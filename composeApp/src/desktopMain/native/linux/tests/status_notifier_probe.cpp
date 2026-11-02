// Drives the tray with no JVM, display or GTK: tests/status_notifier_test.py talks to it on a private bus.
#include "../status_notifier.h"
#include <iostream>
#include <string>

int main() {
    std::cout << (nuvio::tray::available() ? "available" : "unavailable") << std::endl;
    nuvio::tray::Config config{"Nuvio HTPC", "Open Nuvio", "Nuvio 1.2.3", "Exit", {}};
    config.icons.push_back({2, 2, std::vector<uint8_t>(16, 0x7f)});
    auto *item = nuvio::tray::open(config);
    std::cout << (item ? "opened" : "refused") << std::endl;
    if (!item) return 0;
    for (;;) {
        const int event = nuvio::tray::next(item, 100);
        if (event != nuvio::tray::None) std::cout << "event " << event << std::endl;
        if (event == nuvio::tray::Exit) break;
    }
    nuvio::tray::close(item);
    std::cout << "closed" << std::endl;
    return 0;
}
