#include <jni.h>
#include <mpv/client.h>
#include <glib.h>
#include "controls_overlay.h"
#include "awt_x11_lifecycle.h"
#include "seek_thumbnails.h"
#include "separate_audio.h"
#include "http_recovery_events.h"
#include "audio_passthrough_recovery.h"
#include "player_session_options.h"
#include "hdr_wayland_session.h"
#include "live_cache_seek.h"
#include "auto_crop.h"
#include "video_source_size.h"
#include "speech_spot.h"
#include "subtitle_add.h"

#include <algorithm>
#include <cctype>
#include <atomic>
#include <clocale>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <future>
#include <iomanip>
#include <limits>
#include <memory>
#include <locale>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {
void throwJavaError(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) {
        env->ThrowNew(type, message);
        env->DeleteLocalRef(type);
    }
}

void checkMpv(int result, const char *operation) {
    if (result < 0) throw std::runtime_error(std::string(operation) + ": " + mpv_error_string(result));
}

std::string text(JNIEnv *env, jstring value) {
    if (!value) return {};
    // mpv needs standard UTF-8, not JNI's modified UTF-8 (notably for non-BMP filenames).
    jsize length = env->GetStringLength(value);
    std::vector<jchar> utf16(length);
    env->GetStringRegion(value, 0, length, utf16.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read JNI string.");
    std::string result;
    for (size_t i = 0; i < utf16.size(); ++i) {
        uint32_t code = utf16[i];
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < utf16.size() &&
            utf16[i + 1] >= 0xdc00 && utf16[i + 1] <= 0xdfff) {
            code = 0x10000 + ((code - 0xd800) << 10) + (utf16[++i] - 0xdc00);
        } else if (code >= 0xd800 && code <= 0xdfff) {
            code = 0xfffd;
        }
        if (code < 0x80) result += static_cast<char>(code);
        else {
            if (code >= 0x10000) result += static_cast<char>(0xf0 | (code >> 18));
            else if (code >= 0x800) result += static_cast<char>(0xe0 | (code >> 12));
            else result += static_cast<char>(0xc0 | (code >> 6));
            if (code >= 0x10000) result += static_cast<char>(0x80 | ((code >> 12) & 0x3f));
            if (code >= 0x800) result += static_cast<char>(0x80 | ((code >> 6) & 0x3f));
            result += static_cast<char>(0x80 | (code & 0x3f));
        }
    }
    return result;
}

std::string trimmed(const std::string &value) {
    const char *start = value.data();
    const char *end = start + value.size();
    while (start < end) {
        gunichar code = g_utf8_get_char_validated(start, end - start);
        if (code == static_cast<gunichar>(-1) || code == static_cast<gunichar>(-2) ||
            !g_unichar_isspace(code)) break;
        start = g_utf8_next_char(start);
    }
    while (start < end) {
        const char *previous = g_utf8_find_prev_char(start, end);
        if (!previous) break;
        gunichar code = g_utf8_get_char_validated(previous, end - previous);
        if (code == static_cast<gunichar>(-1) || code == static_cast<gunichar>(-2) ||
            !g_unichar_isspace(code)) break;
        end = previous;
    }
    return std::string(start, end);
}

std::string caseFolded(const std::string &value) {
    std::unique_ptr<gchar, decltype(&g_free)> valid(
        g_utf8_make_valid(value.data(), value.size()), g_free);
    std::unique_ptr<gchar, decltype(&g_free)> folded(g_utf8_casefold(valid.get(), -1), g_free);
    return folded.get();
}

std::string jsonString(const std::string &value) {
    // Keep JSON ASCII-only so NewStringUTF never receives standard four-byte UTF-8.
    std::string result = "\"";
    auto unicodeEscape = [&](gunichar unit) {
        char escaped[7];
        std::snprintf(escaped, sizeof(escaped), "\\u%04x", static_cast<unsigned>(unit));
        result += escaped;
    };
    for (size_t index = 0; index < value.size();) {
        gunichar code = static_cast<unsigned char>(value[index]);
        size_t bytes = 1;
        if (code >= 0x80) {
            code = g_utf8_get_char_validated(value.data() + index, value.size() - index);
            if (code == static_cast<gunichar>(-1) || code == static_cast<gunichar>(-2)) {
                code = 0xfffd;
            } else {
                bytes = g_utf8_next_char(value.data() + index) - (value.data() + index);
            }
        }
        index += bytes;
        if (code == '"' || code == '\\') {
            result += '\\';
            result += static_cast<char>(code);
        } else if (code > 0xffff) {
            code -= 0x10000;
            unicodeEscape(0xd800 + (code >> 10));
            unicodeEscape(0xdc00 + (code & 0x3ff));
        } else if (code < 0x20 || code >= 0x80) {
            unicodeEscape(code);
        } else {
            result += static_cast<char>(code);
        }
    }
    return result + '"';
}

std::string subtitleCodecDisplayName(const std::string &value) {
    std::string raw = trimmed(value);
    std::string codec = caseFolded(raw);
    auto contains = [&](const char *name) { return codec.find(name) != std::string::npos; };
    if (contains("eac3") || contains("e-ac-3") || contains("e ac-3")) {
        return contains("joc") || contains("atmos") ? "E-AC-3-JOC" : "E-AC-3";
    }
    if (contains("truehd") || contains("true hd")) return "TrueHD";
    if (contains("ac3") || contains("ac-3")) return "AC-3";
    if (contains("dts-hd") || contains("dtshd") || contains("dts hd")) return "DTS-HD";
    if (contains("dts") || codec == "dca") return "DTS";
    if (contains("aac")) return "AAC";
    if (contains("mp3") || contains("mpeg audio")) return "MP3";
    if (contains("mp2")) return "MP2";
    if (contains("opus")) return "Opus";
    if (contains("vorbis")) return "Vorbis";
    if (contains("flac")) return "FLAC";
    if (contains("alac")) return "ALAC";
    if (contains("pcm") || contains("wav")) return "WAV";
    if (contains("amr_wb") || contains("amr-wb")) return "AMR-WB";
    if (contains("amr_nb") || contains("amr-nb")) return "AMR-NB";
    if (contains("amr")) return "AMR";
    if (contains("iamf")) return "IAMF";
    if (contains("mpegh") || contains("mpeg-h")) return "MPEG-H";
    if (contains("pgs") || contains("hdmv")) return "PGS";
    if (contains("subrip") || codec == "srt") return "SRT";
    if (contains("ass") || contains("ssa")) return "SSA";
    if (contains("webvtt") || codec == "vtt") return "VTT";
    if (contains("ttml")) return "TTML";
    if (contains("mov_text") || contains("tx3g")) return "TX3G";
    if (contains("dvb")) return "DVB";
    return raw;
}

std::vector<std::string> strings(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> result;
    if (!array) return result;
    for (jsize i = 0; i < env->GetArrayLength(array); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        result.push_back(text(env, value));
        if (value) env->DeleteLocalRef(value);
    }
    return result;
}

template<class F> auto guarded(JNIEnv *env, F action) -> decltype(action()) {
    try {
        return action();
    } catch (const std::exception &error) {
        throwJavaError(env, error.what());
    } catch (...) {
        throwJavaError(env, "Unexpected Linux native player failure.");
    }
    if constexpr (!std::is_void_v<decltype(action())>) return {};
}

struct Player;
std::string audioTracksJson(Player &player);
std::string subtitleTracksJson(Player &player);
std::string videoTracksJson(Player &player);

// Xlib's error handler belongs to the process, not to an mpv handle. Reserve
// the whole lifetime, including partial initialization and final restoration.
std::atomic<bool> x11PlayerLifetimeReserved{false};

struct Player {
    mpv_handle *mpv = nullptr;
    JavaVM *vm = nullptr;
    jobject sink = nullptr;
    jmethodID onEvent = nullptr;
    std::mutex operations;
    std::mutex callbacks;
    std::unique_ptr<LinuxControlsOverlay> controls;
    std::unique_ptr<LinuxSeekThumbnails> thumbnails;
    std::thread events;
    std::atomic<bool> stopping{false};
    std::atomic<bool> fileReady{false};
    std::atomic<bool> ended{false};
    int64_t lastShaderSize = -1;
    std::string lastShaderMedia;
    int lastVideoHdr = -1; // Event thread only; never carried to a replacement player.
    LinuxVideoSizeReport videoSizeReport; // Event thread only, likewise.
    LinuxHttpRecoveryEvents recoveryEvents; // Protected by operations, including seek issuance.
    LinuxAudioPassthroughRecovery audioRecovery; // Same operation lock and per-player lifetime.
    // Live TV cache-only seeks (live_cache_seek.h), under operations: whether the last one left
    // hr-seek-demuxer-offset set, and its target, so held arrow steps chain off a seek in flight.
    bool liveHrSeekOffsetApplied = false;
    double liveSeekTargetSeconds = -1.0;
    std::chrono::steady_clock::time_point liveSeekIssuedAt{};
    LinuxSubtitleAdds subtitleAdds; // Addon subtitles added off the caller's thread, under operations.
    bool ownsX11Lifetime = false;
    // Experimental native-Wayland HDR session: no Canvas, X11 lifetime, HUD or thumbnails.
    bool hdrSession = false;
    // What the player opened, for subtitle sync's own audio decodes (speech_spot.h). Set once in
    // create, before the player is published.
    std::string speechSource;
    std::vector<std::string> speechHeaders;
    std::string speechSeparateAudio; // The separate audio stream, if any (linuxSpeechSpotInput).

    // Auto crop (resize mode 3, auto_crop.h): checked from the event loop and switched by
    // setResizeMode, both under operations like every other mpv call here.
    struct AutoCropHost final : LinuxAutoCrop::Host {
        Player &player;
        explicit AutoCropHost(Player &owner) : player(owner) {}
        bool pausedOrSeeking() override { return player.flag("pause") || player.flag("seeking"); }
        double displayWidth() override { return player.number("video-params/dw"); }
        double displayHeight() override { return player.number("video-params/dh"); }
        int64_t frameWidth() override { return player.integer("video-out-params/w", player.integer("video-params/w")); }
        int64_t frameHeight() override { return player.integer("video-out-params/h", player.integer("video-params/h")); }
        bool capture(AutoCropBars &bars, int64_t &grabWidth, int64_t &grabHeight) override {
            return captureAutoCropBars(player.mpv, bars, grabWidth, grabHeight);
        }
        void setCrop(const std::string &crop) override { setAutoCropVideoCrop(player.mpv, crop); }
        bool titleTaggedImax() override {
            for (const char *name : {"filename", "media-title"}) {
                std::string value = player.stringProperty(name);
                std::transform(value.begin(), value.end(), value.begin(),
                    [](unsigned char c) { return (char)std::tolower(c); });
                if (value.find("imax") != std::string::npos) return true;
            }
            return false;
        }
        void log(const std::string &line) override { std::fprintf(stderr, "Linux %s\n", line.c_str()); }
    };
    AutoCropHost autoCropHost{*this};
    LinuxAutoCrop autoCrop{autoCropHost};

    void reserveX11Lifetime() {
        bool expected = false;
        if (!x11PlayerLifetimeReserved.compare_exchange_strong(expected, true))
            throw std::runtime_error("Linux X11 player is already active; dispose it before creating another player.");
        ownsX11Lifetime = true;
    }

    ~Player() { close(); }

    void close() {
        stopping = true;
        std::unique_ptr<LinuxControlsOverlay> closingControls;
        {
            std::lock_guard<std::mutex> lock(operations);
            closingControls = std::move(controls);
            if (mpv && events.joinable()) mpv_wakeup(mpv);
        }
        // GTK callbacks can query mpv; do not hold operations while waiting for GTK.
        // Disconnect/destroy the native child before releasing mpv or the JNI sink.
        if (closingControls) closingControls->close();
        // No operations/JAWT/GTK lock while waiting for the windowless decoder.
        if (thumbnails) thumbnails->close();
        // Never hold the operation lock across callbacks/join.
        if (events.joinable()) events.join();
        std::lock_guard<std::mutex> lock(operations);
        if (mpv) {
            JNIEnv *env = nullptr;
            int status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
            bool attached = status == JNI_EDETACHED;
            if (status != JNI_OK && (!attached ||
                vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK))
                throw std::runtime_error("Unable to acquire JVM environment for Linux player shutdown.");
            if (hdrSession) {
                // Wayland-only session: no X11 error handler, AWT lock or GTK overlay to
                // serialise with. Its event thread is already joined.
                mpv_terminate_destroy(mpv);
            } else {
                // The overlay is closed and the event thread joined: neither can
                // require the EDT or acquire AWT's lock while we wait for GTK/mpv.
                LinuxAwtX11Lifecycle awtLock(env);
                auto terminate = [this] {
                    mpv_terminate_destroy(mpv);
                    LinuxAwtX11Lifecycle::restoreToolkitHandler();
                };
                LinuxControlsOverlay::finishPlayerShutdown(terminate);
            }
            if (attached) vm->DetachCurrentThread();
            mpv = nullptr;
        }
        std::lock_guard<std::mutex> callbackLock(callbacks);
        if (sink) {
            JNIEnv *env = nullptr;
            int status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
            bool attached = status == JNI_EDETACHED;
            if (status == JNI_OK || (attached &&
                vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK)) {
                env->DeleteGlobalRef(sink);
                if (attached) vm->DetachCurrentThread();
            }
            sink = nullptr;
        }
        // A rejected second create owns nothing. Repeated close must not release
        // a reservation subsequently acquired by another player.
        if (ownsX11Lifetime) {
            ownsX11Lifetime = false;
            x11PlayerLifetimeReserved = false;
        }
    }

    void emit(JNIEnv *env, const std::string &name, double value = 0.0) {
        std::lock_guard<std::mutex> lock(callbacks);
        if (stopping || !sink) return;
        jstring eventName = env->NewStringUTF(name.c_str());
        if (eventName) {
            env->CallVoidMethod(sink, onEvent, eventName, value);
            env->DeleteLocalRef(eventName);
        }
        if (env->ExceptionCheck()) {
            // A callback failure must not escape a native thread or terminate the process.
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    void controlsMessage(const std::string &name, double value) {
        if (stopping) return;
        // The HUD's subtitle menu sends mpv's sid; Kotlin has no handler for this
        // native action. Audio's logical index is instead forwarded to Kotlin.
        if (name == "selectSubtitleTrack") {
            std::lock_guard<std::mutex> lock(operations);
            if (mpv && !stopping && value >= -1 && value <= INT32_MAX)
                property("sid", value < 0 ? "no" : std::to_string(std::llround(value)));
            return;
        }
        JNIEnv *env = nullptr;
        int status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        bool attached = status == JNI_EDETACHED;
        if (status == JNI_OK || (attached &&
            vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK)) {
            emit(env, name, value); // Existing Kotlin sink queues onto Swing.
            if (attached) vm->DetachCurrentThread();
        }
    }

    std::string controlsSnapshot() {
        std::lock_guard<std::mutex> lock(operations);
        if (!mpv || stopping) return {};
        double position = number("time-pos");
        double buffered = number("demuxer-cache-time", -1.0);
        if (buffered < position) buffered = position + number("demuxer-cache-duration");
        bool paused = flag("pause");
        bool loading = !fileReady || flag("seeking") || flag("paused-for-cache") ||
            (flag("core-idle") && !paused && !ended && !flag("eof-reached"));
        // Kotlin omits progress-only structural updates. Match the existing HUD
        // runtime contract using real mpv state.
        std::ostringstream json;
        json.imbue(std::locale::classic());
        json << "{\"duration\":" << number("duration") << ",\"position\":" << position
             << ",\"buffered\":" << buffered << ",\"paused\":" << (paused ? "true" : "false")
             << ",\"loading\":" << (loading ? "true" : "false")
             << ",\"audioTracks\":" << audioTracksJson(*this)
             << ",\"subtitleTracks\":" << subtitleTracksJson(*this) << "}";
        return json.str();
    }

    void pump(std::promise<bool> &attached) {
        JNIEnv *env = nullptr;
        bool ready = vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK;
        attached.set_value(ready);
        if (!ready) return;
        while (!stopping) {
            mpv_event *event = mpv_wait_event(mpv, 0.2);
            if (stopping) break;
            LinuxHttpRecoveryEvents::Result translated;
            bool audioRecovered = false;
            {
                std::lock_guard<std::mutex> lock(operations);
                if (stopping) break;
                translated = recoveryEvents.accept(*event, LinuxHttpRecoveryEvents::Clock::now());
                if (!translated.failure && !recoveryEvents.failed()) {
                    const auto audio = audioRecovery.accept(mpv, *event);
                    audioRecovered = audio.recovered;
                    if (audio.error < 0) {
                        // Use the existing terminal-error translator and callbacks. Do not
                        // swallow a rejected chain reset or keep retrying a broken PCM output.
                        mpv_event_end_file end{};
                        end.reason = MPV_END_FILE_REASON_ERROR;
                        end.error = audio.error;
                        mpv_event failure{};
                        failure.event_id = MPV_EVENT_END_FILE;
                        failure.data = &end;
                        translated = recoveryEvents.accept(failure, LinuxHttpRecoveryEvents::Clock::now());
                        if (translated.failure) translated.failure->stop = true;
                    }
                }
                if (translated.started) {
                    fileReady = ended = false;
                }
                if (translated.loaded) { fileReady = true; ended = false; }
                if (translated.restarted) ended = false;
                if (event->event_id == MPV_EVENT_COMMAND_REPLY && LinuxSubtitleAdds::owns(event->reply_userdata)) {
                    std::string url;
                    const bool wanted = subtitleAdds.current(event->reply_userdata);
                    const auto repair = subtitleAdds.finished(event->reply_userdata, event->error, url);
                    if (wanted && event->error < 0)
                        std::fprintf(stderr, "Linux sub-add: %s\n", mpv_error_string(event->error));
                    if (repair && !applyLinuxSubtitleRepair(mpv, *repair))
                        std::fprintf(stderr, "Linux sub-add: an abandoned subtitle could not be taken out\n");
                }
                // The demuxer offset of a cache-only live seek is per seek: it ends with the restart.
                if (event->event_id == MPV_EVENT_PLAYBACK_RESTART && liveHrSeekOffsetApplied) {
                    mpv_set_property_string(mpv, "hr-seek-demuxer-offset", "0");
                    liveHrSeekOffsetApplied = false;
                }
                const auto now = LinuxAutoCrop::Clock::now();
                if (translated.loaded) autoCrop.fileLoaded(now);
                if (translated.restarted) autoCrop.playbackRestart(now);
                autoCrop.tick(now);
                if (translated.eof) ended = true;
                if (translated.failure) {
                    fileReady = ended = false;
                    // A failed range seek can leave time-pos/eof-reached at duration.
                    // Stop the dead demuxer before the existing Kotlin recovery takes over.
                    if (translated.failure->stop) {
                        const char *stop[] = {"stop", nullptr};
                        mpv_command(mpv, stop);
                    }
                }
            }
            // No operation lock across JNI callbacks. The sink preserves this order on the EDT.
            translated.deliverFailure([&](const std::string &name, double value) { emit(env, name, value); });
            if (audioRecovered) emit(env, "audioPassthroughFallback", 1.0);
            switch (event->event_id) {
                case MPV_EVENT_PROPERTY_CHANGE: {
                    auto *property = static_cast<mpv_event_property *>(event->data);
                    if (event->reply_userdata == 1 && property)
                        reportShaderMedia(env, property->format == MPV_FORMAT_NODE
                            ? static_cast<mpv_node *>(property->data) : nullptr);
                    if (hdrSession && property) reportHdrSessionProperty(env, event->reply_userdata, *property);
                    break;
                }
                case MPV_EVENT_CLIENT_MESSAGE:
                    // Only the HDR session installs bindings; the embedded player ignores messages.
                    if (hdrSession) {
                        if (const auto code = linuxHdrKeyCode(*event)) emit(env, "linuxHdrKey", *code);
                    }
                    break;
                case MPV_EVENT_LOG_MESSAGE:
                    if (hdrSession && linuxHdrVoFailureLog(*event)) emit(env, "linuxHdrVoFailed");
                    break;
                case MPV_EVENT_FILE_LOADED:
                    if (translated.loaded) {
                        reportVideoParams(env);
                        emit(env, "fileLoaded");
                    }
                    break;
                case MPV_EVENT_VIDEO_RECONFIG:
                    reportVideoParams(env);
                    break;
                case MPV_EVENT_PLAYBACK_RESTART:
                    if (translated.restarted) emit(env, "playbackRestart");
                    break;
                case MPV_EVENT_SHUTDOWN:
                    // An HDR window can be closed by its compositor; report it before stopping.
                    if (hdrSession) emit(env, "linuxHdrSessionEnded");
                    stopping = true;
                    break;
                default:
                    break;
            }
        }
        vm->DetachCurrentThread();
    }

    void reportShaderMedia(JNIEnv *env, const mpv_node *params) {
        // Read the delivered snapshot, never synchronously query width/height or mix observations.
        auto field = [params](const char *name) -> const mpv_node * {
            if (!params || params->format != MPV_FORMAT_NODE_MAP || !params->u.list) return nullptr;
            for (int i = 0; i < params->u.list->num; ++i)
                if (std::string(params->u.list->keys[i]) == name) return &params->u.list->values[i];
            return nullptr;
        };
        auto number = [&](const char *name) -> int64_t {
            const auto *value = field(name);
            return value && value->format == MPV_FORMAT_INT64 ? value->u.int64 : 0;
        };
        auto text = [&](const char *name) -> std::string {
            const auto *value = field(name);
            return value && value->format == MPV_FORMAT_STRING && value->u.string ? value->u.string : "";
        };
        const int64_t width = number("w"), height = number("h");
        const int64_t packed = linuxPackedVideoSize(width, height);
        if (videoSizeReport.changed(packed)) emit(env, "videoSourceSize", static_cast<double>(packed));
        const std::string media = "linuxShaderMedia/" + text("gamma") + "/" +
            text("primaries") + "/" + text("colormatrix");
        if (packed != lastShaderSize || media != lastShaderMedia) {
            lastShaderSize = packed;
            lastShaderMedia = media;
            emit(env, media, static_cast<double>(packed));
        }
    }

    void reportHdrSessionProperty(JNIEnv *env, uint64_t reply, const mpv_event_property &property) {
        if (reply == kLinuxHdrTargetParamsReply) {
            emit(env, linuxHdrTargetEvent(property.format == MPV_FORMAT_NODE
                ? static_cast<const mpv_node *>(property.data) : nullptr));
        } else if (reply == kLinuxHdrVoConfiguredReply) {
            const bool configured = property.format == MPV_FORMAT_FLAG && property.data &&
                *static_cast<const int *>(property.data) != 0;
            emit(env, "linuxHdrVoConfigured", configured ? 1.0 : 0.0);
        }
    }

    void reportVideoParams(JNIEnv *env) {
        // Only classify the decoded source so SDR colour presets cannot grade HDR by mistake.
        // This does not configure HDR output, tone mapping, filters or hardware decoding.
        const std::string gamma = stringProperty("video-params/gamma");
        const std::string primaries = stringProperty("video-params/primaries");
        if (gamma.empty() || primaries.empty()) return; // Keep unknown sources neutral.
        const int hdr = gamma == "st2084" || gamma == "hlg" || gamma == "arib-std-b67" ||
            primaries == "bt.2020" || primaries == "bt.2020-cl";
        if (hdr != lastVideoHdr) {
            lastVideoHdr = hdr;
            emit(env, "videoParams", hdr);
        }
    }

    double number(const char *name, double unavailable = 0.0) {
        double value = unavailable;
        if (mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &value) < 0 || !std::isfinite(value)) return unavailable;
        return value;
    }

    bool flag(const char *name, bool unavailable = false) {
        int value = unavailable;
        if (mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &value) < 0) return unavailable;
        return value != 0;
    }

    int64_t integer(const char *name, int64_t unavailable = 0) {
        int64_t value = unavailable;
        if (mpv_get_property(mpv, name, MPV_FORMAT_INT64, &value) < 0) return unavailable;
        return value;
    }

    std::string stringProperty(const char *name) {
        char *value = mpv_get_property_string(mpv, name);
        if (!value) return {};
        std::string result(value);
        mpv_free(value);
        return result;
    }

    void property(const char *name, const std::string &value) {
        int result = mpv_set_property_string(mpv, name, value.c_str());
        // Shared preferences include optional/init-only properties. Diagnose failures rather
        // than aborting playback; required commands below use checkMpv instead.
        if (result < 0) std::fprintf(stderr, "Linux mpv property %s: %s\n", name, mpv_error_string(result));
    }
};

// Opaque IDs avoid use-after-free when a snapshot/command races asynchronous disposal.
std::mutex playersLock;
std::unordered_map<jlong, std::shared_ptr<Player>> players;
std::atomic<jlong> nextHandle{1};

std::shared_ptr<Player> playerFromHandle(jlong handle) {
    std::lock_guard<std::mutex> lock(playersLock);
    auto found = players.find(handle);
    return found == players.end() ? nullptr : found->second;
}

template<class F> auto withPlayer(JNIEnv *env, jlong handle, F action) -> decltype(action(std::declval<Player &>())) {
    return guarded(env, [&]() {
        std::shared_ptr<Player> player;
        {
            std::lock_guard<std::mutex> lock(playersLock);
            auto found = players.find(handle);
            if (found == players.end()) throw std::runtime_error("Linux native player handle is not active.");
            player = found->second;
        }
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->mpv || player->stopping) throw std::runtime_error("Linux native player is disposed.");
        return action(*player);
    });
}

void option(mpv_handle *mpv, const char *name, const std::string &value) {
    checkMpv(mpv_set_option_string(mpv, name, value.c_str()), name);
}

void applyExtraMpvOptions(mpv_handle *mpv, const std::vector<std::string> &options,
                         const std::string &source) {
    const std::string modePrefix = "@nuvio-config-mode=";
    std::string mode = "off";
    for (const auto &entry : options) {
        if (entry.rfind(modePrefix, 0) == 0) mode = entry.substr(modePrefix.size());
    }
    // Shared Kotlin omits custom entries in Off and application preferences in Full.
    // Keep Linux's safe-auto fallback in Full, but allow an explicit hwdec override.
    std::unordered_set<std::string> configured;
    if (mode != "full") configured.insert("hwdec");
    auto apply = [&](const std::string &name, const std::string &value, bool owned) {
        const int result = mpv_set_option_string(mpv, name.c_str(), value.c_str());
        if (result < 0) {
            std::fprintf(stderr, "Linux mpv option %s: %s\n", name.c_str(), mpv_error_string(result));
        } else if (owned) {
            configured.insert(name);
        }
    };
    if (mode != "full") {
        // Ordinary gpu-next/streaming defaults from the Windows desktop baseline. None are
        // integration requirements: shared settings and Replace/Full remain authoritative.
        const std::pair<const char *, const char *> defaults[] = {
            {"scale", "spline36"}, {"cscale", "lanczos"}, {"dscale", "mitchell"},
            {"scale-antiring", "0.7"}, {"cscale-antiring", "0.7"}, {"dscale-antiring", "0.7"},
            {"sigmoid-upscaling", "yes"}, {"correct-downscaling", "yes"}, {"linear-downscaling", "no"},
            {"dither", "fruit"}, {"dither-depth", "10"}, {"temporal-dither", "yes"},
            {"temporal-dither-period", "1"}, {"deband", "yes"}, {"deband-iterations", "2"},
            {"deband-threshold", "35"}, {"deband-range", "16"}, {"deband-grain", "0"},
            // The application owns these four runtime properties; begin neutral until decoded
            // colour metadata permits the shared SDR preset values to be applied.
            {"contrast", "0"}, {"brightness", "0"}, {"saturation", "0"}, {"gamma", "0"},
            // Ordinary runtime-profile ownership, omitted in Full and overridable in Replace.
            {"glsl-shaders", ""},
            {"cache", "yes"}, {"cache-pause", "yes"}, {"cache-pause-initial", "yes"},
            {"cache-pause-wait", "0.25"}, {"cache-secs", "600"}, {"demuxer-readahead-secs", "180"},
            {"demuxer-max-bytes", "1GiB"}, {"demuxer-max-back-bytes", "128MiB"},
            {"stream-buffer-size", "1MiB"}, {"hr-seek", "no"}, {"volume-max", "200"},
        };
        for (const auto &entry : defaults) apply(entry.first, entry.second, true);
        if (source.find("://") != std::string::npos && source.rfind("file://", 0) != 0) {
            apply("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5", true);
        }
    }
    for (std::string entry : options) {
        const bool userOption = entry.rfind("@nuvio-user:", 0) == 0;
        if (userOption) entry.erase(0, 12);
        if (entry.rfind("@nuvio-", 0) == 0) continue;
        auto equals = entry.find('=');
        if (equals == std::string::npos || equals == 0) continue;
        const std::string name = entry.substr(0, equals);
        std::string value = entry.substr(equals + 1);
        if (userOption && value.size() >= 2 && value.front() == value.back() &&
            (value.front() == '"' || value.front() == '\'')) {
            // As on Windows, the custom box accepts quoted mpv.conf values, while
            // mpv_set_option_string does not remove their surrounding quotes.
            value = value.substr(1, value.size() - 2);
        }
        if (userOption && (mode == "add" || mode == "full") && configured.count(name)) continue;
        // Failed options reserve nothing; repeated custom values remain last-wins.
        apply(name, value, !userOption);
    }
}

void headerOptions(mpv_handle *mpv, std::vector<std::string> &headers) {
    if (headers.empty()) return;
    // Use the native string array so commas in individual header values stay in one header.
    std::vector<mpv_node> entries(headers.size());
    for (size_t i = 0; i < headers.size(); ++i) {
        entries[i].format = MPV_FORMAT_STRING;
        entries[i].u.string = headers[i].data();
    }
    mpv_node_list list{};
    list.num = static_cast<int>(entries.size());
    list.values = entries.data();
    mpv_node node{};
    node.format = MPV_FORMAT_NODE_ARRAY;
    node.u.list = &list;
    checkMpv(mpv_set_option(mpv, "http-header-fields", MPV_FORMAT_NODE, &node), "http-header-fields");
}

void seek(Player &player, jlong ms, const char *mode) {
    const bool relative = std::string_view(mode).substr(0, 8) == "relative";
    checkMpv(player.recoveryEvents.commandSeek(player.mpv, ms, mode,
        relative ? player.number("time-pos", -1.0) : 0.0, LinuxHttpRecoveryEvents::Clock::now()), "seek");
    player.ended = false;
}

// Live TV: seeks kept inside the demuxer cache's seekable ranges (live_cache_seek.h). Exact, as on
// Windows: a keyframe seek resolves up to a GOP earlier, and inside the cache the decode is free.
LinuxLiveCacheState liveCacheState(Player &player) {
    struct CacheState {
        mpv_node node{};
        ~CacheState() { mpv_free_node_contents(&node); }
    } state;
    if (mpv_get_property(player.mpv, "demuxer-cache-state", MPV_FORMAT_NODE, &state.node) < 0) return {};
    return linuxLiveCacheState(state.node);
}

void issueCachedSeek(Player &player, const LinuxLiveCacheState &cache, double target) {
    const double offset = linuxCachedSeekDemuxerOffset(cache, target);
    if (offset > 0.0 || player.liveHrSeekOffsetApplied) {
        player.property("hr-seek-demuxer-offset", std::to_string(offset));
        player.liveHrSeekOffsetApplied = offset > 0.0;
    }
    seek(player, std::llround(target * 1000.0), "absolute+exact");
    player.liveSeekTargetSeconds = target;
    player.liveSeekIssuedAt = std::chrono::steady_clock::now();
}

void seekWithinCache(Player &player, double target, const char *reason) {
    const auto cache = liveCacheState(player);
    const double clamped = linuxClampIntoCachedRanges(cache, target);
    std::fprintf(stderr, "Linux %s requested=%.2f target=%.2f cacheEnd=%.2f ranges=%d%s\n", reason, target,
        clamped, cache.cacheEnd, (int)cache.ranges.size(), clamped < 0.0 ? " skipped" : "");
    if (clamped >= 0.0) issueCachedSeek(player, cache, clamped);
}

std::string chaptersJson(Player &player) {
    // One coherent property snapshot; no mpv-owned pointers escape its lifetime.
    struct ChapterList {
        mpv_node node{};
        ~ChapterList() { mpv_free_node_contents(&node); }
    } chapters;
    if (mpv_get_property(player.mpv, "chapter-list", MPV_FORMAT_NODE, &chapters.node) < 0 ||
        chapters.node.format != MPV_FORMAT_NODE_ARRAY || !chapters.node.u.list) return "[]";
    const mpv_node_list &list = *chapters.node.u.list;
    if (list.num > 0 && !list.values) return "[]";
    std::ostringstream json;
    json.imbue(std::locale::classic());
    json << std::setprecision(std::numeric_limits<double>::max_digits10) << '[';
    bool first = true;
    for (int index = 0; index < list.num; ++index) {
        const mpv_node &entry = list.values[index];
        if (entry.format != MPV_FORMAT_NODE_MAP || !entry.u.list) continue;
        const mpv_node_list &fields = *entry.u.list;
        if (!fields.keys || !fields.values) continue;
        double time = -1;
        std::string title;
        for (int field = 0; field < fields.num; ++field) {
            if (!fields.keys[field]) continue;
            const std::string key = fields.keys[field];
            const mpv_node &value = fields.values[field];
            if (key == "time") {
                if (value.format == MPV_FORMAT_DOUBLE) time = value.u.double_;
                else if (value.format == MPV_FORMAT_INT64) time = static_cast<double>(value.u.int64);
            } else if (key == "title" && value.format == MPV_FORMAT_STRING && value.u.string) {
                title = trimmed(value.u.string);
            }
        }
        if (!std::isfinite(time) || time < 0) continue;
        if (!first) json << ',';
        first = false;
        json << "{\"startTime\":" << time << ",\"title\":" << jsonString(title) << '}';
    }
    json << ']';
    return json.str();
}

void removeExternalSubtitleTracks(Player &player) {
    const int64_t count = player.integer("track-list/count");
    // Removing a track mutates track-list, so visit indices in reverse order.
    for (int64_t index = count - 1; index >= 0; --index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) != "sub" ||
            !player.flag((prefix + "external").c_str())) continue;
        int64_t trackId = player.integer((prefix + "id").c_str(), -1);
        if (trackId < 0) continue;
        std::string id = std::to_string(trackId);
        const char *command[] = {"sub-remove", id.c_str(), nullptr};
        checkMpv(mpv_command(player.mpv, command), "sub-remove");
    }
}

std::string audioChannelLayoutName(const std::string &channels, int64_t count) {
    std::string normalized = trimmed(channels);
    std::string lower = caseFolded(normalized);
    if (!normalized.empty() && lower != "unknown") {
        if (lower == "mono") return "Mono";
        if (lower == "stereo") return "Stereo";
        return normalized;
    }
    switch (count) {
        case 1: return "Mono";
        case 2: return "Stereo";
        case 6: return "5.1";
        case 8: return "7.1";
        default: return count > 0 ? std::to_string(count) + "ch" : "";
    }
}

// Raw facts for the player badges and the stream info panel, in the fields the Windows bridge
// reports (upstream 774265f13): empty / 0 when mpv does not know them. demux-bitrate is the
// container's figure and is often 0.
std::string trackMediaFactsJson(Player &player, const std::string &prefix, bool video) {
    double fps = player.number((prefix + "demux-fps").c_str(), 0.0);
    if (!std::isfinite(fps) || fps < 0.0) fps = 0.0;
    std::ostringstream json;
    json.imbue(std::locale::classic());
    json << ",\"codec\":" << jsonString(trimmed(player.stringProperty((prefix + "codec").c_str())))
         << ",\"profile\":" << jsonString(trimmed(player.stringProperty((prefix + "codec-profile").c_str())))
         << ",\"channelCount\":" << player.integer((prefix + "demux-channel-count").c_str())
         << ",\"dvProfile\":" << (video ? player.integer((prefix + "dolby-vision-profile").c_str()) : 0)
         << ",\"width\":" << player.integer((prefix + "demux-w").c_str())
         << ",\"height\":" << player.integer((prefix + "demux-h").c_str())
         << ",\"fps\":" << fps
         << ",\"bitrate\":" << player.integer((prefix + "demux-bitrate").c_str())
         << ",\"sampleRate\":" << player.integer((prefix + "demux-samplerate").c_str());
    return json.str();
}

std::string audioTracksJson(Player &player) {
    const int64_t count = player.integer("track-list/count");
    int64_t logicalIndex = 0;
    std::string json = "[";
    for (int64_t index = 0; index < count; ++index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) != "audio") continue;
        int64_t trackId = player.integer((prefix + "id").c_str(), logicalIndex + 1);
        std::string title = trimmed(player.stringProperty((prefix + "title").c_str()));
        std::string language = trimmed(player.stringProperty((prefix + "lang").c_str()));
        // The existing codec display helper also covers audio codecs.
        std::string codec = subtitleCodecDisplayName(player.stringProperty((prefix + "codec").c_str()));
        if (codec.empty()) codec = subtitleCodecDisplayName(player.stringProperty((prefix + "decoder-desc").c_str()));
        std::string channels = audioChannelLayoutName(player.stringProperty((prefix + "demux-channels").c_str()),
            player.integer((prefix + "demux-channel-count").c_str()));
        // Match Windows formatTrackTitle: title, language, then numbered fallback;
        // append channel layout and codec only when absent from the base label.
        std::string label = !title.empty() ? title : !language.empty() ? language :
            "Track " + std::to_string(logicalIndex + 1);
        std::string details;
        for (const auto &detail : {channels, codec}) {
            if (detail.empty() || caseFolded(label).find(caseFolded(detail)) != std::string::npos) continue;
            if (!details.empty()) details += ", ";
            details += detail;
        }
        if (!details.empty()) label += " (" + details + ")";
        if (logicalIndex > 0) json += ',';
        json += "{\"index\":" + std::to_string(logicalIndex) +
            ",\"id\":" + jsonString(std::to_string(trackId)) +
            ",\"label\":" + jsonString(label) + ",\"language\":" + jsonString(language) +
            ",\"selected\":" + (player.flag((prefix + "selected").c_str()) ? "true" : "false") +
            ",\"forced\":" + (player.flag((prefix + "forced").c_str()) ? "true" : "false") +
            trackMediaFactsJson(player, prefix, false) + '}';
        ++logicalIndex;
    }
    return json + ']';
}

std::string subtitleTracksJson(Player &player) {
    const int64_t count = player.integer("track-list/count");
    const int64_t primarySubtitleId = player.integer("current-tracks/sub/id", player.integer("sid", -1));
    int64_t logicalIndex = 0;
    std::string json = "[";
    for (int64_t index = 0; index < count; ++index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) != "sub") continue;
        int64_t trackId = player.integer((prefix + "id").c_str(), logicalIndex + 1);
        std::string title = trimmed(player.stringProperty((prefix + "title").c_str()));
        std::string language = trimmed(player.stringProperty((prefix + "lang").c_str()));
        std::string codec = subtitleCodecDisplayName(player.stringProperty((prefix + "codec").c_str()));
        if (codec.empty()) codec = subtitleCodecDisplayName(player.stringProperty((prefix + "decoder-desc").c_str()));
        bool selected = player.flag((prefix + "selected").c_str()) ||
            (primarySubtitleId >= 0 && trackId == primarySubtitleId);
        bool forced = player.flag((prefix + "forced").c_str());
        std::string label = !title.empty() ? title : !language.empty() ? language :
            "Subtitle " + std::to_string(logicalIndex + 1);
        if (!codec.empty() && caseFolded(label).find(caseFolded(codec)) == std::string::npos) {
            label += " (" + codec + ")";
        }
        if (logicalIndex > 0) json += ',';
        json += "{\"index\":" + std::to_string(logicalIndex) +
            ",\"id\":" + jsonString(std::to_string(trackId)) +
            ",\"label\":" + jsonString(label) + ",\"language\":" + jsonString(language) +
            ",\"selected\":" + (selected ? "true" : "false") +
            ",\"forced\":" + (forced ? "true" : "false") +
            trackMediaFactsJson(player, prefix, false) + '}';
        ++logicalIndex;
    }
    return json + ']';
}

// The video tracks for the player badges and the stream info panel (upstream 774265f13), read
// synchronously like the audio and subtitle lists. Labels follow the audio list's rules.
std::string videoTracksJson(Player &player) {
    const int64_t count = player.integer("track-list/count");
    int64_t logicalIndex = 0;
    std::string json = "[";
    for (int64_t index = 0; index < count; ++index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) != "video") continue;
        int64_t trackId = player.integer((prefix + "id").c_str(), logicalIndex + 1);
        std::string title = trimmed(player.stringProperty((prefix + "title").c_str()));
        std::string language = trimmed(player.stringProperty((prefix + "lang").c_str()));
        std::string label = !title.empty() ? title : !language.empty() ? language :
            "Video " + std::to_string(logicalIndex + 1);
        if (logicalIndex > 0) json += ',';
        json += "{\"index\":" + std::to_string(logicalIndex) +
            ",\"id\":" + jsonString(std::to_string(trackId)) +
            ",\"label\":" + jsonString(label) + ",\"language\":" + jsonString(language) +
            ",\"selected\":" + (player.flag((prefix + "selected").c_str()) ? "true" : "false") +
            ",\"forced\":" + (player.flag((prefix + "forced").c_str()) ? "true" : "false") +
            trackMediaFactsJson(player, prefix, true) + '}';
        ++logicalIndex;
    }
    return json + ']';
}

jlong milliseconds(double seconds) {
    return static_cast<jlong>(std::llround(std::max(0.0, seconds) * 1000.0));
}
}

#define JNI_METHOD(type, name) extern "C" JNIEXPORT type JNICALL \
    Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_##name

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}

namespace {
// Shared by the embedded X11 player and the experimental HDR session. Each step keeps
// the embedded player's original option values and ordering.
void retainEventSink(JNIEnv *env, Player &player, jobject eventSink) {
    player.sink = env->NewGlobalRef(eventSink);
    if (!player.sink) throw std::runtime_error("Unable to retain native player event sink.");
    // The interface, not the sink's runtime class: native-image metadata is generated from
    // FindClass literals (composeApp/native-image-linux/README.md).
    jclass sinkClass = env->FindClass("com/nuvio/app/features/player/desktop/NativePlayerEventSink");
    if (!sinkClass) throw std::runtime_error("Unable to read native player event sink class.");
    player.onEvent = env->GetMethodID(sinkClass, "onPlayerEvent", "(Ljava/lang/String;D)V");
    env->DeleteLocalRef(sinkClass);
    if (!player.onEvent) throw std::runtime_error("Missing onPlayerEvent(String, Double).");
}

void applyPlaybackOptions(mpv_handle *mpv, const std::vector<std::string> &extraOptions,
                          const std::string &source) {
    // Vendor-neutral default; Replace/Full and unmarked options can override it.
    option(mpv, "hwdec", "auto");
    applyExtraMpvOptions(mpv, extraOptions, source);
}

void applyStartOptions(mpv_handle *mpv, bool playWhenReady, jlong initialPositionMs,
                       double initialProgressFraction) {
    option(mpv, "pause", playWhenReady ? "no" : "yes");
    if (initialPositionMs > 0) {
        option(mpv, "start", std::to_string(static_cast<double>(initialPositionMs) / 1000.0));
    } else if (std::isfinite(initialProgressFraction) && initialProgressFraction > 0.0) {
        option(mpv, "start", std::to_string(std::min(initialProgressFraction, 1.0) * 100.0) + "%");
    }
}

template<class AfterInitialize>
void initializeAndLoad(Player &player, const std::string &source, AfterInitialize afterInitialize) {
    checkMpv(mpv_initialize(player.mpv), "mpv_initialize");
    // SPDIF recovery needs mpv's exact verbose PCM-fallback marker. Other
    // messages are discarded, never forwarded or written to a new log sink.
    const char *recoveryLogLevel = player.stringProperty("audio-spdif").empty() ? "warn" : "v";
    checkMpv(mpv_request_log_messages(player.mpv, recoveryLogLevel), "request recovery log events");
    checkMpv(mpv_observe_property(player.mpv, 1, "video-params", MPV_FORMAT_NODE), "observe video-params");
    afterInitialize();
    const char *load[] = {"loadfile", source.c_str(), nullptr};
    checkMpv(mpv_command(player.mpv, load), "loadfile");
    std::promise<bool> attached;
    auto ready = attached.get_future();
    player.events = std::thread([raw = &player, attached = std::move(attached)]() mutable {
        raw->pump(attached);
    });
    if (!ready.get()) throw std::runtime_error("Unable to attach Linux mpv event thread to JVM.");
}

jlong publishPlayer(const std::shared_ptr<Player> &player) {
    jlong handle = nextHandle++;
    std::lock_guard<std::mutex> lock(playersLock);
    players.emplace(handle, player);
    return handle;
}
}

JNI_METHOD(jlong, create)(
    JNIEnv *env, jobject, jlong hostViewPtr, jstring sourceUrl, jstring sourceAudioUrl,
    jobjectArray headerLines, jboolean playWhenReady, jlong initialPositionMs,
    jdouble initialProgressFraction, jstring controlsPageUrl,
    jboolean /* nvidiaRtxSuperResolutionEnabled */, jboolean /* nvidiaRtxHdrEnabled */,
    jboolean /* isAnimeContent */, jstring /* animeSvpFilter */, jobjectArray extraMpvOptions,
    jobject eventSink
) {
    return guarded(env, [&]() -> jlong {
        if (hostViewPtr <= 0 || hostViewPtr > 0xffffffffLL) {
            throw std::runtime_error("Linux playback requires a nonzero X11 Canvas drawable.");
        }
        std::string source = text(env, sourceUrl);
        if (source.empty()) throw std::runtime_error("Linux playback source URL is empty.");
        if (!eventSink) throw std::runtime_error("Native player event sink is required.");
        auto player = std::make_shared<Player>();
        player->reserveX11Lifetime();
        if (env->GetJavaVM(&player->vm) != JNI_OK) throw std::runtime_error("GetJavaVM failed.");
        LinuxAwtX11Lifecycle::prepare(env);
        retainEventSink(env, *player, eventSink);
        std::setlocale(LC_NUMERIC, "C");
        player->mpv = mpv_create();
        if (!player->mpv) throw std::runtime_error("mpv_create failed.");

        applyPlaybackOptions(player->mpv, strings(env, extraMpvOptions), source);
        // These embedding/lifecycle boundaries must win over saved desktop options.
        for (const auto &entry : linuxX11EmbeddingOptions(hostViewPtr)) option(player->mpv, entry.first.c_str(), entry.second);
        applyStartOptions(player->mpv, playWhenReady == JNI_TRUE, initialPositionMs, initialProgressFraction);
        auto headers = strings(env, headerLines);
        headerOptions(player->mpv, headers);
        std::string audio = text(env, sourceAudioUrl);
        checkMpv(setLinuxSeparateAudio(player->mpv, audio), "audio-files");
        player->speechSource = source;
        player->speechHeaders = headers;
        player->speechSeparateAudio = audio;
        initializeAndLoad(*player, source, [] {});
        std::weak_ptr<Player> weak = player;
        player->controls = LinuxControlsOverlay::create(hostViewPtr, text(env, controlsPageUrl),
            [weak](const std::string &name, double value) {
                if (auto current = weak.lock()) current->controlsMessage(name, value);
            },
            [weak] {
                if (auto current = weak.lock()) return current->controlsSnapshot();
                return std::string{};
            });
        if (player->controls) {
            player->thumbnails = std::make_unique<LinuxSeekThumbnails>(source, std::move(headers),
                [weak](int64_t position, const std::string &url,
                       LinuxSeekThumbnails::Generation generation, uint64_t request) {
                    if (auto current = weak.lock()) {
                        std::lock_guard<std::mutex> lock(current->operations);
                        if (!current->stopping && current->controls)
                            current->controls->deliverSeekThumbnail(position, url, std::move(generation), request);
                    }
                });
        }
        return publishPlayer(player);
    });
}

JNI_METHOD(void, dispose)(JNIEnv *env, jobject, jlong handle) {
    guarded(env, [&]() {
        std::shared_ptr<Player> player;
        {
            std::lock_guard<std::mutex> lock(playersLock);
            auto found = players.find(handle);
            if (found == players.end()) return; // Includes dispose(0) and repeated disposal.
            player = found->second;
            players.erase(found);
        }
        player->close();
    });
}

JNI_METHOD(void, setPaused)(JNIEnv *env, jobject, jlong handle, jboolean paused) {
    withPlayer(env, handle, [&](Player &p) {
        int value = paused == JNI_TRUE;
        checkMpv(mpv_set_property(p.mpv, "pause", MPV_FORMAT_FLAG, &value), "pause");
    });
}
JNI_METHOD(void, seekTo)(JNIEnv *env, jobject, jlong handle, jlong position) {
    withPlayer(env, handle, [&](Player &p) { seek(p, std::max<jlong>(0, position), "absolute+exact"); });
}
JNI_METHOD(void, seekBy)(JNIEnv *env, jobject, jlong handle, jlong offset) {
    withPlayer(env, handle, [&](Player &p) { seek(p, offset, "relative+exact"); });
}
JNI_METHOD(void, seekToCachedLiveEdge)(JNIEnv *env, jobject, jlong handle, jlong cushionMs) {
    withPlayer(env, handle, [&](Player &p) {
        const double timePos = p.number("time-pos", -1.0);
        const auto cache = liveCacheState(p);
        const double target = linuxCachedLiveEdgeTarget(cache, timePos, cushionMs);
        std::fprintf(stderr, "Linux go live pos=%.2f cacheEnd=%.2f ranges=%d target=%.2f%s\n", timePos,
            cache.cacheEnd, (int)cache.ranges.size(), target, target < 0.0 ? " skipped" : "");
        if (target >= 0.0) issueCachedSeek(p, cache, target);
    });
}
JNI_METHOD(void, seekWithinCacheTo)(JNIEnv *env, jobject, jlong handle, jlong positionMs) {
    withPlayer(env, handle, [&](Player &p) { seekWithinCache(p, (double)positionMs / 1000.0, "live seek"); });
}
JNI_METHOD(void, seekWithinCacheBy)(JNIEnv *env, jobject, jlong handle, jlong offsetMs) {
    withPlayer(env, handle, [&](Player &p) {
        // Relative steps chain off a seek still in flight, so a held arrow keeps stepping instead
        // of re-reading a time-pos that has not caught up yet.
        const bool pendingFresh = p.liveSeekTargetSeconds >= 0.0 && (p.flag("seeking") ||
            std::chrono::steady_clock::now() - p.liveSeekIssuedAt < std::chrono::milliseconds(800));
        const double base = pendingFresh ? p.liveSeekTargetSeconds : p.number("time-pos", -1.0);
        if (base < 0.0) return;
        seekWithinCache(p, base + (double)offsetMs / 1000.0, "live step");
    });
}
JNI_METHOD(void, requestSeekThumbnail)(JNIEnv *env, jobject, jlong handle, jlong positionMs) {
    guarded(env, [&] {
        std::shared_ptr<Player> player;
        {
            std::lock_guard<std::mutex> lock(playersLock);
            auto found = players.find(handle);
            if (found == players.end()) return; // Upstream ignores requests after disposal.
            player = found->second;
        }
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->stopping && player->thumbnails) player->thumbnails->request(positionMs);
    });
}
JNI_METHOD(void, setMpvProperty)(JNIEnv *env, jobject, jlong handle, jstring name, jstring value) {
    withPlayer(env, handle, [&](Player &p) { p.property(text(env, name).c_str(), text(env, value)); });
}
JNI_METHOD(void, setSpeed)(JNIEnv *env, jobject, jlong handle, jfloat speed) {
    withPlayer(env, handle, [&](Player &p) {
        double value = speed;
        checkMpv(mpv_set_property(p.mpv, "speed", MPV_FORMAT_DOUBLE, &value), "speed");
    });
}
JNI_METHOD(void, setVolume)(JNIEnv *env, jobject, jlong handle, jfloat volume) {
    withPlayer(env, handle, [&](Player &p) {
        double value = volume;
        checkMpv(mpv_set_property(p.mpv, "volume", MPV_FORMAT_DOUBLE, &value), "volume");
    });
}
JNI_METHOD(void, setMute)(JNIEnv *env, jobject, jlong handle, jboolean muted) {
    withPlayer(env, handle, [&](Player &p) {
        int value = muted == JNI_TRUE;
        checkMpv(mpv_set_property(p.mpv, "mute", MPV_FORMAT_FLAG, &value), "mute");
    });
}
JNI_METHOD(jlong, positionMs)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) { return milliseconds(p.number("time-pos")); });
}
JNI_METHOD(jlong, durationMs)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) { return milliseconds(p.number("duration")); });
}
JNI_METHOD(jlong, bufferedPositionMs)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) {
        double position = p.number("time-pos");
        double end = p.number("demuxer-cache-time", -1.0);
        return milliseconds(end >= position ? end : position + p.number("demuxer-cache-duration"));
    });
}
JNI_METHOD(jboolean, isPaused)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean { return p.flag("pause"); });
}
JNI_METHOD(jboolean, isEnded)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean {
        return p.recoveryEvents.ended(p.ended || p.flag("eof-reached"));
    });
}
JNI_METHOD(jboolean, isLoading)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean {
        return !p.fileReady || p.flag("seeking") || p.flag("paused-for-cache") ||
            (p.flag("core-idle") && !p.flag("pause") && !p.ended && !p.flag("eof-reached"));
    });
}
JNI_METHOD(jfloat, speed)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jfloat { return p.number("speed", 1.0); });
}
JNI_METHOD(jfloat, volume)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jfloat { return p.number("volume"); });
}
JNI_METHOD(jboolean, isMuted)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean { return p.flag("mute"); });
}
JNI_METHOD(void, setResizeMode)(JNIEnv *env, jobject, jlong handle, jint mode) {
    withPlayer(env, handle, [&](Player &p) {
        p.property("keepaspect", mode == 1 ? "no" : "yes");
        // Auto crop (3) fills like Zoom once video-crop has trimmed the bars (auto_crop.h).
        p.property("panscan", mode == 2 || mode == 3 ? "1" : "0");
        p.autoCrop.setEnabled(mode == 3, LinuxAutoCrop::Clock::now());
    });
}

JNI_METHOD(jdouble, speechSourceBytesPerSecond)(JNIEnv *env, jobject, jlong handle) {
    return guarded(env, [&]() -> jdouble {
        auto player = playerFromHandle(handle);
        if (!player) return -1.0;
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->mpv || player->stopping) return -1.0;
        return linuxSpeechSourceBytesPerSecond(player->speechSource, player->integer("file-size", -1),
            player->number("duration"));
    });
}

JNI_METHOD(jint, decodeSpeechSpot)(JNIEnv *env, jobject, jlong handle, jdouble startSeconds,
                                   jdouble durationSeconds, jstring outputPath) {
    return guarded(env, [&]() -> jint {
        auto player = playerFromHandle(handle);
        if (!player) return -1;
        LinuxSpeechSpotInput input;
        std::vector<std::string> headers;
        {
            // Copy what the decode needs, then let it run (up to 90 s) without blocking the player.
            std::lock_guard<std::mutex> lock(player->operations);
            if (!player->mpv || player->stopping) return -1;
            input = linuxSpeechSpotInput(player->speechSource, player->speechSeparateAudio,
                player->stringProperty("aid"));
            headers = player->speechHeaders;
        }
        return linuxDecodeSpeechSpot(input.url, headers, input.aid, startSeconds, durationSeconds,
            text(env, outputPath), [player] { return player->stopping.load(); });
    });
}

JNI_METHOD(jstring, audioTracksJson)(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("[]");
    return withPlayer(env, handle, [&](Player &p) {
        std::string json = audioTracksJson(p);
        return env->NewStringUTF(json.c_str());
    });
}
JNI_METHOD(jstring, videoTracksJson)(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("[]");
    return withPlayer(env, handle, [&](Player &p) {
        std::string json = videoTracksJson(p);
        return env->NewStringUTF(json.c_str());
    });
}
JNI_METHOD(jstring, chaptersJson)(JNIEnv *env, jobject, jlong handle) {
    return guarded(env, [&] {
        auto player = playerFromHandle(handle);
        if (!player) return env->NewStringUTF("[]");
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->mpv || player->stopping) return env->NewStringUTF("[]");
        return env->NewStringUTF(chaptersJson(*player).c_str());
    });
}
JNI_METHOD(void, toggleStatsOverlay)(JNIEnv *env, jobject, jlong handle) {
    guarded(env, [&] {
        auto player = playerFromHandle(handle);
        if (!player) return;
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->mpv || player->stopping) return;
        // The matching stats.lua is embedded in libmpv and loaded once per player.
        // Match upstream's void JNI contract: diagnose failure without stopping playback.
        const char *command[] = {"script-binding", "stats/display-stats-toggle", nullptr};
        int result = mpv_command(player->mpv, command);
        if (result < 0)
            std::fprintf(stderr, "Linux mpv stats overlay: %s\n", mpv_error_string(result));
    });
}
JNI_METHOD(jboolean, selectAudioTrack)(JNIEnv *env, jobject, jlong handle, jint trackId) {
    if (handle == 0) return JNI_FALSE;
    return withPlayer(env, handle, [&](Player &p) -> jboolean {
        // A stale/nonexistent aid can disable audio. Resolve only live audio IDs
        // under the same operation lock used for the actual selection.
        const int64_t count = p.integer("track-list/count");
        for (int64_t index = 0; index < count; ++index) {
            std::string prefix = "track-list/" + std::to_string(index) + "/";
            if (p.stringProperty((prefix + "type").c_str()) != "audio" ||
                p.integer((prefix + "id").c_str(), -1) != trackId || trackId < 0) continue;
            int64_t id = trackId;
            return mpv_set_property(p.mpv, "aid", MPV_FORMAT_INT64, &id) >= 0;
        }
        return JNI_FALSE;
    });
}

// Subtitle configuration is replayed by the shared controller immediately after create/fileLoaded.
JNI_METHOD(jstring, subtitleTracksJson)(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("[]");
    return withPlayer(env, handle, [&](Player &p) {
        std::string json = subtitleTracksJson(p);
        return env->NewStringUTF(json.c_str());
    });
}
JNI_METHOD(void, setSubtitleDelayMs)(JNIEnv *env, jobject, jlong handle, jint delay) {
    withPlayer(env, handle, [&](Player &p) { p.property("sub-delay", std::to_string(delay / 1000.0)); });
}
JNI_METHOD(jboolean, selectSubtitleTrack)(JNIEnv *env, jobject, jlong handle, jint trackId) {
    // The shared startup policy can disable subtitles even without track enumeration.
    return withPlayer(env, handle, [&](Player &p) -> jboolean {
        std::string value = trackId < 0 ? "no" : std::to_string(trackId);
        p.subtitleAdds.abandon(p.mpv, value);
        return mpv_set_property_string(p.mpv, "sid", value.c_str()) >= 0;
    });
}
JNI_METHOD(void, addSubtitleUrl)(JNIEnv *env, jobject, jlong handle, jstring url) {
    withPlayer(env, handle, [&](Player &p) {
        std::string subtitle = text(env, url);
        if (subtitle.empty()) return;
        // Asynchronous: the download no longer holds this thread or the operation lock (subtitle_add.h).
        checkMpv(p.subtitleAdds.add(p.mpv, subtitle), "sub-add");
    });
}
JNI_METHOD(void, clearExternalSubtitles)(JNIEnv *env, jobject, jlong handle) {
    withPlayer(env, handle, [&](Player &p) {
        p.subtitleAdds.abandon(p.mpv, "no");
        removeExternalSubtitleTracks(p);
        checkMpv(mpv_set_property_string(p.mpv, "sid", "no"), "sid");
    });
}
JNI_METHOD(void, clearExternalSubtitlesAndSelect)(JNIEnv *env, jobject, jlong handle, jint trackId) {
    withPlayer(env, handle, [&](Player &p) {
        std::string value = trackId >= 0 ? std::to_string(trackId) : "no";
        p.subtitleAdds.abandon(p.mpv, value);
        removeExternalSubtitleTracks(p);
        checkMpv(mpv_set_property_string(p.mpv, "sid", value.c_str()), "sid");
    });
}
JNI_METHOD(void, setSubtitleAssStyleMode)(JNIEnv *env, jobject, jlong handle, jstring mode, jdouble scale) {
    withPlayer(env, handle, [&](Player &p) {
        p.property("sub-ass-override", text(env, mode));
        p.property("sub-scale", std::to_string(scale));
    });
}
JNI_METHOD(void, applySubtitleStyle)(
    JNIEnv *env, jobject, jlong handle, jstring color, jstring background, jstring outline,
    jfloat outlineSize, jboolean bold, jfloat fontSize, jint position, jstring font
) {
    withPlayer(env, handle, [&](Player &p) {
        std::string back = text(env, background);
        p.property("sub-color", text(env, color));
        p.property("sub-back-color", back);
        p.property("sub-outline-color", text(env, outline));
        p.property("sub-border-style", back.rfind("#00", 0) == 0 ? "outline-and-shadow" : "opaque-box");
        p.property("sub-outline-size", std::to_string(outlineSize));
        p.property("sub-bold", bold ? "yes" : "no");
        p.property("sub-font-size", std::to_string(fontSize));
        p.property("sub-pos", std::to_string(position));
        p.property("sub-font", text(env, font));
    });
}

JNI_METHOD(void, updateControls)(JNIEnv *env, jobject, jlong handle, jstring json) {
    withPlayer(env, handle, [&](Player &p) { if (p.controls) p.controls->updateControls(text(env, json)); });
}
JNI_METHOD(void, runJavaScript)(JNIEnv *env, jobject, jlong handle, jstring script) {
    withPlayer(env, handle, [&](Player &p) { if (p.controls) p.controls->runJavaScript(text(env, script)); });
}
// Upstream's Windows keep-awake request, called on every desktop while a video plays. Linux keeps
// the display awake from the same keepScreenAwake flag through LinuxPlaybackInhibition (portal or
// logind), so this succeeds without doing anything rather than failing to link on each play/pause.
JNI_METHOD(jboolean, setWindowsDisplaySleepInhibited)(JNIEnv *, jobject, jboolean) { return JNI_TRUE; }
// controls.js emits cursorVisibility, which the shared Kotlin controller routes here.
JNI_METHOD(void, setCursorHidden)(JNIEnv *env, jobject, jlong handle, jboolean hidden) {
    withPlayer(env, handle, [&](Player &p) { if (p.controls) p.controls->setCursorHidden(hidden); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxPlayerControlsBridge_setWindowFocused(
    JNIEnv *env, jobject, jlong handle, jboolean focused) {
    withPlayer(env, handle, [&](Player &p) {
        if (p.controls) p.controls->setWindowFocused(focused == JNI_TRUE);
    });
}
// Media session integration is still absent.
JNI_METHOD(void, setMediaSessionMetadata)(JNIEnv *, jobject, jlong, jstring, jstring, jstring) {}

// Linux-only capability query; shared platforms retain their existing JNI contract.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxMprisNative_seekable(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean { return p.flag("seekable"); });
}
// X11's mpv VO owns expose/resize/redraw, unlike the Windows D3D redraw workaround.
JNI_METHOD(void, forceVideoRedraw)(JNIEnv *, jobject, jlong) {}

// Experimental native-Wayland HDR session (Linux only). The session is an ordinary
// Player in the shared handle table, so every existing NativePlayerBridge per-handle
// method (position, seek, tracks, subtitles, volume, dispose) applies to it unchanged.
#define LINUX_HDR_METHOD(type, name) extern "C" JNIEXPORT type JNICALL \
    Java_com_nuvio_app_features_player_desktop_LinuxHdrSessionNative_##name

LINUX_HDR_METHOD(jboolean, libmpvSupportsWayland)(JNIEnv *env, jobject) {
    return guarded(env, [] {
        static const bool supported = linuxLibmpvSupportsWaylandVk();
        return supported ? JNI_TRUE : JNI_FALSE;
    });
}

LINUX_HDR_METHOD(jlong, create)(
    JNIEnv *env, jobject, jstring sourceUrl, jstring sourceAudioUrl, jobjectArray headerLines,
    jlong initialPositionMs, jobjectArray extraMpvOptions, jobjectArray keyBindings,
    jstring vulkanDevice, jstring oscScriptPath, jstring appId, jobject eventSink
) {
    return guarded(env, [&]() -> jlong {
        std::string source = text(env, sourceUrl);
        if (source.empty()) throw std::runtime_error("Linux HDR session source URL is empty.");
        if (!eventSink) throw std::runtime_error("Native player event sink is required.");
        // Never let a session without the compiled context fall back to another VO.
        if (!linuxLibmpvSupportsWaylandVk())
            throw std::runtime_error("The loaded libmpv has no Wayland Vulkan context.");
        auto player = std::make_shared<Player>();
        player->hdrSession = true;
        if (env->GetJavaVM(&player->vm) != JNI_OK) throw std::runtime_error("GetJavaVM failed.");
        retainEventSink(env, *player, eventSink);
        std::setlocale(LC_NUMERIC, "C");
        player->mpv = mpv_create();
        if (!player->mpv) throw std::runtime_error("mpv_create failed.");

        applyPlaybackOptions(player->mpv, strings(env, extraMpvOptions), source);
        LinuxHdrSessionConfig config;
        config.vulkanDevice = text(env, vulkanDevice);
        config.appId = text(env, appId);
        for (const auto &entry : linuxHdrSessionOptions(config)) option(player->mpv, entry.first.c_str(), entry.second);
        // Always start paused: the application unpauses only after the session has
        // configured its window and confirmed an HDR target, so audio never overlaps
        // the paused embedded player it replaces.
        applyStartOptions(player->mpv, false, initialPositionMs, 0.0);
        const std::string osc = text(env, oscScriptPath);
        if (!osc.empty()) {
            // Cosmetic only: a libmpv without Lua or an unreadable script keeps playing.
            const int result = mpv_set_option_string(player->mpv, "scripts", osc.c_str());
            if (result < 0) std::fprintf(stderr, "Linux HDR OSC script: %s\n", mpv_error_string(result));
        }
        auto headers = strings(env, headerLines);
        headerOptions(player->mpv, headers);
        std::string audio = text(env, sourceAudioUrl);
        checkMpv(setLinuxSeparateAudio(player->mpv, audio), "audio-files");
        player->speechSource = source;
        player->speechHeaders = headers;
        player->speechSeparateAudio = audio;
        const auto bindings = strings(env, keyBindings);
        initializeAndLoad(*player, source, [&] {
            installLinuxHdrKeyBindings(player->mpv, bindings);
            checkMpv(mpv_observe_property(player->mpv, kLinuxHdrTargetParamsReply, "video-target-params",
                MPV_FORMAT_NODE), "observe video-target-params");
            checkMpv(mpv_observe_property(player->mpv, kLinuxHdrVoConfiguredReply, "vo-configured",
                MPV_FORMAT_FLAG), "observe vo-configured");
        });
        return publishPlayer(player);
    });
}

LINUX_HDR_METHOD(void, scriptMessage)(JNIEnv *env, jobject, jlong handle, jobjectArray arguments) {
    withPlayer(env, handle, [&](Player &p) {
        if (!p.hdrSession) return;
        auto values = strings(env, arguments);
        std::vector<const char *> command{"script-message"};
        for (const auto &value : values) command.push_back(value.c_str());
        command.push_back(nullptr);
        mpv_command(p.mpv, command.data());
    });
}

LINUX_HDR_METHOD(void, showText)(JNIEnv *env, jobject, jlong handle, jstring message, jint durationMs) {
    withPlayer(env, handle, [&](Player &p) {
        if (!p.hdrSession) return;
        // "$>" disables property expansion: a stream title must never be expanded.
        const std::string value = "$>" + text(env, message);
        const std::string duration = std::to_string(std::clamp<jint>(durationMs, 0, 10000));
        const char *command[] = {"show-text", value.c_str(), duration.c_str(), nullptr};
        mpv_command(p.mpv, command);
    });
}

LINUX_HDR_METHOD(jboolean, isFocused)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean {
        // Unknown focus is not focus: a pad must not drive a session the user left.
        return p.hdrSession && p.flag("focused", false);
    });
}

namespace {
struct LinuxExternalSubtitle {
    int64_t sourceId = -1;
    std::string filename;
    std::string title;
};

std::vector<LinuxExternalSubtitle> externalSubtitles(Player &player) {
    std::vector<LinuxExternalSubtitle> result;
    const int64_t count = player.integer("track-list/count");
    for (int64_t index = 0; index < count; ++index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) != "sub" ||
            !player.flag((prefix + "external").c_str())) continue;
        LinuxExternalSubtitle subtitle;
        subtitle.sourceId = player.integer((prefix + "id").c_str(), -1);
        subtitle.filename = player.stringProperty((prefix + "external-filename").c_str());
        subtitle.title = player.stringProperty((prefix + "title").c_str());
        if (subtitle.sourceId >= 0 && !subtitle.filename.empty()) result.push_back(std::move(subtitle));
    }
    return result;
}

int64_t externalSubtitleId(Player &player, const std::string &filename) {
    const int64_t count = player.integer("track-list/count");
    for (int64_t index = count - 1; index >= 0; --index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) == "sub" &&
            player.flag((prefix + "external").c_str()) &&
            player.stringProperty((prefix + "external-filename").c_str()) == filename)
            return player.integer((prefix + "id").c_str(), -1);
    }
    return -1;
}
}

// Copies the live user-visible playback choices from the embedded player to the HDR
// session (or back): selected tracks, external subtitles, delays, speed, volume. Both
// players play the same source, so internal track ids are identical.
LINUX_HDR_METHOD(void, transferPlaybackState)(JNIEnv *env, jobject, jlong fromHandle, jlong toHandle) {
    guarded(env, [&] {
        auto from = playerFromHandle(fromHandle);
        auto to = playerFromHandle(toHandle);
        if (!from || !to || from == to) throw std::runtime_error("Linux HDR state transfer needs two live players.");
        std::vector<LinuxExternalSubtitle> subtitles;
        std::string aid, sid, secondary;
        std::vector<std::pair<const char *, std::string>> values;
        {
            // Read one snapshot, then release: a slow subtitle download below must never
            // block UI queries that still target the source player.
            std::lock_guard<std::mutex> lock(from->operations);
            if (!from->mpv || from->stopping) throw std::runtime_error("Linux native player is disposed.");
            subtitles = externalSubtitles(*from);
            aid = from->stringProperty("aid");
            sid = from->stringProperty("sid");
            secondary = from->stringProperty("secondary-sid");
            for (const char *name : {"sub-delay", "audio-delay", "speed", "volume", "mute", "sub-visibility"})
                values.emplace_back(name, from->stringProperty(name));
        }
        std::lock_guard<std::mutex> lock(to->operations);
        if (!to->mpv || to->stopping) throw std::runtime_error("Linux native player is disposed.");
        for (const auto &[name, value] : values) if (!value.empty()) to->property(name, value);
        if (!aid.empty()) to->property("aid", aid);
        auto mapped = [&](const std::string &id) -> std::string {
            for (const auto &subtitle : subtitles) {
                if (std::to_string(subtitle.sourceId) != id) continue;
                const int64_t target = externalSubtitleId(*to, subtitle.filename);
                return target >= 0 ? std::to_string(target) : "no";
            }
            return id;
        };
        for (const auto &subtitle : subtitles) {
            if (externalSubtitleId(*to, subtitle.filename) >= 0) continue;
            const char *command[] = {"sub-add", subtitle.filename.c_str(), "auto", subtitle.title.c_str(), nullptr};
            const int result = mpv_command(to->mpv, command);
            if (result < 0) std::fprintf(stderr, "Linux HDR subtitle transfer: %s\n", mpv_error_string(result));
        }
        if (!sid.empty()) to->property("sid", mapped(sid));
        if (!secondary.empty()) to->property("secondary-sid", mapped(secondary));
    });
}
