// Headless fixtures for the experimental Linux HDR session: option contracts, key
// binding parsing, event translation, libmpv capability detection, real-libmpv key
// routing and the bundled OSC script. Null video/audio output only; no display,
// Wayland connection, GTK or JNI.

#include "hdr_wayland_session.h"
#include "player_session_options.h"

#include <mpv/client.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>
#include <string>
#include <vector>

namespace {
int failures = 0;

void check(bool condition, const std::string &message) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message.c_str());
        ++failures;
    }
}

std::string value(const LinuxMpvOptionList &options, const std::string &name) {
    for (const auto &entry : options) if (entry.first == name) return entry.second;
    return "<absent>";
}

void x11ContractIsUnchanged() {
    // Literal copy of the embedded player's accepted boundary before the HDR work.
    const LinuxMpvOptionList legacy{
        {"config", "no"}, {"terminal", "no"}, {"osc", "no"}, {"input-default-bindings", "no"},
        {"input-vo-keyboard", "no"}, {"input-cursor", "no"}, {"window-dragging", "no"},
        {"stop-screensaver", "no"}, {"keep-open", "yes"}, {"idle", "yes"}, {"vo", "gpu-next"},
        {"gpu-api", "vulkan"}, {"gpu-context", "x11vk"}, {"wid", "123456"},
    };
    check(linuxX11EmbeddingOptions(123456) == legacy, "X11 embedding options changed");
}

void hdrContract() {
    LinuxHdrSessionConfig config;
    auto options = linuxHdrSessionOptions(config);
    check(value(options, "gpu-context") == "waylandvk", "HDR session must use waylandvk");
    check(value(options, "vo") == "gpu-next", "HDR session must use gpu-next");
    check(value(options, "gpu-api") == "vulkan", "HDR session must use Vulkan");
    check(value(options, "wid") == "<absent>", "HDR session must not embed into a window id");
    check(value(options, "fullscreen") == "yes", "HDR session is fullscreen");
    check(value(options, "input-default-bindings") == "no", "default bindings stay disabled");
    check(value(options, "input-vo-keyboard") == "yes", "HDR window must receive keys");
    check(value(options, "config") == "no" && value(options, "osc") == "no", "config/osc stay disabled");
    check(value(options, "vulkan-device") == "<absent>", "no device unless selected");
    check(value(options, "target-colorspace-hint") == "<absent>", "target mapping is app-owned");
    check(value(options, "target-trc") == "<absent>", "PQ is never forced");
    config.vulkanDevice = "Example GPU";
    config.appId = "com.nuvio.media.desktop";
    options = linuxHdrSessionOptions(config);
    check(value(options, "vulkan-device") == "Example GPU", "selected device is passed through");
    check(value(options, "wayland-app-id") == "com.nuvio.media.desktop", "app id is passed through");
    std::set<std::string> names;
    for (const auto &entry : options) check(names.insert(entry.first).second, "duplicate HDR option " + entry.first);
}

void keyBindingParsing() {
    auto parsed = parseLinuxHdrKeyBinding("SPACE=32");
    check(parsed && parsed->first == "SPACE" && parsed->second == 32, "SPACE=32");
    parsed = parseLinuxHdrKeyBinding("==61");
    check(parsed && parsed->first == "=" && parsed->second == 61, "'=' key splits at the last '='");
    parsed = parseLinuxHdrKeyBinding("CLOSE_WIN=-100");
    check(parsed && parsed->second == -100, "negative application codes");
    for (const char *invalid : {"", "=", "SPACE=", "=32", "A B=1", "x=1a", "q\"=1", "x=1234567", "#=1", "\\=1"})
        check(!parseLinuxHdrKeyBinding(invalid), std::string("rejects ") + invalid);
    check(linuxHdrKeyBindingCommand(-100) == "script-message nuvio-hdr-key -100", "binding command");
}

void eventTranslation() {
    mpv_node values[2];
    char gamma[] = "pq";
    char primaries[] = "bt.2020";
    values[0].format = MPV_FORMAT_STRING;
    values[0].u.string = gamma;
    values[1].format = MPV_FORMAT_STRING;
    values[1].u.string = primaries;
    char gammaKey[] = "gamma";
    char primariesKey[] = "primaries";
    char *keys[] = {gammaKey, primariesKey};
    mpv_node_list list{};
    list.num = 2;
    list.values = values;
    list.keys = keys;
    mpv_node node{};
    node.format = MPV_FORMAT_NODE_MAP;
    node.u.list = &list;
    check(linuxHdrTargetEvent(&node) == "linuxHdrTarget/pq/bt.2020", "target event");
    check(linuxHdrTargetEvent(nullptr) == "linuxHdrTarget//", "missing target params stay unknown");

    mpv_event_log_message log{};
    log.prefix = "vo/gpu-next/wayland";
    log.level = "error";
    log.text = "Can't connect to display\n";
    log.log_level = MPV_LOG_LEVEL_ERROR;
    mpv_event event{};
    event.event_id = MPV_EVENT_LOG_MESSAGE;
    event.data = &log;
    check(linuxHdrVoFailureLog(event), "VO error is a start failure");
    log.log_level = MPV_LOG_LEVEL_WARN;
    check(!linuxHdrVoFailureLog(event), "VO warning is not a failure");
    log.log_level = MPV_LOG_LEVEL_ERROR;
    log.prefix = "ffmpeg/demuxer";
    check(!linuxHdrVoFailureLog(event), "demuxer error is not a VO failure");
    log.prefix = "cplayer";
    log.text = "Error opening/initializing the selected video_out (--vo) device.\n";
    check(linuxHdrVoFailureLog(event), "cplayer video_out error");
}

void capabilityDetection(const char *expectation) {
    // A bogus context proves mpv validates entries at option time; waylandvk is then
    // accepted exactly when the loaded libmpv has the compiled Wayland context.
    mpv_handle *mpv = mpv_create();
    check(mpv != nullptr, "mpv_create");
    if (mpv) {
        check(mpv_set_option_string(mpv, "gpu-context", "nuvio-not-a-context") < 0, "unknown context rejected");
        mpv_terminate_destroy(mpv);
    }
    const bool supported = linuxLibmpvSupportsWaylandVk();
    std::printf("libmpv waylandvk support: %s\n", supported ? "yes" : "no");
    if (expectation) check(supported == (std::strcmp(expectation, "yes") == 0), "waylandvk detection matches build");
}

mpv_handle *headlessPlayer(const char *script) {
    mpv_handle *mpv = mpv_create();
    if (!mpv) return nullptr;
    for (const auto &entry : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"terminal", "no"}, {"osc", "no"}, {"input-default-bindings", "no"},
             {"vo", "null"}, {"ao", "null"}, {"idle", "yes"}, {"load-scripts", "no"}}) {
        if (mpv_set_option_string(mpv, entry.first, entry.second) < 0) {
            mpv_terminate_destroy(mpv);
            return nullptr;
        }
    }
    if (script) {
        mpv_set_option_string(mpv, "scripts", script);
        // Scripts start during mpv_initialize: subscribe first, or a fast script's
        // load message (or load error) can be emitted before anyone listens.
        if (mpv_request_log_messages(mpv, "info") < 0) {
            mpv_terminate_destroy(mpv);
            return nullptr;
        }
    }
    if (mpv_initialize(mpv) < 0) {
        mpv_terminate_destroy(mpv);
        return nullptr;
    }
    return mpv;
}

void realKeyRouting() {
    mpv_handle *mpv = headlessPlayer(nullptr);
    check(mpv != nullptr, "headless libmpv");
    if (!mpv) return;
    const int installed = installLinuxHdrKeyBindings(mpv, {"SPACE=32", "CLOSE_WIN=-100", "a=65", "bad entry=1"});
    check(installed == 3, "three valid bindings installed");
    for (const char *key : {"SPACE", "a", "CLOSE_WIN", "Q"}) {
        const char *press[] = {"keypress", key, nullptr};
        mpv_command(mpv, press);
    }
    std::vector<int> codes;
    bool shutdown = false;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (codes.size() < 3 && std::chrono::steady_clock::now() < deadline) {
        mpv_event *event = mpv_wait_event(mpv, 0.1);
        if (event->event_id == MPV_EVENT_SHUTDOWN) { shutdown = true; break; }
        if (const auto code = linuxHdrKeyCode(*event)) codes.push_back(*code);
    }
    // Unbound keys produce nothing, and the bound CLOSE_WIN no longer quits.
    const auto settle = std::chrono::steady_clock::now() + std::chrono::milliseconds(300);
    while (std::chrono::steady_clock::now() < settle) {
        mpv_event *event = mpv_wait_event(mpv, 0.05);
        if (event->event_id == MPV_EVENT_SHUTDOWN) shutdown = true;
        if (const auto code = linuxHdrKeyCode(*event)) codes.push_back(*code);
    }
    check(codes == std::vector<int>({32, 65, -100}), "each bound key is forwarded exactly once");
    check(!shutdown, "CLOSE_WIN is application-owned");
    mpv_terminate_destroy(mpv);
}

void oscScript(const char *script) {
    mpv_handle *mpv = headlessPlayer(script);
    check(mpv != nullptr, "headless libmpv with OSC and log subscription");
    if (!mpv) return;
    bool ready = false;
    int errors = 0;
    auto pump = [&](std::chrono::milliseconds duration) {
        const auto until = std::chrono::steady_clock::now() + duration;
        while (std::chrono::steady_clock::now() < until) {
            mpv_event *event = mpv_wait_event(mpv, 0.05);
            if (event->event_id != MPV_EVENT_LOG_MESSAGE) continue;
            const auto *log = static_cast<mpv_event_log_message *>(event->data);
            if (std::strstr(log->text, "Nuvio HDR OSC ready")) ready = true;
            if (log->log_level <= MPV_LOG_LEVEL_ERROR) {
                ++errors;
                std::fprintf(stderr, "mpv error [%s] %s", log->prefix, log->text);
            }
        }
    };
    pump(std::chrono::milliseconds(1500));
    const char *key[] = {"script-message", kLinuxHdrKeyMessage, "32", nullptr};
    mpv_command(mpv, key);
    const char *hint[] = {"script-message", "nuvio-hdr-osc-hint", "Esc  Back", nullptr};
    mpv_command(mpv, hint);
    pump(std::chrono::milliseconds(1200));
    check(ready, "OSC script loaded");
    check(errors == 0, "OSC script ran without errors");
    mpv_terminate_destroy(mpv);
}
} // namespace

int main(int argc, char **argv) {
    const char *expectation = nullptr;
    for (int i = 1; i < argc; ++i)
        if (std::strncmp(argv[i], "--expect-wayland=", 17) == 0) expectation = argv[i] + 17;
    if (std::getenv("DISPLAY") || std::getenv("WAYLAND_DISPLAY")) {
        std::fprintf(stderr, "Unset DISPLAY and WAYLAND_DISPLAY: this fixture is headless.\n");
        return 2;
    }
    x11ContractIsUnchanged();
    hdrContract();
    keyBindingParsing();
    eventTranslation();
    capabilityDetection(expectation);
    realKeyRouting();
#ifdef NUVIO_HDR_OSC_SCRIPT
    oscScript(NUVIO_HDR_OSC_SCRIPT);
#endif
    if (failures) {
        std::fprintf(stderr, "%d HDR session check(s) failed\n", failures);
        return 1;
    }
    std::printf("linux_hdr_session_test: all checks passed\n");
    return 0;
}
