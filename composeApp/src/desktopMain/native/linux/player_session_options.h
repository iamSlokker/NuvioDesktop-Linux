#pragma once

// Pure mpv option lists for the two Linux presentation boundaries. No JNI, mpv
// handle, display or GTK state, so the exact contracts are unit-testable.

#include <string>
#include <utility>
#include <vector>

using LinuxMpvOptionList = std::vector<std::pair<std::string, std::string>>;

// The accepted embedded X11/AWT Canvas player. These boundaries are applied after
// shared/custom options so saved desktop options cannot replace them. Order and
// values are the existing SDR contract; the HDR session must never change them.
inline LinuxMpvOptionList linuxX11EmbeddingOptions(long long canvasXid) {
    return {
        {"config", "no"},
        {"terminal", "no"},
        {"osc", "no"},
        {"input-default-bindings", "no"},
        {"input-vo-keyboard", "no"},
        {"input-cursor", "no"},
        {"window-dragging", "no"},
        {"stop-screensaver", "no"},
        {"keep-open", "yes"},
        {"idle", "yes"},
        {"vo", "gpu-next"},
        {"gpu-api", "vulkan"},
        {"gpu-context", "x11vk"},
        {"wid", std::to_string(canvasXid)},
    };
}

struct LinuxHdrSessionConfig {
    // Exact Vulkan device name selected by the capability policy; empty keeps mpv's choice.
    std::string vulkanDevice;
    // Wayland xdg-toplevel application id, so the compositor attributes the window to Nuvio.
    std::string appId;
};

// Experimental native-Wayland HDR session: mpv owns its own fullscreen xdg_toplevel
// (no wid, no X11, no WebKit HUD). Keys/mouse reach mpv only to be forwarded to the
// application through explicit keybind/script-message bindings; default bindings
// stay disabled. Target colour-space options are supplied by the application (the
// shared desktop HDR-mode mapping), never forced here.
inline LinuxMpvOptionList linuxHdrSessionOptions(const LinuxHdrSessionConfig &config) {
    LinuxMpvOptionList options{
        {"config", "no"},
        {"terminal", "no"},
        {"osc", "no"},
        {"input-default-bindings", "no"},
        {"input-vo-keyboard", "yes"},
        {"input-cursor", "yes"},
        {"window-dragging", "no"},
        {"stop-screensaver", "no"},
        {"keep-open", "yes"},
        {"idle", "yes"},
        {"vo", "gpu-next"},
        {"gpu-api", "vulkan"},
        {"gpu-context", "waylandvk"},
        {"fullscreen", "yes"},
        {"title", "${?media-title:${media-title}}${!media-title:Nuvio} - Nuvio HDR (experimental)"},
    };
    if (!config.appId.empty()) options.emplace_back("wayland-app-id", config.appId);
    if (!config.vulkanDevice.empty()) options.emplace_back("vulkan-device", config.vulkanDevice);
    return options;
}
