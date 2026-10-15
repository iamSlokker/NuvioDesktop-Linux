#pragma once

#include <functional>
#include <string>

namespace nuvio::flatpak {
// Portal Progress status values (flatpak-portal.c), plus failures before or around the portal call.
enum Status : int { Running = 0, Empty = 1, Done = 2, Error = 3, Unavailable = -1, TimedOut = -2 };

struct UpdateResult {
    int status = Unavailable;
    std::string error;    // D-Bus error name reported by the portal, if any
    std::string message;
};

// Asks org.freedesktop.portal.Flatpak to update the calling Flatpak app and blocks until it
// finishes, or until no progress arrives for idleTimeoutMs. onProgress(op, ops, percent) runs on
// the calling thread.
UpdateResult update(const std::function<void(int, int, int)> &onProgress, int idleTimeoutMs);
}
