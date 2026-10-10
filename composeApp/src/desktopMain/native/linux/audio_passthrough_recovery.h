#pragma once

#include <mpv/client.h>
#include <chrono>
#include <charconv>
#include <optional>
#include <string>
#include <string_view>

// libmpv 0.41 can select its PCM decoder after a rejected SPDIF output without
// reopening the AO. Rebuild the selected audio chain once; never change storage.
// Caller serializes this with other mpv operations and excludes shutdown.
class LinuxAudioPassthroughRecovery {
public:
    using Clock = std::chrono::steady_clock;
    static constexpr auto EvidenceLifetime = std::chrono::seconds(5);
    struct Result { bool recovered = false; int error = 0; };

    template<class Get, class Set>
    Result accept(const mpv_event &event, Clock::time_point now, Get get, Set set) {
        if (rejection_ && (now < *rejection_ || now - *rejection_ >= EvidenceLifetime))
            rejection_.reset();
        if (event.event_id == MPV_EVENT_START_FILE) {
            active_ = true;
            attempted_ = false;
            rejection_.reset();
            sourceId_ = event.data ? std::to_string(
                static_cast<const mpv_event_start_file *>(event.data)->playlist_entry_id) : "";
            if (!sourceId_.empty() && currentSource(get) != sourceId_) {
                active_ = false; // An older START_FILE can still be queued after replacement.
                return {};
            }
            // The override belongs to the preceding attempt, not the next source.
            // Do not overwrite a different value explicitly set in the meantime.
            if (!restoreSpdif_.empty()) {
                if (get("audio-spdif").empty()) {
                    const int error = set("audio-spdif", restoreSpdif_.c_str());
                    if (error < 0) return {false, error};
                }
                restoreSpdif_.clear();
            }
        } else if (event.event_id == MPV_EVENT_END_FILE ||
                   event.event_id == MPV_EVENT_SHUTDOWN ||
                   event.event_id == MPV_EVENT_QUEUE_OVERFLOW) {
            active_ = false;
            rejection_.reset();
        } else if (event.event_id == MPV_EVENT_PLAYBACK_RESTART) {
            rejection_.reset();
        } else if (event.event_id == MPV_EVENT_LOG_MESSAGE && event.data && active_ && !attempted_) {
            const auto &log = *static_cast<const mpv_event_log_message *>(event.data);
            const std::string_view prefix = log.prefix ? log.prefix : "";
            const std::string_view level = log.level ? log.level : "";
            std::string_view text = log.text ? log.text : "";
            while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.remove_suffix(1);
            constexpr std::string_view aoFailure = "Failed to initialize audio driver '";
            if ((level == "error" || level == "fatal") &&
                ((prefix == "ao" && text.substr(0, aoFailure.size()) == aoFailure) ||
                 (prefix == "cplayer" && text == "Passthrough format unsupported."))) {
                if (!rejection_) rejection_ = now;
            } else if (rejection_ && prefix == "cplayer" && level == "v" &&
                       text == "Falling back to PCM output.") {
                rejection_.reset();
                attempted_ = true; // Including property/selection failures: no retry loop.
                const auto spdif = get("audio-spdif");
                if (spdif.empty() || !get("current-ao").empty() ||
                    get("idle-active") == "yes" || get("eof-reached") == "yes" ||
                    (!sourceId_.empty() && currentSource(get) != sourceId_)) return {};
                const auto aid = get("aid");
                int id = -1;
                const auto parsed = std::from_chars(aid.data(), aid.data() + aid.size(), id);
                if (parsed.ec != std::errc{} || parsed.ptr != aid.data() + aid.size() || id < 0)
                    return {}; // No selected track (possibly deliberately disabled): leave it alone.
                int error = set("audio-spdif", "");
                if (error < 0) return {false, error};
                restoreSpdif_ = spdif;
                error = set("aid", "no");
                if (error >= 0) error = set("aid", aid.c_str());
                return {error >= 0, error < 0 ? error : 0};
            }
        }
        return {};
    }

    Result accept(mpv_handle *mpv, const mpv_event &event) {
        return accept(event, Clock::now(), [mpv](const char *name) {
            char *value = mpv_get_property_string(mpv, name);
            std::string result = value ? value : "";
            mpv_free(value);
            return result;
        }, [mpv](const char *name, const char *value) {
            return mpv_set_property_string(mpv, name, value);
        });
    }

private:
    template<class Get> static std::string currentSource(Get get) {
        const auto position = get("playlist-playing-pos");
        int index = -1;
        const auto parsed = std::from_chars(position.data(), position.data() + position.size(), index);
        if (parsed.ec != std::errc{} || parsed.ptr != position.data() + position.size() || index < 0) return {};
        return get(("playlist/" + position + "/id").c_str());
    }
    bool active_ = false;
    bool attempted_ = false;
    std::optional<Clock::time_point> rejection_;
    std::string restoreSpdif_;
    std::string sourceId_;
};
