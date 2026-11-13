#pragma once

// What happens when the GTK thread's X connection dies (the display server went away: a logout, a
// crashed Xwayland, a nested compositor closed). Xlib's default is to call exit() from inside the
// failing call, with the display lock held; libGLX's exit handler then takes that lock again and the
// process hangs for good, ignoring SIGTERM, since the JVM's own shutdown waits on that exit (seen in
// cage, 2026-11-13). Instead the process is asked to quit once, the way a logout does it, so the app
// shuts down normally (stores flushed, players closed), and the dead connection is left alone: Xlib
// returns at once from every later call on it.
//
// XSetIOErrorExitHandler is libX11 1.7 (2020); an older libX11 keeps its default.

#include <X11/Xlib.h>
#include <atomic>
#include <csignal>
#include <cstdio>
#include <dlfcn.h>
#include <functional>
#include <unistd.h>

inline std::function<void()> &linuxX11IoExitAction() {
    static std::function<void()> action = [] { kill(getpid(), SIGTERM); };
    return action;
}

inline std::atomic<int> &linuxX11IoExits() {
    static std::atomic<int> count{0};
    return count;
}

inline void linuxX11IoExitHandler(Display *, void *) {
    if (linuxX11IoExits().fetch_add(1) > 0) return;
    std::fprintf(stderr, "Linux controls: the X display connection was lost; asking the app to quit\n");
    linuxX11IoExitAction()();
}

// Returns whether the handler went in.
inline bool linuxInstallX11IoExitHandler(Display *display) {
    using Setter = void (*)(Display *, void (*)(Display *, void *), void *);
    static const auto setter = reinterpret_cast<Setter>(dlsym(RTLD_DEFAULT, "XSetIOErrorExitHandler"));
    if (!display || !setter) return false;
    setter(display, linuxX11IoExitHandler, nullptr);
    return true;
}
