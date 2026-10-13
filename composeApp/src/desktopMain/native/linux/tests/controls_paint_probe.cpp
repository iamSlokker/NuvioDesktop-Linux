// Isolated-display diagnostic: production overlay + controls.js, without mpv.
#include "controls_overlay.h"
#include <gtk/gtk.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>

int main(int argc, char **argv) {
    if (argc != 2 || !std::getenv("NUVIO_ISOLATED_VIDEO_PROBE") ||
        std::string(std::getenv("NUVIO_ISOLATED_VIDEO_PROBE")) != "1" ||
        !std::getenv("NUVIO_RUN_LIVE_DISPLAY_TESTS") ||
        std::string(std::getenv("NUVIO_RUN_LIVE_DISPLAY_TESTS")) != "1") return 2;
    XInitThreads();
    Display *display = XOpenDisplay(nullptr);
    if (!display) return 3;
    const Window root = DefaultRootWindow(display);
    const Window host = XCreateSimpleWindow(display, root, 0, 0, 640, 360, 0, 0, 0);
    XMapWindow(display, host);
    XSync(display, False);
    std::mutex mutex;
    std::condition_variable changed;
    int completed = 0;
    auto overlay = LinuxControlsOverlay::create(host, argv[1], [&](const std::string &name, double value) {
        if (name == "paintProbe") {
            std::lock_guard<std::mutex> lock(mutex);
            completed = static_cast<int>(value);
            changed.notify_all();
        }
    }, [] { return ""; }); // The fixture controls state; no periodic empty snapshot may overwrite it.
    overlay->setWindowFocused(true);
    auto run = [&](const std::string &script, int step) {
        overlay->runJavaScript(script + ";window.webkit.messageHandlers.player.postMessage({type:'paintProbe',value:" +
            std::to_string(step) + "});");
        std::unique_lock<std::mutex> lock(mutex);
        if (!changed.wait_for(lock, std::chrono::seconds(30), [&] { return completed == step; }))
            throw std::runtime_error("Production controls script did not complete");
    };
    run(R"JS(
        const style = document.createElement('style');
        style.textContent = '.player *{visibility:hidden!important}' +
          '#seekThumbnail,#seekThumbnail *{visibility:visible!important}' +
          '#seekThumbnail{position:fixed!important;left:20px!important;top:20px!important;' +
          'bottom:auto!important;width:100px!important;transform:none!important;' +
          'border:0!important;border-radius:0!important;box-shadow:none!important}' +
          '#seekThumbnail img{width:100px!important;height:60px!important;border-radius:0!important}';
        document.head.appendChild(style);
        hasReceivedPlayerControls = true;
        state = {...state, isLoading:false, durationMs:60000, seekThumbnailsEnabled:true};
        renderOpeningOverlay(false);
        showSeekThumbnailAt({clientX:seek.getBoundingClientRect().left + 40});
    )JS", 1);
    GdkPixbuf *pixel = gdk_pixbuf_new(GDK_COLORSPACE_RGB, TRUE, 8, 16, 9);
    gdk_pixbuf_fill(pixel, 0xff0000ff);
    gchar *bytes = nullptr;
    gsize size = 0;
    if (!gdk_pixbuf_save_to_buffer(pixel, &bytes, &size, "png", nullptr, nullptr)) return 4;
    gchar *encoded = g_base64_encode(reinterpret_cast<guchar *>(bytes), size);
    const std::string url = "data:image/png;base64," + std::string(encoded);
    g_free(encoded); g_free(bytes); g_object_unref(pixel);
    run("window.nuvioSeekThumbnailReady(pendingSeekThumbnailPosition, '" + url + "')", 2);
    Window overlayWindow = None, returnedRoot, parent, *children = nullptr;
    unsigned count = 0;
    if (!XQueryTree(display, root, &returnedRoot, &parent, &children, &count)) return 5;
    for (unsigned i = 0; i < count; ++i) {
        XWindowAttributes attributes{};
        if (XGetWindowAttributes(display, children[i], &attributes) && attributes.override_redirect &&
            attributes.depth == 32 && attributes.width == 640 && attributes.height == 360)
            overlayWindow = children[i];
    }
    XFree(children);
    if (!overlayWindow) throw std::runtime_error("Missing production ARGB overlay");
    enum class Paint { Transparent, Red, Black };
    auto awaitPixel = [&](int x, int y, Paint expected) {
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        unsigned long value = 0;
        do {
            XImage *image = XGetImage(display, overlayWindow, x, y, 1, 1, AllPlanes, ZPixmap);
            if (!image) throw std::runtime_error("Cannot capture overlay pixel");
            value = XGetPixel(image, 0, 0);
            XDestroyImage(image);
            const auto alpha = (value >> 24) & 255, red = (value >> 16) & 255;
            if ((expected == Paint::Red && red > 200 && alpha > 200) ||
                (expected == Paint::Black && red < 20 && alpha > 180) ||
                (expected == Paint::Transparent && alpha == 0)) return;
            std::this_thread::sleep_for(std::chrono::milliseconds(10)); // Bounded paint readiness only.
        } while (std::chrono::steady_clock::now() < deadline);
        std::fprintf(stderr, "ARGB at %d,%d = %08lx (expected paint %d)\n", x, y, value, static_cast<int>(expected));
        throw std::runtime_error("Overlay paint/clear failed at " + std::to_string(x) + "," + std::to_string(y));
    };
    awaitPixel(20, 20, Paint::Transparent);
    awaitPixel(60, 60, Paint::Red);
    int failures = 0;
    auto expectPixel = [&](int x, int y, Paint expected, const char *group) {
        try { awaitPixel(x, y, expected); }
        catch (const std::runtime_error &error) {
            std::fprintf(stderr, "FAIL %s: %s\n", group, error.what());
            ++failures;
        }
    };
    run("seekThumbnail.style.setProperty('left','180px','important');seekThumbnailTime.textContent='00:35'", 3);
    awaitPixel(380, 60, Paint::Red);
    expectPixel(60, 60, Paint::Transparent, "moved thumbnail erases old pixels");
    run("hideSeekThumbnail()", 4);
    expectPixel(380, 60, Paint::Transparent, "hidden thumbnail erases pixels");
    run("openingOverlay.style.setProperty('visibility','visible','important');"
        "openingOverlay.style.setProperty('background','black','important');"
        "state={...state,isLoading:true,showOpeningOverlay:true};renderOpeningOverlay(false)", 5);
    awaitPixel(320, 180, Paint::Black);
    run("state={...state,isLoading:false};renderOpeningOverlay(false)", 6);
    expectPixel(320, 180, Paint::Transparent, "dismissed full-screen scrim erases pixels");
    if (failures) {
        overlay->close(); overlay.reset();
        XDestroyWindow(display, host); XCloseDisplay(display);
        return 1;
    }
    std::puts("ISOLATED_HUD_PAINT_PASS: moved/hidden thumbnail and dismissed full-screen scrim clear to alpha zero");
    std::fflush(stdout);
    if (std::getchar() != 'q') return 6; // Harness inspects the sandbox before authorizing cleanup.
    overlay->close(); overlay.reset();
    XDestroyWindow(display, host);
    XCloseDisplay(display);
}
