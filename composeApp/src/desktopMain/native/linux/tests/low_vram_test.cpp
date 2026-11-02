// The Low VRAM decision and options (low_vram.h): the pure rules, mpv keeping the trimmed pipeline
// (real libmpv, null outputs), and the real Vulkan loader, which must never crash or claim a device
// it does not have. Run with whatever drivers the machine has; the builder has software only.
#include "low_vram.h"
#include <mpv/client.h>
#include <iostream>
#include <memory>
#include <stdexcept>

namespace {
constexpr uint64_t GiB = 1024ull * 1024 * 1024;

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const std::string &operation) {
    require(result >= 0, operation + ": " + mpv_error_string(result));
}

void rules() {
    const LinuxVulkanDevice small{"small", 2, GiB}, big{"big", 2, 8 * GiB}, igpu{"igpu", 1, 16 * GiB},
        lowIgpu{"lowIgpu", 1, GiB}, cpu{"llvmpipe", 4, 64 * GiB}, virtualGpu{"virtio", 3, 4 * GiB};
    require(!linuxPlaybackVulkanDevice({}), "no devices");
    require(!linuxPlaybackVulkanDevice({cpu}), "a CPU device is not one mpv would pick");
    require(linuxPlaybackVulkanDevice({igpu, big})->name == "big", "discrete first");
    require(linuxPlaybackVulkanDevice({igpu, small})->name == "small", "a small discrete card beats a roomy iGPU");
    require(linuxPlaybackVulkanDevice({small, big})->name == "big", "largest among equals");
    require(linuxPlaybackVulkanDevice({virtualGpu, lowIgpu})->name == "lowIgpu", "integrated before virtual");

    const auto device = [](uint64_t bytes) { return std::optional<LinuxVulkanDevice>(LinuxVulkanDevice{"d", 2, bytes}); };
    require(linuxLowVramActive("on", std::nullopt), "On needs no probe");
    require(!linuxLowVramActive("off", device(GiB)), "Off is off");
    require(linuxLowVramActive("auto", device(GiB)), "Auto below 2 GiB");
    require(!linuxLowVramActive("auto", device(2 * GiB)), "2 GiB is enough");
    require(!linuxLowVramActive("auto", std::nullopt), "Auto with no answer keeps the full pipeline");
    require(!linuxLowVramActive("bogus", device(GiB)), "an unknown mode is not On");

    require(*linuxLowVramPinned("scale") == "bilinear" && *linuxLowVramPinned("deband") == "no", "pins");
    require(!linuxLowVramPinned("glsl-shaders"), "shader chains are an explicit choice and stay");
    require(!linuxLowVramPinned("target-peak"), "unrelated keys pass");
}

void mpvKeepsThePipeline() {
    std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)> player(mpv_create(), mpv_terminate_destroy);
    mpv_handle *mpv = player.get();
    require(mpv != nullptr, "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"vo", "null"}, {"ao", "null"}, {"terminal", "no"}, {"idle", "yes"},
             {"scale", "ewa_lanczos"}, {"deband", "yes"}})
        check(mpv_set_option_string(mpv, name, value), name);
    for (const auto &[name, value] : linuxLowVramOptions()) check(mpv_set_option_string(mpv, name.c_str(), value.c_str()), name);
    check(mpv_initialize(mpv), "initialize");
    for (const auto &[name, value] : linuxLowVramOptions()) {
        char *kept = mpv_get_property_string(mpv, ("options/" + name).c_str());
        require(kept && value == kept, name + " kept " + (kept ? kept : "(null)"));
        mpv_free(kept);
        check(mpv_set_property_string(mpv, name.c_str(), value.c_str()), name + " at run time");
    }
}

void realLoader() {
    const auto devices = linuxVulkanDevices();
    for (const auto &device : devices) {
        std::cout << "  vulkan device: " << device.name << " type=" << device.type
                  << " deviceLocal=" << device.deviceLocalBytes / (1024 * 1024) << " MiB\n";
        require(!device.name.empty() && device.type >= 0 && device.type <= 4, "device fields are readable");
    }
    const auto chosen = linuxPlaybackVulkanDevice(devices);
    std::cout << "  playback device: " << (chosen ? chosen->name : std::string("none")) << '\n';
    require(&linuxCachedPlaybackVulkanDevice() == &linuxCachedPlaybackVulkanDevice(), "cached");
}
}

int main() {
    try {
        rules();
        std::cout << "PASS mode, threshold and device choice\n";
        mpvKeepsThePipeline();
        std::cout << "PASS mpv keeps the trimmed pipeline and takes it at run time\n";
        realLoader();
        std::cout << "PASS the real Vulkan loader answers without crashing\n";
        std::cout << "LOW_VRAM_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
