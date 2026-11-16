// Drives nuvio::remotepointer against whatever session bus the environment names.
// Usage: linux_remote_pointer_portal_probe <timeout-ms> [restore-token]
// Starts a session, then (when it runs) sends one of each input and stops, printing each step.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include "../remote_pointer_portal.h"

int main(int argc, char **argv) {
    const int timeout = argc > 1 ? std::atoi(argv[1]) : 2000;
    const auto result = nuvio::remotepointer::start(argc > 2 ? argv[2] : "", timeout);
    std::printf("start %d|%s|%s\n", result.status, result.restoreToken.c_str(), result.message.c_str());
    std::printf("active %d\n", nuvio::remotepointer::active());
    if (result.status == nuvio::remotepointer::Started) {
        // A second start while running is a no-op.
        std::printf("again %d\n", nuvio::remotepointer::start("", timeout).status);
        nuvio::remotepointer::motion(12.5, -3);
        nuvio::remotepointer::button(272, true);
        nuvio::remotepointer::button(272, false);
        nuvio::remotepointer::key(42, true);
        nuvio::remotepointer::axis(0, -2);
        nuvio::remotepointer::key(42, false);
        nuvio::remotepointer::axis(1, 0);  // No steps: nothing sent.
        // The stand-in portal closes the session after the fifth call when asked to.
        for (int wait = 0; wait < 40 && nuvio::remotepointer::active(); ++wait) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        std::printf("after %d\n", nuvio::remotepointer::active());
        nuvio::remotepointer::stop();
    }
    std::printf("stopped %d\n", nuvio::remotepointer::active());
    return 0;
}
