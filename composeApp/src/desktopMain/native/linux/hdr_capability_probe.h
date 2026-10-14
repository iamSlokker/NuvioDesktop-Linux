#pragma once

// Read-only Wayland/Vulkan HDR capability snapshot for the experimental HDR session.
// Built into the separate nuvio-hdr-probe helper so a hung or crashing GPU driver
// can never take the application down and the caller can enforce a hard timeout.

#include <chrono>
#include <cstdint>
#include <string>
#include <vector>

// Named wp_color_manager_v1 values ("st2084_pq", "bt2020", "srgb", ...). Unknown or
// ICC-only descriptions stay empty and are never treated as SDR or HDR by assumption.
struct LinuxHdrImageDescription {
    bool ready = false;
    bool failed = false;
    std::string failure;
    std::string transfer;
    std::string primaries;
    double minNits = -1;
    double maxNits = -1;
    double referenceNits = -1;
    double targetMinNits = -1;
    double targetMaxNits = -1;
};

struct LinuxHdrVulkanDevice {
    std::string name;
    bool hdrMetadataExtension = false;
    bool hdr10SurfaceFormat = false;
    bool hdr10TenBitSurfaceFormat = false;
    bool waylandPresentQueue = false;
};

struct LinuxHdrCapabilities {
    bool probeComplete = false;
    std::string reason;
    uint32_t colorManagerVersion = 0;
    bool pqAdvertised = false;
    bool bt2020Advertised = false;
    LinuxHdrImageDescription preferred;
    std::vector<LinuxHdrImageDescription> outputs;
    std::vector<LinuxHdrVulkanDevice> devices;
};

// Creates one UNMAPPED wl_surface and VkWaylandSurfaceKHR: no role, buffer, commit,
// swapchain, presentation or output-setting change. Refuses to connect when
// WAYLAND_DISPLAY is unset (libwayland would otherwise fall back to wayland-0).
LinuxHdrCapabilities probeLinuxHdrCapabilities(std::chrono::milliseconds timeout);

std::string linuxHdrCapabilitiesJson(const LinuxHdrCapabilities &capabilities);
