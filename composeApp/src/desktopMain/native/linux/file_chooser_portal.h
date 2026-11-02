#pragma once

#include <string>
#include <vector>

namespace nuvio::filechooser {
// Response codes of org.freedesktop.portal.Request, plus failures before or around the portal call.
// Not "Status": Xlib defines that as a macro.
enum Outcome : int {
    Picked = 0,
    Cancelled = 1,
    Failed = 2,
    Unavailable = -1,
    TimedOut = -2,
    // The dialog succeeded, but the portal could not export the choice through the document portal.
    NothingShared = -3,
    // The portal is older than the FileChooser version this request needs.
    Unsupported = -4,
};

struct Request {
    bool save = false;
    bool directory = false;
    std::string parentWindow;  // "x11:<hex>", or empty
    std::string title;
    std::string currentFolder;
    std::string currentName;
    std::string filterName;
    std::vector<std::string> filterPatterns;
};

struct Result {
    int status = Unavailable;
    std::string uri;
    std::string message;
};

// Opens the desktop's file chooser through org.freedesktop.portal.FileChooser and blocks the calling
// thread until the user answers, the portal goes away, or timeoutMs passes.
Result choose(const Request &request, int timeoutMs);

struct TransferResult {
    bool ok = false;
    std::vector<std::string> files;
    std::string message;
};

// Redeems a drag source's org.freedesktop.portal.FileTransfer key. The portal exports the files for
// this app and answers with paths the sandbox can open.
TransferResult retrieveFiles(const std::string &key, int timeoutMs);

// The window manager's client window holding an X11 window: WM_TRANSIENT_FOR must name that one, and
// JAWT hands out AWT's content window inside it. Returns 0 when there is no such ancestor.
unsigned long managedAncestor(unsigned long window);
}
