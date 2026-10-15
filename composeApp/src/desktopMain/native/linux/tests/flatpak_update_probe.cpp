// Drives nuvio::flatpak::update against whatever session bus the environment names.
// Usage: linux_flatpak_update_probe <idle-timeout-ms>. Prints "progress op/ops percent" lines
// and a final "result status|error|message" line.
#include <cstdio>
#include <cstdlib>
#include "../flatpak_update.h"

int main(int argc, char **argv) {
    int timeout = argc > 1 ? std::atoi(argv[1]) : 2000;
    auto result = nuvio::flatpak::update([](int op, int ops, int progress) {
        std::printf("progress %d/%d %d\n", op, ops, progress);
        std::fflush(stdout);
    }, timeout);
    std::printf("result %d|%s|%s\n", result.status, result.error.c_str(), result.message.c_str());
    return 0;
}
