#include "x11_error_handler_guard.h"

#include <dlfcn.h>
#include <atomic>

// mpv's X11 code calls XSetErrorHandler(NULL) whenever its video output closes, and that is not only
// when the player does: an output that fails to start, on a machine with no Vulkan device, leaves
// Xlib's default in place with the app still running. That default exits the process on the first X
// error, and AWT makes harmless ones (RenderFreePicture on a canvas whose window has gone) that its own
// handler ignores. Restoring afterwards cannot close that gap, so the bridge answers mpv's call itself:
// libmpv is loaded as the bridge's dependency, which puts this definition ahead of libX11's for it.
namespace {
std::atomic<XErrorHandler> replacement{nullptr};
}

void linuxReplaceFatalX11Default(XErrorHandler handler) { replacement.store(handler); }

extern "C" __attribute__((visibility("default"))) XErrorHandler XSetErrorHandler(XErrorHandler handler) {
    using Set = XErrorHandler (*)(XErrorHandler);
    static const Set real = reinterpret_cast<Set>(dlsym(RTLD_NEXT, "XSetErrorHandler"));
    return real(handler ? handler : replacement.load());
}
