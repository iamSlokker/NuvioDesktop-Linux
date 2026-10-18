// Isolated-display probe: run a host script in the production overlay + controls.js, then require a
// JavaScript condition to hold. Usage: linux_script_probe <controls.html URL> <script> <condition>
#include "controls_overlay.h"
#include <X11/Xlib.h>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <string>

static bool envIs(const char *name, const char *value) {
    const char *actual = std::getenv(name);
    return actual && std::string(actual) == value;
}

int main(int argc, char **argv) {
    if (argc != 4 || !envIs("NUVIO_ISOLATED_VIDEO_PROBE", "1") || !envIs("NUVIO_RUN_LIVE_DISPLAY_TESTS", "1")) return 2;
    XInitThreads();
    Display *display = XOpenDisplay(nullptr);
    if (!display) return 3;
    const Window host = XCreateSimpleWindow(display, DefaultRootWindow(display), 0, 0, 640, 360, 0, 0, 0);
    XMapWindow(display, host);
    XSync(display, False);
    std::mutex mutex;
    std::condition_variable changed;
    int verdict = -1;
    auto overlay = LinuxControlsOverlay::create(host, argv[1], [&](const std::string &name, double value) {
        if (name != "scriptProbe") return;
        std::lock_guard<std::mutex> lock(mutex);
        verdict = static_cast<int>(value);
        changed.notify_all();
    }, [] { return ""; });
    // Queued until controlsReady, like the Linux host's own scripts.
    overlay->runJavaScript(argv[2]);
    overlay->runJavaScript(std::string("window.webkit.messageHandlers.player.postMessage({type:'scriptProbe',value:(") +
        argv[3] + ") ? 1 : 0});");
    std::unique_lock<std::mutex> lock(mutex);
    if (!changed.wait_for(lock, std::chrono::seconds(30), [&] { return verdict >= 0; })) {
        std::puts("SCRIPT_PROBE_TIMEOUT");
        return 4;
    }
    std::puts(verdict == 1 ? "SCRIPT_PROBE_PASS" : "SCRIPT_PROBE_FAIL");
    lock.unlock();
    overlay.reset();
    XCloseDisplay(display);
    return verdict == 1 ? 0 : 1;
}
