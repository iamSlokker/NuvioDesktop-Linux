#include "audio_passthrough_recovery.h"
#include "http_recovery_events.h"
#include <functional>
#include <iostream>
#include <map>
#include <stdexcept>
#include <vector>

using Recovery = LinuxAudioPassthroughRecovery;
using Sets = std::vector<std::pair<std::string, std::string>>;
void require(bool ok, const char *message) { if (!ok) throw std::runtime_error(message); }

struct Fixture {
    Recovery recovery;
    Recovery::Clock::time_point now{};
    std::map<std::string, std::string> properties{
        {"audio-spdif", "ac3,dts"}, {"aid", "1"}, {"playlist-playing-pos", "0"}, {"playlist/0/id", "1"}};
    Sets sets;
    int failAt = -1;
    Recovery::Result accept(const mpv_event &event) {
        return recovery.accept(event, now, [&](const char *key) { return properties[key]; },
            [&](const char *key, const char *value) -> int {
                sets.emplace_back(key, value);
                if (static_cast<int>(sets.size()) - 1 == failAt) return MPV_ERROR_PROPERTY_ERROR;
                properties[key] = value;
                return 0;
            });
    }
    Recovery::Result event(mpv_event_id id) { mpv_event e{}; e.event_id = id; return accept(e); }
    Recovery::Result start(int64_t id = 1) {
        mpv_event_start_file start{}; start.playlist_entry_id = id;
        mpv_event e{}; e.event_id = MPV_EVENT_START_FILE; e.data = &start;
        return accept(e);
    }
    Recovery::Result log(const char *prefix, const char *level, const char *text) {
        mpv_event_log_message log{}; log.prefix = prefix; log.level = level; log.text = text;
        mpv_event e{}; e.event_id = MPV_EVENT_LOG_MESSAGE; e.data = &log;
        return accept(e);
    }
    Recovery::Result reject() { return log("ao", "error", "Failed to initialize audio driver 'pcm'\n"); }
    Recovery::Result marker() { return log("cplayer", "v", "Falling back to PCM output.\n"); }
};

int main() {
    int groups = 0;
    auto test = [&](const char *name, const std::function<void()> &body) {
        body(); ++groups; std::cout << "PASS " << name << '\n';
    };
    try {
        test("inactive and ordinary startup logs cannot recover", [] {
            Fixture f; f.reject(); f.marker(); f.start(); f.marker();
            require(f.sets.empty(), "unexpected reset");
        });
        test("AO failure requires the definitive PCM fallback marker", [] {
            Fixture f; f.start(); f.reject();
            f.log("cplayer", "v", "Starting playback...");
            require(f.sets.empty(), "AO failure alone was sufficient");
        });
        test("wrong prefixes, severities and approximate messages are ignored", [] {
            for (int i = 0; i < 3; ++i) {
                Fixture f; f.start();
                f.log(i == 0 ? "other" : "ao", i == 1 ? "warn" : "error",
                      i == 2 ? "Failed to initialize unrelated audio driver" : "Failed to initialize audio driver 'pcm'");
                f.marker(); require(f.sets.empty(), "false rejection");
            }
            Fixture f; f.start(); f.reject();
            f.log("other", "v", "Falling back to PCM output.");
            f.log("cplayer", "info", "Falling back to PCM output.");
            f.log("cplayer", "v", "Falling back to PCM output. unrelated");
            require(f.sets.empty(), "false marker");
        });
        test("output rejection rebuilds the selected chain in order", [] {
            Fixture f; f.start(); f.reject(); require(f.marker().recovered, "no recovery");
            require(f.sets == Sets{{"audio-spdif", ""}, {"aid", "no"}, {"aid", "1"}}, "wrong order/track");
        });
        test("unsupported SPDIF format follows the same production recovery", [] {
            Fixture f; f.start(); f.log("cplayer", "error", "Passthrough format unsupported.\r\n");
            require(f.marker().recovered, "no format recovery");
        });
        test("disabled passthrough leaves PCM alone", [] {
            Fixture f; f.properties["audio-spdif"] = ""; f.start(); f.reject(); f.marker();
            require(f.sets.empty(), "disabled preference overridden");
        });
        test("already working output is not disturbed", [] {
            Fixture f; f.start(); f.reject(); f.properties["current-ao"] = "null"; f.marker();
            require(f.sets.empty(), "healthy output reset");
        });
        test("rejection expires exactly at five seconds and duplicates cannot renew it", [] {
            Fixture f; f.start(); f.reject(); f.now += std::chrono::seconds(4); f.reject();
            f.now += std::chrono::seconds(1); f.marker();
            require(f.sets.empty(), "stale rejection reused");
        });
        test("successful restart clears speculative rejection", [] {
            Fixture f; f.start(); f.reject(); f.event(MPV_EVENT_PLAYBACK_RESTART); f.marker();
            require(f.sets.empty(), "old error reused after success");
        });
        test("EOF and shutdown prevent late recovery", [] {
            for (auto id : {MPV_EVENT_END_FILE, MPV_EVENT_SHUTDOWN}) {
                Fixture f; f.start(); f.reject(); f.event(id); f.marker(); f.reject(); f.marker();
                require(f.sets.empty(), "terminal recovery");
            }
        });
        test("one shot survives repeated errors and playback restart", [] {
            Fixture f; f.start(); f.reject(); f.marker();
            f.event(MPV_EVENT_PLAYBACK_RESTART); f.reject(); f.marker();
            require(f.sets.size() == 3, "recovery loop");
        });
        test("new source restores runtime preference and rearms once", [] {
            Fixture f; f.start(); f.reject(); f.marker(); f.event(MPV_EVENT_END_FILE);
            f.properties["playlist/0/id"] = "2"; f.start(2);
            require(f.properties["audio-spdif"] == "ac3,dts", "next source lost passthrough");
            require(f.sets.size() == 4, "unnecessary chain churn on start");
            f.marker(); require(f.sets.size() == 4, "previous source rejection survived");
            f.reject(); require(f.marker().recovered, "new source not rearmed");
        });
        test("queued old-source logs and START_FILE cannot reset a replacement", [] {
            Fixture f; f.start(); f.reject(); f.properties["playlist/0/id"] = "2"; f.marker();
            require(f.sets.empty(), "old source changed new source");
            f.start(1); f.reject(); f.marker(); require(f.sets.empty(), "stale start accepted");
        });
        test("absent or disabled selected track is left to existing playback/error handling", [] {
            for (const char *aid : {"", "auto", "no", "-1", "1x"}) {
                Fixture f; f.properties["aid"] = aid; f.start(); f.reject();
                const auto result = f.marker();
                require(!result.recovered && !result.error, "missing track caused a synthetic failure");
                require(f.sets.empty(), "invalid aid modified");
            }
        });
        test("each property failure propagates once through the existing HTTP error translator", [] {
            for (int i = 0; i < 3; ++i) {
                Fixture f; f.failAt = i; f.start(); f.reject(); const auto audio = f.marker();
                require(audio.error == MPV_ERROR_PROPERTY_ERROR && !audio.recovered, "property error lost");
                f.reject(); f.marker(); require(static_cast<int>(f.sets.size()) == i + 1, "failed recovery retried");
                LinuxHttpRecoveryEvents http; mpv_event e{}; e.event_id = MPV_EVENT_START_FILE;
                http.accept(e, f.now);
                mpv_event_end_file end{}; end.reason = MPV_END_FILE_REASON_ERROR; end.error = audio.error;
                e.event_id = MPV_EVENT_END_FILE; e.data = &end;
                const auto translated = http.accept(e, f.now);
                require(translated.failure && translated.failure->event ==
                    std::string("mpvStartupError:Playback loading failed: ") + mpv_error_string(audio.error), "generic callback changed");
                require(!http.accept(e, f.now).failure, "duplicate terminal error");
            }
        });
        test("lost event ordering disables recovery until a new source", [] {
            Fixture f; f.start(); f.reject(); f.event(MPV_EVENT_QUEUE_OVERFLOW); f.marker();
            f.reject(); f.marker(); require(f.sets.empty(), "overflow recovery used lost ordering");
            f.start(); f.reject(); require(f.marker().recovered, "next source not rearmed");
        });
        test("live idle/EOF state prevents queued pre-EOF fallback from acting", [] {
            for (const char *key : {"idle-active", "eof-reached"}) {
                Fixture f; f.start(); f.reject(); f.properties[key] = "yes"; f.marker();
                require(f.sets.empty(), "EOF converted to audio reset");
            }
        });
        test("restoration respects an explicit newer runtime preference", [] {
            Fixture f; f.start(); f.reject(); f.marker();
            f.properties["audio-spdif"] = "dts"; f.start();
            require(f.properties["audio-spdif"] == "dts" && f.sets.size() == 3, "new preference overwritten");
        });
        test("failed next-source restoration reports its original error", [] {
            Fixture f; f.start(); f.reject(); f.marker(); f.failAt = 3;
            require(f.start().error == MPV_ERROR_PROPERTY_ERROR, "restore error lost");
        });
        std::cout << groups << " deterministic audio recovery groups passed\n";
    } catch (const std::exception &error) {
        std::cerr << "FAIL after " << groups << " groups: " << error.what() << '\n'; return 1;
    }
}
