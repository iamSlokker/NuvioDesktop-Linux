// Isolated-display regression probe: the production overlay + controls.js must blank a parked
// pointer when the chrome fades, and restore it when the chrome comes back.
#include "controls_overlay.h"
#include <X11/Xlib.h>
#include <X11/extensions/Xfixes.h>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>

static bool envIs(const char *name, const char *value) {
    const char *actual = std::getenv(name);
    return actual && std::string(actual) == value;
}

int main(int argc, char **argv) {
    if (argc != 2 || !envIs("NUVIO_ISOLATED_VIDEO_PROBE", "1") || !envIs("NUVIO_RUN_LIVE_DISPLAY_TESTS", "1")) return 2;
    XInitThreads();
    Display *display = XOpenDisplay(nullptr);
    if (!display) return 3;
    int eventBase = 0, errorBase = 0;
    if (!XFixesQueryExtension(display, &eventBase, &errorBase)) return 4;
    const Window root = DefaultRootWindow(display);
    const Window host = XCreateSimpleWindow(display, root, 0, 0, 640, 360, 0, 0, 0);
    XMapWindow(display, host);
    XSync(display, False);
    std::mutex mutex;
    std::condition_variable changed;
    int completed = 0;
    std::shared_ptr<LinuxControlsOverlay> overlay;
    std::shared_ptr<LinuxControlsOverlay> *holder = &overlay;
    overlay = LinuxControlsOverlay::create(host, argv[1], [&, holder](const std::string &name, double value) {
        // What NativePlayerController does with the page's message on Linux.
        if (name == "cursorVisibility" && *holder) (*holder)->setCursorHidden(value == 0.0);
        if (name == "cursorProbe") {
            std::lock_guard<std::mutex> lock(mutex);
            completed = static_cast<int>(value);
            changed.notify_all();
        }
    }, [] { return ""; });
    overlay->setWindowFocused(true);
    auto run = [&](const std::string &script, int step) {
        overlay->runJavaScript(script + ";window.webkit.messageHandlers.player.postMessage({type:'cursorProbe',value:" +
            std::to_string(step) + "});");
        std::unique_lock<std::mutex> lock(mutex);
        if (!changed.wait_for(lock, std::chrono::seconds(30), [&] { return completed == step; }))
            throw std::runtime_error("Production controls script did not complete");
    };
    // True when the sprite X currently shows has any opaque pixel.
    auto cursorVisible = [&] {
        XFixesCursorImage *image = XFixesGetCursorImage(display);
        if (!image) throw std::runtime_error("No cursor image");
        bool opaque = false;
        for (int i = 0; i < image->width * image->height && !opaque; ++i)
            opaque = ((image->pixels[i] >> 24) & 0xff) != 0;
        XFree(image);
        return opaque;
    };
    auto expectCursor = [&](bool visible, const char *what) {
        for (int attempt = 0; attempt < 50; ++attempt) {
            if (cursorVisible() == visible) return;
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        throw std::runtime_error(std::string("Cursor not ") + (visible ? "visible" : "hidden") + " " + what);
    };
    // Visible, playing chrome with the pointer parked over the video area.
    run("hasReceivedPlayerControls = true;"
        "state = {...state, isLoading:false, isPlaying:true, durationMs:60000, controlsVisible:true};"
        "renderOpeningOverlay(false); render();", 1);
    XWarpPointer(display, None, host, 0, 0, 0, 0, 320, 120);
    XSync(display, False);
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    expectCursor(true, "while the chrome is up");
    // The chrome fades (as the auto-hide timer does) without any pointer motion.
    run("state = {...state, controlsVisible:false}; render();", 2);
    expectCursor(false, "after the chrome faded over a parked pointer");
    // A key brings the chrome back while the pointer stays parked.
    run("state = {...state, controlsVisible:true}; render();", 3);
    expectCursor(true, "after the chrome came back");
    // The usual cycle: the pointer moves (WebKit applies its own cursor), stops, and the chrome
    // fades again over the parked pointer.
    for (int step = 0; step < 10; ++step) {
        XWarpPointer(display, None, host, 0, 0, 0, 0, 300 + step * 8, 140 + step * 4);
        XSync(display, False);
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    expectCursor(true, "after the pointer moved");
    run("state = {...state, controlsVisible:false}; render();", 4);
    expectCursor(false, "after the chrome faded again over a parked pointer");
    // The real path: the Linux host's configuration, the chrome shown once, then no input at all
    // until controls.js's own auto-hide timer fades it (upstream's 5.5 s).
    run("window.nuvioConfigureChromeAutoHide(null, true);"
        "state = {...state, controlsVisible:true}; render(); noteChromeActivity(true);", 5);
    const auto shownAt = std::chrono::steady_clock::now();
    expectCursor(true, "once the chrome was shown for the timer");
    std::this_thread::sleep_for(std::chrono::milliseconds(4500));
    if (!cursorVisible()) throw std::runtime_error("Cursor hidden before the 5.5 s fade");
    expectCursor(false, "after the auto-hide timer faded the chrome");
    const auto hiddenAfterMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - shownAt).count();
    std::printf("AUTO_HIDE_CURSOR_MS %lld\n", static_cast<long long>(hiddenAfterMs));
    std::puts("CURSOR_HIDE_PASS");
    overlay.reset();
    XCloseDisplay(display);
    return 0;
}
