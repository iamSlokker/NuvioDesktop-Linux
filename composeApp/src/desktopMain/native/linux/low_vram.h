#pragma once

// Windows' Low VRAM pipeline (DesktopLowVramMode). libplacebo sizes its intermediate targets from the
// source resolution; when the driver refuses an allocation it disables FBOs mid-frame and can then
// abort the process, so the memory must never be asked for. Bilinear needs no intermediate target at
// all, and deband, sigmoid/linear light and peak detection are the other source-sized allocations.
//
// Auto asks Vulkan where Windows asks DXGI. An integrated GPU reports its share of system memory here
// rather than a token pool, so Auto trims it only when that share really is small.

#include <dlfcn.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

inline std::vector<std::pair<std::string, std::string>> linuxLowVramOptions() {
    return {
        {"scale", "bilinear"}, {"dscale", "bilinear"}, {"cscale", "bilinear"},
        {"sigmoid-upscaling", "no"}, {"correct-downscaling", "no"}, {"deband", "no"},
        {"dither", "no"}, {"temporal-dither", "no"}, {"hdr-compute-peak", "no"},
    };
}

// The value a runtime write of key is held to while the pipeline is active: the profile re-asserts
// these on every file load and settings change, which would put the allocations back a frame later.
inline std::optional<std::string> linuxLowVramPinned(const std::string &key) {
    for (const auto &[name, value] : linuxLowVramOptions()) if (name == key) return value;
    return std::nullopt;
}

struct LinuxVulkanDevice {
    std::string name;
    int type = 0; // VkPhysicalDeviceType: 1 integrated, 2 discrete, 3 virtual, 4 CPU.
    uint64_t deviceLocalBytes = 0; // The largest DEVICE_LOCAL heap.
};

// The device libplacebo would choose (discrete, then integrated, then virtual; a CPU device needs an
// explicit --vulkan-device), and within that the largest heap. Empty when there is no such device.
inline std::optional<LinuxVulkanDevice> linuxPlaybackVulkanDevice(const std::vector<LinuxVulkanDevice> &devices) {
    std::optional<LinuxVulkanDevice> best;
    const auto rank = [](int type) { return type == 2 ? 3 : type == 1 ? 2 : type == 3 ? 1 : 0; };
    for (const auto &device : devices) {
        if (rank(device.type) == 0) continue;
        if (!best || rank(device.type) > rank(best->type) ||
            (rank(device.type) == rank(best->type) && device.deviceLocalBytes > best->deviceLocalBytes)) best = device;
    }
    return best;
}

constexpr uint64_t kLinuxLowVramThresholdBytes = 2ull * 1024 * 1024 * 1024;

// When Vulkan cannot answer the full pipeline stays: one machine crashing is a smaller failure than
// every machine silently losing scaler quality to a broken probe.
inline bool linuxLowVramActive(const std::string &mode, const std::optional<LinuxVulkanDevice> &device) {
    return mode == "on" || (mode == "auto" && device && device->deviceLocalBytes < kLinuxLowVramThresholdBytes);
}

namespace linux_low_vram_detail {
// The stable Vulkan 1.0 ABI, declared here because player_bridge builds without Vulkan headers; the
// loader is already in the process as a dependency of libmpv.
struct InstanceCreateInfo {
    int sType = 1; // VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
    const void *pNext = nullptr;
    uint32_t flags = 0;
    const void *pApplicationInfo = nullptr;
    uint32_t enabledLayerCount = 0;
    const char *const *ppEnabledLayerNames = nullptr;
    uint32_t enabledExtensionCount = 0;
    const char *const *ppEnabledExtensionNames = nullptr;
};
struct MemoryProperties {
    uint32_t typeCount;
    struct { uint32_t flags; uint32_t heap; } types[32];
    uint32_t heapCount;
    struct { uint64_t size; uint32_t flags; } heaps[16];
};
// VkPhysicalDeviceProperties: the fields used here sit ahead of the alignment-sensitive limits.
struct DevicePropertiesHead {
    uint32_t apiVersion, driverVersion, vendorId, deviceId;
    int type;
    char name[256];
};
constexpr size_t DevicePropertiesSize = 824;
constexpr uint32_t HeapDeviceLocal = 1;

using GetProc = void *(*)(void *, const char *);
}

inline std::vector<LinuxVulkanDevice> linuxVulkanDevices() {
    using namespace linux_low_vram_detail;
    std::vector<LinuxVulkanDevice> devices;
    void *loader = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!loader) return devices;
    const auto getProc = reinterpret_cast<GetProc>(dlsym(loader, "vkGetInstanceProcAddr"));
    const auto createInstance = getProc
        ? reinterpret_cast<int (*)(const InstanceCreateInfo *, const void *, void **)>(getProc(nullptr, "vkCreateInstance"))
        : nullptr;
    void *instance = nullptr;
    const InstanceCreateInfo info;
    if (createInstance && createInstance(&info, nullptr, &instance) == 0 && instance) {
        const auto enumerate = reinterpret_cast<int (*)(void *, uint32_t *, void **)>(getProc(instance, "vkEnumeratePhysicalDevices"));
        const auto properties = reinterpret_cast<void (*)(void *, void *)>(getProc(instance, "vkGetPhysicalDeviceProperties"));
        const auto memory = reinterpret_cast<void (*)(void *, MemoryProperties *)>(getProc(instance, "vkGetPhysicalDeviceMemoryProperties"));
        uint32_t count = 0;
        if (enumerate && properties && memory && enumerate(instance, &count, nullptr) == 0 && count > 0) {
            std::vector<void *> handles(count);
            if (enumerate(instance, &count, handles.data()) >= 0) {
                for (uint32_t index = 0; index < count; ++index) {
                    alignas(8) unsigned char raw[DevicePropertiesSize + 256] = {};
                    properties(handles[index], raw);
                    const auto *head = reinterpret_cast<const DevicePropertiesHead *>(raw);
                    MemoryProperties heaps{};
                    memory(handles[index], &heaps);
                    LinuxVulkanDevice device;
                    device.name = std::string(head->name, strnlen(head->name, sizeof(head->name)));
                    device.type = head->type;
                    for (uint32_t heap = 0; heap < std::min<uint32_t>(heaps.heapCount, 16); ++heap)
                        if (heaps.heaps[heap].flags & HeapDeviceLocal)
                            device.deviceLocalBytes = std::max(device.deviceLocalBytes, heaps.heaps[heap].size);
                    devices.push_back(std::move(device));
                }
            }
        }
        if (const auto destroy = reinterpret_cast<void (*)(void *, const void *)>(getProc(instance, "vkDestroyInstance")))
            destroy(instance, nullptr);
    }
    // Not dlclose: mpv keeps the loader in the process anyway.
    return devices;
}

// Asked once per process, and only for Auto: creating an instance loads every driver on the system.
inline const std::optional<LinuxVulkanDevice> &linuxCachedPlaybackVulkanDevice() {
    static std::once_flag once;
    static std::optional<LinuxVulkanDevice> device;
    std::call_once(once, [] { device = linuxPlaybackVulkanDevice(linuxVulkanDevices()); });
    return device;
}
