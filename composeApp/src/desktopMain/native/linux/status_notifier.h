#pragma once
#include <cstdint>
#include <string>
#include <vector>

// A StatusNotifierItem tray icon with a flat DBusMenu, on its own GDBus connection and thread.
// Nothing calls back into the JVM: the caller drains events with next().
namespace nuvio::tray {

enum Event { None = 0, Open = 1, Exit = 2, HostLost = 3, HostBack = 4 };

struct Pixmap {
    int width = 0, height = 0;
    std::vector<uint8_t> argb; // ARGB32 in network byte order, as the specification asks.
};

struct Config {
    std::string title, openLabel, infoLabel, exitLabel;
    std::vector<Pixmap> icons;
};

// Whether something on the session bus would show an icon right now. Asks and disconnects.
bool available();

class Item;
// Null when the bus, the watcher or the name is not available.
Item *open(const Config &config);
// Waits up to timeoutMs for the next event. The caller stops calling this before close().
int next(Item *item, int timeoutMs);
void close(Item *item);

}
