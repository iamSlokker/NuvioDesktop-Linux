// The library half of linux_x11_error_guard_test: loaded the way the JVM loads the bridge, so libmpv
// finds this library's XSetErrorHandler the way it finds the bridge's in the app.
#include "video_output_failure.h"
#include "x11_error_handler_guard.h"
#include <X11/Xlib.h>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <thread>

namespace {
int errorsSeen = 0;
int toolkitHandler(Display *, XErrorEvent *) { ++errorsSeen; return 0; }

bool waitForVerdict(mpv_handle *mpv, const char *video) {
    const char *load[] = {"loadfile", video, nullptr};
    if (mpv_command(mpv, load) < 0) return false;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (std::chrono::steady_clock::now() < deadline) {
        const mpv_event *event = mpv_wait_event(mpv, 0.1);
        if (linuxVideoOutputInitFailed(*event)) return true;
    }
    return false;
}
}

// 0 survived the X error through the toolkit handler, 3 the setup failed. Xlib's default exits with 1.
extern "C" __attribute__((visibility("default"))) int x11GuardProbe(const char *video, int guarded) {
    Display *display = XOpenDisplay(nullptr);
    if (!display) { std::cerr << "no display\n"; return 3; }
    XSetErrorHandler(toolkitHandler);
    if (guarded) linuxReplaceFatalX11Default(toolkitHandler);

    mpv_handle *mpv = mpv_create();
    if (!mpv) return 3;
    for (const auto &[name, value] : std::initializer_list<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"ao", "null"}, {"terminal", "no"},
             {"idle", "yes"}, {"keep-open", "yes"}, {"hwdec", "no"}, {"vo", "gpu-next"}, {"gpu-api", "vulkan"},
             {"gpu-context", "x11vk"}})
        if (mpv_set_option_string(mpv, name, value) < 0) return 3;
    if (mpv_initialize(mpv) < 0 || mpv_request_log_messages(mpv, "error") < 0) return 3;
    if (!waitForVerdict(mpv, video)) { std::cerr << "mpv gave no video output verdict\n"; return 3; }
    // The verdict is logged after the context failed, and so after mpv's X11 teardown.
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    XFreePixmap(display, 1); // No such pixmap: an X error with the player still alive, as in the app.
    XSync(display, False);
    mpv_terminate_destroy(mpv);
    if (errorsSeen != 1) { std::cerr << "toolkit handler saw " << errorsSeen << " errors\n"; return 3; }
    return 0;
}
