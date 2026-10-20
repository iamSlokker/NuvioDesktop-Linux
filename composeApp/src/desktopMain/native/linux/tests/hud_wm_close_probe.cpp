// Isolated-display regression probe: a window manager's close request (WM_DELETE_WINDOW, what
// Hyprland's killactive sends) to the override-redirect HUD must close the player like the HUD's
// own close button, and must not destroy the HUD window.
#include "controls_overlay.h"
#include <X11/Xlib.h>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>

static bool envIs(const char *name, const char *value) {
    const char *actual = std::getenv(name);
    return actual && std::string(actual) == value;
}

static int ignoreErrors(Display *, XErrorEvent *) { return 0; }

int main(int argc, char **argv) {
    if (argc != 2 || !envIs("NUVIO_ISOLATED_VIDEO_PROBE", "1") || !envIs("NUVIO_RUN_LIVE_DISPLAY_TESTS", "1")) return 2;
    XInitThreads();
    Display *display = XOpenDisplay(nullptr);
    if (!display) return 3;
    XSetErrorHandler(ignoreErrors); // A destroyed HUD window must fail the probe, not abort it.
    const Window root = DefaultRootWindow(display);
    const Window host = XCreateSimpleWindow(display, root, 0, 0, 640, 360, 0, 0, 0);
    XMapWindow(display, host);
    XSync(display, False);
    std::mutex mutex;
    std::condition_variable changed;
    int completed = 0, backs = 0;
    auto overlay = LinuxControlsOverlay::create(host, argv[1], [&](const std::string &name, double value) {
        std::lock_guard<std::mutex> lock(mutex);
        if (name == "back") ++backs;
        if (name == "closeProbe") completed = static_cast<int>(value);
        changed.notify_all();
    }, [] { return ""; });
    overlay->setWindowFocused(true);
    auto run = [&](const std::string &script, int step) {
        overlay->runJavaScript(script + ";window.webkit.messageHandlers.player.postMessage({type:'closeProbe',value:" +
            std::to_string(step) + "});");
        std::unique_lock<std::mutex> lock(mutex);
        if (!changed.wait_for(lock, std::chrono::seconds(30), [&] { return completed == step; }))
            throw std::runtime_error("Production controls script did not complete");
    };
    run("hasReceivedPlayerControls = true;"
        "state = {...state, isLoading:false, isPlaying:true, durationMs:60000, controlsVisible:true};"
        "renderOpeningOverlay(false); render();", 1);
    // The HUD is the only viewable override-redirect window on this private display.
    Window hud = None;
    for (int attempt = 0; attempt < 50 && hud == None; ++attempt) {
        Window rootReturn = None, parent = None, *children = nullptr;
        unsigned int count = 0;
        if (XQueryTree(display, root, &rootReturn, &parent, &children, &count)) {
            for (unsigned int i = 0; i < count; ++i) {
                XWindowAttributes attributes{};
                if (children[i] != host && XGetWindowAttributes(display, children[i], &attributes) &&
                    attributes.override_redirect && attributes.map_state == IsViewable) hud = children[i];
            }
            if (children) XFree(children);
        }
        if (hud == None) std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    if (hud == None) throw std::runtime_error("HUD window not mapped");
    XEvent event;
    std::memset(&event, 0, sizeof event);
    event.xclient.type = ClientMessage;
    event.xclient.window = hud;
    event.xclient.message_type = XInternAtom(display, "WM_PROTOCOLS", False);
    event.xclient.format = 32;
    event.xclient.data.l[0] = static_cast<long>(XInternAtom(display, "WM_DELETE_WINDOW", False));
    event.xclient.data.l[1] = CurrentTime;
    XSendEvent(display, hud, False, NoEventMask, &event);
    XSync(display, False);
    {
        std::unique_lock<std::mutex> lock(mutex);
        if (!changed.wait_for(lock, std::chrono::seconds(10), [&] { return backs == 1; }))
            throw std::runtime_error("Close request did not reach the player as \"back\"");
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    XWindowAttributes attributes{};
    if (!XGetWindowAttributes(display, hud, &attributes))
        throw std::runtime_error("Close request destroyed the HUD window");
    run("render();", 2); // The page still runs and still reaches the player.
    std::puts("HUD_WM_CLOSE_PASS");
    overlay.reset();
    XCloseDisplay(display);
    return 0;
}
