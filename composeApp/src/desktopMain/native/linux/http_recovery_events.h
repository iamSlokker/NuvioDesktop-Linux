#pragma once

#include <mpv/client.h>
#include <algorithm>
#include <chrono>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <string_view>

// Only translates libmpv evidence into the existing Kotlin callbacks. No retry,
// provider policy, logging sink or native window. Caller serializes with seek commands.
class LinuxHttpRecoveryEvents {
public:
    using Clock = std::chrono::steady_clock;
    using Time = Clock::time_point;
    static constexpr auto HttpLifetime = std::chrono::seconds(10);
    static constexpr auto SeekLifetime = std::chrono::seconds(15);
    static constexpr const char *SeekBoundary = "nuvio-linux-seek-boundary";

    static std::optional<int64_t> seekTarget(int64_t ms, bool relative, double currentSeconds) {
        if (!relative) return ms;
        const long double target = static_cast<long double>(currentSeconds) * 1000 + ms;
        if (!std::isfinite(currentSeconds) || currentSeconds < 0 ||
            target > std::numeric_limits<int64_t>::max()) return std::nullopt;
        return static_cast<int64_t>(std::max(0.0L, std::round(target)));
    }

    struct Failure {
        std::string event;
        std::optional<int64_t> seekTargetMs;
        bool stop = false;
    };
    struct Result {
        std::optional<Failure> failure;
        bool started = false;
        bool loaded = false;
        bool restarted = false;
        bool eof = false;

        template<class Send> void deliverFailure(Send send) const {
            if (!failure) return;
            if (failure->seekTargetMs)
                send("seekFailureTargetMs", static_cast<double>(*failure->seekTargetMs));
            send(failure->event, 0.0);
        }
    };

    // Called before issuing the synchronous command, under the same lock as accept().
    // A new seek cannot inherit HTTP evidence from the preceding operation.
    uint64_t seekRequested(std::optional<int64_t> targetMs, Time now) {
        expire(now);
        const bool overlapping = seekOutstanding_;
        http_.reset();
        if (++seekToken_ == 0) ++seekToken_;
        seek_ = Seek{targetMs, now, overlapping, seekToken_, false};
        seekOutstanding_ = true;
        return seekToken_;
    }

    int commandSeek(mpv_handle *mpv, int64_t ms, const char *mode, double currentSeconds, Time now) {
        const bool relative = std::string_view(mode).substr(0, 8) == "relative";
        const auto token = std::to_string(seekRequested(seekTarget(ms, relative, currentSeconds), now));
        // This reaches our event queue before the seek. Logs already queued by an older
        // operation cannot become evidence for the newly requested target.
        const char *boundary[] = {"script-message-to", mpv_client_name(mpv), SeekBoundary, token.c_str(), nullptr};
        int result = mpv_command(mpv, boundary);
        if (result >= 0) {
            const auto seconds = std::to_string(static_cast<double>(ms) / 1000.0);
            const char *command[] = {"seek", seconds.c_str(), mode, nullptr};
            result = mpv_command(mpv, command);
        }
        if (result < 0) seekRejected();
        return result;
    }

    void seekRejected() { http_.reset(); seek_.reset(); }
    bool failed() const { return failed_; }
    bool ended(bool mpvEof) const { return !failed_ && mpvEof; }

    Result accept(const mpv_event &event, Time now) {
        expire(now);
        Result result;
        if (event.event_id == MPV_EVENT_START_FILE) {
            clearPending();
            failed_ = loaded_ = playing_ = false;
            active_ = true;
            result.started = true;
        } else if (event.event_id == MPV_EVENT_FILE_LOADED && active_ && !failed_) {
            clearPending();
            loaded_ = result.loaded = true;
        } else if (event.event_id == MPV_EVENT_PLAYBACK_RESTART && active_ && !failed_) {
            // An already queued restart precedes the new command boundary. It says
            // nothing about whether the newly requested seek has completed.
            if (!seek_ || seek_->boundarySeen) clearPending();
            playing_ = result.restarted = true;
        } else if (event.event_id == MPV_EVENT_QUEUE_OVERFLOW) {
            // Lost ordering cannot justify retaining a status or attempted position.
            clearPending();
            seekOutstanding_ = true;
        } else if (event.event_id == MPV_EVENT_CLIENT_MESSAGE && event.data && seek_) {
            const auto &message = *static_cast<const mpv_event_client_message *>(event.data);
            if (message.num_args == 2 && message.args && message.args[0] && message.args[1] &&
                std::string_view(message.args[0]) == SeekBoundary) {
                const std::string_view tokenText(message.args[1]);
                uint64_t token = 0;
                const auto parsed = std::from_chars(tokenText.data(), tokenText.data() + tokenText.size(), token);
                if (parsed.ec == std::errc{} && parsed.ptr == tokenText.data() + tokenText.size() &&
                    token == seek_->token && !seek_->boundarySeen) {
                    http_.reset();
                    seek_->boundarySeen = true;
                }
            }
        } else if (event.event_id == MPV_EVENT_LOG_MESSAGE && event.data && active_ && !failed_) {
            if (seek_ && !seek_->boundarySeen) return result;
            const auto &log = *static_cast<const mpv_event_log_message *>(event.data);
            const std::string_view prefix = log.prefix ? log.prefix : "";
            const std::string_view level = log.level ? log.level : "";
            const std::string_view text = log.text ? log.text : "";
            if (prefix != "ffmpeg") return result;
            // FFmpeg http.c logs HTTP errors at AV_LOG_WARNING in the bundled runtime.
            if (level == "warn" || level == "error" || level == "fatal") {
                if (const int status = httpStatus(text)) {
                    // Repeated lines must not extend the lifetime of the original evidence.
                    if (!http_ || http_->status != status) http_ = Http{status, now};
                }
            }
            // stream.c lowers cancelled seeks to verbose severity. Never recover those.
            if ((level == "error" || level == "fatal") &&
                (text == "Seek failed" || text.substr(0, 12) == "Seek failed ") &&
                (loaded_ || http_)) {
                result.failure = fail(http_ ? statusText() + "; Seek failed"
                                            : "Playback seek failed: Seek failed", true);
            }
        } else if (event.event_id == MPV_EVENT_END_FILE && event.data) {
            const auto &end = *static_cast<const mpv_event_end_file *>(event.data);
            if (end.reason == MPV_END_FILE_REASON_ERROR && active_ && !failed_) {
                std::string message = mpv_error_string(end.error);
                if (http_) message = statusText() + "; " + message;
                result.failure = fail(message, false);
            } else if (end.reason == MPV_END_FILE_REASON_EOF && active_ && !failed_) {
                // A status alone is not a terminal failure; do not reinterpret normal EOF.
                result.eof = true;
            }
            active_ = false;
            clearPending();
        }
        return result;
    }

private:
    struct Http { int status; Time at; };
    struct Seek { std::optional<int64_t> target; Time at; bool ambiguous; uint64_t token; bool boundarySeen; };
    std::optional<Http> http_;
    std::optional<Seek> seek_;
    bool loaded_ = false;
    bool playing_ = false;
    bool active_ = false;
    bool failed_ = false; // One terminal error per load, including the later END_FILE error/EOF.
    bool seekOutstanding_ = false;
    uint64_t seekToken_ = 0;

    static int httpStatus(std::string_view text) {
        constexpr std::string_view marker = "HTTP error ";
        const auto at = text.find(marker);
        if (at == std::string_view::npos) return 0;
        const auto code = text.substr(at + marker.size());
        if (code.size() < 3 || code[0] < '4' || code[0] > '5' ||
            code[1] < '0' || code[1] > '9' || code[2] < '0' || code[2] > '9' ||
            (code.size() > 3 && code[3] != ' ' && code[3] != '\r' && code[3] != '\n')) return 0;
        return (code[0] - '0') * 100 + (code[1] - '0') * 10 + code[2] - '0';
    }
    std::string statusText() const { return "HTTP error " + std::to_string(http_->status); }
    void clearPending() { http_.reset(); seek_.reset(); seekOutstanding_ = false; }
    void expire(Time now) {
        if (http_ && (now < http_->at || now - http_->at >= HttpLifetime)) http_.reset();
        if (seek_ && (now < seek_->at || now - seek_->at >= SeekLifetime)) {
            // HTTP evidence attached to an expired seek cannot leak into later work.
            http_.reset();
            seek_.reset();
            // Keep overlap ambiguity until playback settles, even after target expiry.
        }
    }
    Failure fail(const std::string &message, bool stop) {
        Failure failure{std::string(playing_ || (stop && loaded_) ? "mpvPlaybackError:" : "mpvStartupError:") + message,
                        std::nullopt, stop};
        if ((stop || http_) && loaded_ && seek_ && seek_->boundarySeen && !seek_->ambiguous)
            failure.seekTargetMs = seek_->target;
        failed_ = true;
        clearPending();
        return failure;
    }
};
