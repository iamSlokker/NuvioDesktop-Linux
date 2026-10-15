// nuvio-hdr-probe: read-only Wayland/Vulkan HDR capability snapshot for the
// experimental Linux HDR session. Prints one JSON object on stdout.
//
// The application runs this only after every cheaper gate passed (setting enabled,
// WAYLAND_DISPLAY present, Wayland-capable libmpv, PQ/HLG source, HDR mode not
// Tonemap) and always under its own hard timeout; Vulkan driver queries are not
// cancellable. It never maps a surface, presents, or changes any output setting.

#include "hdr_capability_probe.h"

#include <cstdio>
#include <cstring>
#include <string>

int main(int argc, char **argv) {
    if (argc != 2 || std::strcmp(argv[1], "--probe-json") != 0) {
        std::fprintf(stderr, "Usage: nuvio-hdr-probe --probe-json\n");
        return 64;
    }
    const auto capabilities = probeLinuxHdrCapabilities(std::chrono::milliseconds(5000));
    const std::string json = linuxHdrCapabilitiesJson(capabilities);
    std::fwrite(json.data(), 1, json.size(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
    // Zero means "a snapshot was produced", including an incomplete or SDR one. The
    // application policy, never this exit code, decides eligibility.
    return 0;
}
