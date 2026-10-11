// Explicit isolated-Xvfb probe only. Compiled with the production overlay source.
#include "controls_overlay.h"
#include <X11/Xlib.h>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>

int main(int argc, char** argv)
{
    const char* isolated = std::getenv("NUVIO_WEBKIT_ISOLATED_PROBE");
    const char* optIn = std::getenv("NUVIO_RUN_LIVE_DISPLAY_TESTS");
    if (argc != 2 || !isolated || std::string(isolated) != "1"
        || !optIn || std::string(optIn) != "1" || !std::getenv("DISPLAY")) {
        std::fprintf(stderr, "Only run through the isolated WebKit probe harness\n");
        return 2;
    }
    XInitThreads();
    auto* display = XOpenDisplay(nullptr);
    if (!display) return 3;
    auto window = XCreateSimpleWindow(display, DefaultRootWindow(display), 0, 0, 640, 360, 0, 0, 0);
    XMapWindow(display, window);
    XSync(display, False);
    std::mutex mutex;
    std::condition_variable changed;
    bool received = false;
    auto overlay = LinuxControlsOverlay::create(window, argv[1], [&](const std::string& name, double value) {
        if (name == "packagingProbe" && value == 42) {
            std::lock_guard<std::mutex> lock(mutex);
            received = true;
            changed.notify_all();
        }
    }, [] { return "{}"; });
    overlay->setWindowFocused(true);
    // Production delivery waits for the real controls.js controlsReady message.
    overlay->runJavaScript("if (typeof window.playerControls !== 'function') throw new Error('controls missing');"
                          "window.webkit.messageHandlers.player.postMessage({type:'packagingProbe', value:42});");
    {
        std::unique_lock<std::mutex> lock(mutex);
        if (!changed.wait_for(lock, std::chrono::seconds(45), [&] { return received; })) {
            std::fprintf(stderr, "Timed out waiting for production overlay round trip\n");
            return 4;
        }
    }
    std::puts("OVERLAY_ROUND_TRIP_PASS");
    std::fflush(stdout);
    // The harness inspects subprocess namespaces/status while this is alive.
    if (std::getchar() != 'q') return 5;
    overlay->close();
    overlay.reset();
    XDestroyWindow(display, window);
    XCloseDisplay(display);
    return 0;
}
