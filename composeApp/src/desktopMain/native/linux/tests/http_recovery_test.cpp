// The same reducer and callback delivery used by player_bridge.cpp. No window/GTK/JNI.
#include "http_recovery_events.h"
#include <functional>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <utility>
#include <vector>

namespace {
using Events = LinuxHttpRecoveryEvents;
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
struct Fixture {
    Events events;
    Events::Time now{};
    std::vector<std::pair<std::string, double>> callbacks;

    Events::Result event(mpv_event_id id, void *data = nullptr) {
        mpv_event input{};
        input.event_id = id;
        input.data = data;
        auto result = events.accept(input, now);
        result.deliverFailure([&](const std::string &name, double value) { callbacks.emplace_back(name, value); });
        return result;
    }
    Events::Result log(const char *text, const char *level = "error", const char *prefix = "ffmpeg") {
        mpv_event_log_message message{};
        message.text = text;
        message.level = level;
        message.prefix = prefix;
        return event(MPV_EVENT_LOG_MESSAGE, &message);
    }
    Events::Result end(mpv_end_file_reason reason, int error = MPV_ERROR_LOADING_FAILED) {
        mpv_event_end_file message{};
        message.reason = reason;
        message.error = error;
        return event(MPV_EVENT_END_FILE, &message);
    }
    void playing() {
        event(MPV_EVENT_START_FILE);
        event(MPV_EVENT_FILE_LOADED);
        event(MPV_EVENT_PLAYBACK_RESTART);
    }
    void boundary(uint64_t token) {
        const auto value = std::to_string(token);
        const char *args[] = {Events::SeekBoundary, value.c_str()};
        mpv_event_client_message message{};
        message.num_args = 2;
        message.args = args;
        event(MPV_EVENT_CLIENT_MESSAGE, &message);
    }
    void seek(int64_t target) { boundary(events.seekRequested(target, now)); }
    void http(const char *status = "https: HTTP error 429 Too Many Requests\n") { log(status, "warn"); }
    Events::Result failSeek() { return log("Seek failed (to 738209342, size 162)\n"); }
};

void deterministicTests() {
    int count = 0;
    auto test = [&](const char *name, const std::function<void()> &body) {
        body();
        std::cout << "PASS " << name << '\n';
        ++count;
    };
    test("429 warning + definitive seek error delivers target before rate-limit error", [] {
        Fixture f; f.playing(); f.seek(123456); f.http();
        require(f.callbacks.empty(), "HTTP warning alone must not recover");
        auto result = f.failSeek();
        require(result.failure && result.failure->stop, "failed demuxer must stop");
        require(f.callbacks.size() == 2 && f.callbacks[0] == std::make_pair(std::string("seekFailureTargetMs"), 123456.0), "exact target first");
        require(f.callbacks[1].first == "mpvPlaybackError:HTTP error 429; Seek failed", "shared rate-limit payload");
        require(!f.events.ended(true), "raw eof-reached must not turn failure into completion");
    });
    test("old HTTP evidence expires at the exact ten-second boundary", [] {
        Fixture f; f.playing(); f.seek(10000); f.http(); f.now += Events::HttpLifetime;
        f.failSeek();
        require(f.callbacks.back().first == "mpvPlaybackError:Playback seek failed: Seek failed", "expired 429 leaked");
        require(f.callbacks.front().second == 10000, "still-recent seek lost");
    });
    test("duplicate warnings cannot extend expiry", [] {
        Fixture f; f.playing(); f.http(); f.now += std::chrono::seconds(9); f.http();
        f.now += std::chrono::seconds(1); f.failSeek();
        require(f.callbacks.back().first.find("429") == std::string::npos, "duplicate extended HTTP lifetime");
    });
    test("new source clears HTTP and attempted seek", [] {
        Fixture f; f.playing(); f.seek(40000); f.http(); f.event(MPV_EVENT_START_FILE);
        f.end(MPV_END_FILE_REASON_ERROR);
        require(f.callbacks.size() == 1 && f.callbacks[0].first == "mpvStartupError:loading failed", "source inherited previous evidence");
    });
    test("file-loaded boundary clears speculative opening evidence", [] {
        Fixture f; f.event(MPV_EVENT_START_FILE); f.seek(40000); f.http(); f.event(MPV_EVENT_FILE_LOADED);
        f.failSeek();
        require(f.callbacks.size() == 1 && f.callbacks[0].first.find("429") == std::string::npos, "load retained stale state");
    });
    test("duplicate seek/error/EOF/restart sequence reports one failure", [] {
        Fixture f; f.playing(); f.seek(10000); f.http(); f.failSeek();
        f.http(); f.failSeek(); f.end(MPV_END_FILE_REASON_ERROR);
        require(!f.end(MPV_END_FILE_REASON_EOF).eof, "failed seek became EOF");
        require(!f.event(MPV_EVENT_PLAYBACK_RESTART).restarted, "dying source restarted");
        require(f.callbacks.size() == 2 && f.events.failed(), "duplicate recovery callback");
    });
    test("403 is preserved without becoming a rate limit", [] {
        Fixture f; f.playing(); f.seek(10000); f.http("https: HTTP error 403 Forbidden\n"); f.failSeek();
        require(f.callbacks.back().first == "mpvPlaybackError:HTTP error 403; Seek failed", "incorrect HTTP classification");
    });
    test("normal EOF including a nonfatal HTTP warning remains EOF", [] {
        Fixture f; f.playing(); f.http();
        require(f.end(MPV_END_FILE_REASON_EOF).eof && f.events.ended(true), "normal EOF was converted");
        require(f.callbacks.empty(), "HTTP warning turned EOF into recovery");
    });
    test("late teardown logs after EOF or Stop cannot become playback failures", [] {
        for (const auto reason : {MPV_END_FILE_REASON_EOF, MPV_END_FILE_REASON_STOP}) {
            Fixture f; f.playing(); f.end(reason); f.http(); f.failSeek();
            f.end(MPV_END_FILE_REASON_ERROR);
            require(f.callbacks.empty(), "inactive source emitted recovery");
        }
    });
    test("overlapping seeks discard old HTTP evidence and omit ambiguous positions", [] {
        Fixture f; f.playing(); f.seek(10000); f.http(); f.seek(80000); f.failSeek();
        require(f.callbacks.size() == 1 && f.callbacks[0].first.find("429") == std::string::npos, "older seek poisoned newer request");
    });
    test("overlapping seeks with a fresh 429 recover without inventing a target", [] {
        Fixture f; f.playing(); f.seek(10000); f.seek(80000); f.http(); f.failSeek();
        require(f.callbacks.size() == 1 && f.callbacks[0].first == "mpvPlaybackError:HTTP error 429; Seek failed", "ambiguous seek received a position");
    });
    test("settled seek followed by new seek reports the new position", [] {
        Fixture f; f.playing(); f.seek(10000); f.http(); f.event(MPV_EVENT_PLAYBACK_RESTART);
        f.seek(80000); f.http(); f.failSeek();
        require(f.callbacks.size() == 2 && f.callbacks[0].second == 80000, "new seek reused old target");
    });
    test("generic startup and playback errors retain the existing callback path", [] {
        Fixture f; f.event(MPV_EVENT_START_FILE); f.end(MPV_END_FILE_REASON_ERROR);
        require(f.callbacks[0].first == "mpvStartupError:loading failed", "startup callback changed");
        f.playing(); f.end(MPV_END_FILE_REASON_ERROR, MPV_ERROR_AO_INIT_FAILED);
        require(f.callbacks.back().first == std::string("mpvPlaybackError:") + mpv_error_string(MPV_ERROR_AO_INIT_FAILED), "generic playback callback changed");
    });
    test("successful playback restart clears HTTP and seek state", [] {
        Fixture f; f.playing(); f.seek(10000); f.http(); f.event(MPV_EVENT_PLAYBACK_RESTART); f.failSeek();
        require(f.callbacks.size() == 1 && f.callbacks[0].first.find("429") == std::string::npos, "success retained old evidence");
    });
    test("seek target expires at the exact fifteen-second boundary", [] {
        Fixture f; f.playing(); f.seek(10000); f.now += Events::SeekLifetime; f.failSeek();
        require(f.callbacks.size() == 1, "expired target leaked");
    });
    test("an expired outstanding seek still makes a replacement ambiguous", [] {
        Fixture f; f.playing(); f.seek(10000); f.now += Events::SeekLifetime;
        f.seek(80000); f.http(); f.failSeek();
        require(f.callbacks.size() == 1, "expiry made an uncompleted seek look settled");
    });
    test("HTTP warning before a new seek cannot poison it", [] {
        Fixture f; f.playing(); f.http(); f.seek(10000); f.failSeek();
        require(f.callbacks.back().first.find("429") == std::string::npos, "pre-seek HTTP reused");
    });
    test("queued pre-seek HTTP and seek failures are ignored before the command boundary", [] {
        Fixture f; f.playing(); const auto token = f.events.seekRequested(10000, f.now);
        f.http(); f.failSeek(); require(f.callbacks.empty(), "queued older failure recovered");
        f.boundary(token); f.failSeek();
        require(f.callbacks.size() == 2 && f.callbacks.back().first.find("429") == std::string::npos, "queued HTTP crossed boundary");
    });
    test("old command boundary cannot activate a newer pending request", [] {
        Fixture f; f.playing(); const auto old = f.events.seekRequested(10000, f.now);
        const auto current = f.events.seekRequested(80000, f.now);
        f.boundary(old); f.http(); f.failSeek(); require(f.callbacks.empty(), "older marker accepted");
        f.boundary(current); f.http(); f.failSeek();
        require(f.callbacks.size() == 1, "overlapping position incorrectly reported");
    });
    test("queued earlier restart does not complete a newly requested seek", [] {
        Fixture f; f.playing(); const auto token = f.events.seekRequested(10000, f.now);
        f.event(MPV_EVENT_PLAYBACK_RESTART); f.boundary(token); f.http(); f.failSeek();
        require(f.callbacks.size() == 2 && f.callbacks[0].second == 10000, "older restart erased current target");
    });
    test("unrelated generic playback error cannot inherit a pending seek position", [] {
        Fixture f; f.playing(); f.seek(10000); f.end(MPV_END_FILE_REASON_ERROR, MPV_ERROR_AO_INIT_FAILED);
        require(f.callbacks.size() == 1 && f.callbacks[0].first.find("mpvPlaybackError:") == 0, "generic error acquired a seek target");
    });
    test("cancelled/verbose seeks and unrelated prefixes never cause recovery", [] {
        Fixture f; f.playing(); f.seek(10000);
        f.log("HTTP error 429 Too Many Requests", "warn", "other");
        f.log("Seek failed (to 1, size 0)", "v");
        f.log("Seek failed (to 1, size 0)", "warn");
        require(f.callbacks.empty(), "nondefinitive seek recovered");
        f.failSeek();
        require(f.callbacks.back().first.find("429") == std::string::npos, "unrelated HTTP log classified");
    });
    test("malformed HTTP status cannot look like 429", [] {
        for (const char *message : {"HTTP error 4290 x", "HTTP error 429x", "HTTP error 42 x", "HTTP error abc"}) {
            Fixture f; f.playing(); f.http(message); f.failSeek();
            require(f.callbacks[0].first.find("429") == std::string::npos, "invalid HTTP status accepted");
        }
    });
    test("rejected command clears speculative target and HTTP state", [] {
        Fixture f; f.playing(); f.seek(10000); f.http(); f.events.seekRejected(); f.failSeek();
        require(f.callbacks.size() == 1 && f.callbacks[0].first.find("429") == std::string::npos, "rejected seek leaked");
    });
    test("lost event ordering invalidates pending evidence", [] {
        Fixture f; f.playing(); f.seek(10000); f.http(); f.event(MPV_EVENT_QUEUE_OVERFLOW); f.failSeek();
        require(f.callbacks.size() == 1 && f.callbacks[0].first.find("429") == std::string::npos, "overflow kept evidence");
    });
    test("new HTTP status replaces old status without forwarding raw text", [] {
        Fixture f; f.playing(); f.http(); f.http("https: HTTP error 403 Forbidden https://example.invalid/?secret=x"); f.failSeek();
        require(f.callbacks[0].first == "mpvPlaybackError:HTTP error 403; Seek failed", "raw/stale HTTP leaked");
    });
    test("terminal state only resets on a new source, which can fail again", [] {
        Fixture f; f.playing(); f.http(); f.failSeek();
        f.event(MPV_EVENT_FILE_LOADED); f.event(MPV_EVENT_PLAYBACK_RESTART);
        require(f.events.failed(), "late success resurrected a dead source");
        f.playing(); require(!f.events.failed(), "new source remained failed");
        f.seek(90000); f.http(); f.failSeek(); require(f.callbacks.size() == 3, "new source failure suppressed");
    });
    test("startup HTTP error feeds existing rate-limit classification without a seek target", [] {
        Fixture f; f.event(MPV_EVENT_START_FILE); f.http(); f.end(MPV_END_FILE_REASON_ERROR);
        require(f.callbacks.size() == 1 && f.callbacks[0].first == "mpvStartupError:HTTP error 429; loading failed", "startup HTTP detail lost");
    });
    test("relative targets use pre-command playhead with bounds and unknown-position handling", [] {
        require(Events::seekTarget(30000, true, 10.25) == 40250, "relative target wrong");
        require(Events::seekTarget(-30000, true, 10.25) == 0, "relative target negative");
        require(!Events::seekTarget(30000, true, -1), "unknown playhead invented");
        require(!Events::seekTarget(INT64_MAX, true, 10), "overflow target accepted");
        require(Events::seekTarget(123456, false, 999) == 123456, "absolute target derived from current time");
    });
    std::cout << count << " deterministic regression groups passed\n";
}

// Used only by http_recovery_test.py with its own loopback server. Null outputs,
// config/scripts/hardware decoding disabled, no GUI, audio device or application data.
void runtimeProbe(const char *url, const char *armFile) {
    require(std::string_view(url).substr(0, 17) == "http://127.0.0.1:", "probe requires loopback fixture");
    const auto mpv = std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)>(mpv_create(), mpv_terminate_destroy);
    require(bool(mpv), "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"terminal", "no"},
             {"vo", "null"}, {"ao", "null"}, {"hwdec", "no"}, {"pause", "yes"}, {"idle", "yes"},
             {"cache", "no"}, {"demuxer-max-bytes", "64KiB"}, {"demuxer-readahead-secs", "0"},
             {"stream-lavf-o", "reconnect=0"}, {"network-timeout", "2"}}) {
        require(mpv_set_option_string(mpv.get(), name, value) >= 0, name);
    }
    require(mpv_initialize(mpv.get()) >= 0, "mpv_initialize");
    require(mpv_request_log_messages(mpv.get(), "warn") >= 0, "request warning-level events");
    char *version = mpv_get_property_string(mpv.get(), "mpv-version");
    require(version && std::string_view(version).find("0.41") != std::string_view::npos, "probe must exercise bundled mpv 0.41");
    std::cout << "Runtime " << version << '\n'; mpv_free(version);
    const char *load[] = {"loadfile", url, nullptr};
    require(mpv_command(mpv.get(), load) >= 0, "loadfile");
    Events events;
    bool issued = false;
    int callbacks = 0;
    auto deadline = Events::Clock::now() + std::chrono::seconds(10);
    while (Events::Clock::now() < deadline) {
        const auto *event = mpv_wait_event(mpv.get(), 0.1);
        auto result = events.accept(*event, Events::Clock::now());
        result.deliverFailure([&](const std::string &name, double value) {
            if (callbacks++ == 0) require(name == "seekFailureTargetMs" && value == 90000, "real attempted position not first");
            else require(name == "mpvPlaybackError:HTTP error 429; Seek failed", "real mpv rate-limit callback mismatch: " + name);
        });
        if (result.failure) {
            require(result.failure->stop && callbacks == 2 && !events.ended(true), "real failed seek/EOF contract");
            const char *stop[] = {"stop", nullptr};
            require(mpv_command(mpv.get(), stop) >= 0, "stop failed seek");
            std::cout << "PASS real mpv 0.41 range 429 -> failed seek -> ordered recovery callbacks\n";
            return;
        }
        if (result.restarted && !issued) {
            issued = true;
            std::ofstream armed(armFile);
            armed << "armed";
            armed.close();
            require(bool(armed), "arm local range-failure fixture");
            require(events.commandSeek(mpv.get(), 90000, "absolute+exact", 0, Events::Clock::now()) >= 0, "seek");
        }
    }
    throw std::runtime_error("real mpv failed-seek fixture timed out");
}
}

int main(int argc, char **argv) {
    try {
        if (argc == 3) runtimeProbe(argv[1], argv[2]);
        else deterministicTests();
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
