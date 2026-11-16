#pragma once

#include <string>

// The phone remote's Pointer and the controller cursor move the real cursor. On Wayland, XTest
// (java.awt.Robot) only moves Xwayland's own pointer, so this drives the compositor's cursor through
// org.freedesktop.portal.RemoteDesktop instead: one session for the app's life, pointer and keyboard,
// remembered with the portal's restore token so the desktop asks only once.
namespace nuvio::remotepointer {
enum Outcome {
    Started = 0,      // The portal's own response codes: 0 granted, 1 cancelled, 2 other error.
    Cancelled = 1,
    Failed = 2,
    Unavailable = -1, // No session bus or no RemoteDesktop portal.
    TimedOut = -2,    // The permission dialog was left open.
    Unsupported = -4, // The portal offers no pointer.
};

struct StartResult {
    int status = Unavailable;
    std::string restoreToken; // The portal's new token (empty: none given).
    std::string message;
};

// Blocks until the session runs or fails; the first start may show the desktop's permission dialog.
StartResult start(const std::string &restoreToken, int timeoutMs);
// False once the session ended (the user revoked it, the portal stopped) or before start.
bool active();
// Fire-and-forget input on the running session; ignored without one.
void motion(double dx, double dy);
void button(int evdevCode, bool pressed);
void axis(int axis, int steps); // 0 vertical, 1 horizontal; positive is down or right.
void key(int evdevCode, bool pressed);
void stop();
}
