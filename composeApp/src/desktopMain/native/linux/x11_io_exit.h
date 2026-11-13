#pragma once

// What happens when an X connection dies (the display server went away: a logout, a crashed
// Xwayland, a nested compositor closed). Xlib's default is to call exit() from inside the failing
// call, with the display's user lock held; libGLX's and NVIDIA EGL's exit handlers then take that
// lock again and the process hangs for good, ignoring SIGTERM, since the JVM's own shutdown waits on
// that exit (seen in cage, 2026-11-13). Instead the process is asked to quit once, the way a logout
// does it, so the app shuts down normally (stores flushed, players closed), and the dead connection
// is left alone: Xlib returns at once from every later call on it.
//
// Every connection needs it: one left on Xlib's default still exits from inside Xlib, and NVIDIA's
// exit handler then waits on another display's lock (the 1.38.1 package: AWT's connection; with a
// video playing, mpv's own). Most of them are never handed to us, so it goes in as libX11 makes each
// one (linuxHookX11DisplayOpen, from the bridge's load, before AWT, GDK or mpv open theirs).
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

inline void linuxX11IoExitHandler(Display *display, void *) {
    // _XIOError took the display's user lock for the exit() it expected, and nothing gives it back when
    // this returns. Held, every later XLockDisplay on this display waits forever, among them the GL
    // drivers' exit handlers, which walk every display the process opened.
    XUnlockDisplay(display);
    if (linuxX11IoExits().fetch_add(1) > 0) return;
    std::fprintf(stderr, "Linux controls: the X display connection was lost; asking the app to quit\n");
    linuxX11IoExitAction()();
}

// The process-wide I/O error handler runs before a display's exit handler. GDK installs its own as it
// opens its display, and that one _exit()s at once: no shutdown, stores left unflushed (the JVM build
// in cage, 2026-11-13). This one only returns, so each display's exit handler decides. Installed after
// gtk_init, once both AWT and GDK have set theirs.
inline int linuxX11IoErrorReturns(Display *) { return 0; }
inline void linuxInstallX11IoErrorHandler() { XSetIOErrorHandler(linuxX11IoErrorReturns); }

// Returns whether the handler went in.
inline bool linuxInstallX11IoExitHandler(Display *display) {
    using Setter = void (*)(Display *, void (*)(Display *, void *), void *);
    static const auto setter = reinterpret_cast<Setter>(dlsym(RTLD_DEFAULT, "XSetIOErrorExitHandler"));
    if (!display || !setter) return false;
    setter(display, linuxX11IoExitHandler, nullptr);
    return true;
}

// XOpenDisplay sets a new display's default exit handler and then initializes its lock through
// _XInitDisplayLock_fn, which XInitThreads sets (libX11 1.7.5 and current alike). Wrapping that pointer
// gives every connection the handler above as it is made. Call after XInitThreads; returns whether the
// hook went in.
inline int (*&linuxX11InitDisplayLockNext())(Display *) {
    static int (*next)(Display *) = nullptr;
    return next;
}

inline int linuxX11InitDisplayLock(Display *display) {
    const int result = linuxX11InitDisplayLockNext()(display);
    if (result == 0) linuxInstallX11IoExitHandler(display);
    return result;
}

inline bool linuxHookX11DisplayOpen() {
    auto *hook = reinterpret_cast<int (**)(Display *)>(dlsym(RTLD_DEFAULT, "_XInitDisplayLock_fn"));
    if (!hook || !*hook || *hook == linuxX11InitDisplayLock) return false;
    linuxX11InitDisplayLockNext() = *hook;
    *hook = linuxX11InitDisplayLock;
    return true;
}
