#pragma once

// Experimental Linux native-Wayland HDR playback session helpers.
//
// The session itself is an ordinary player_bridge Player created without a
// Canvas/XID (see LinuxHdrSessionNative_create in player_bridge.cpp). This unit
// holds the parts that need neither JNI nor a display, so they can be exercised
// by headless fixtures: libmpv capability detection, the app-owned input
// bindings, and the translation of session-specific mpv events.

#include <mpv/client.h>

#include <optional>
#include <string>
#include <utility>
#include <vector>

// Client-message name used by the keybind commands installed for the session.
inline constexpr const char *kLinuxHdrKeyMessage = "nuvio-hdr-key";

// mpv_observe_property reply ids used only by the HDR session. 1 is the shared
// video-params observer in player_bridge.cpp.
inline constexpr uint64_t kLinuxHdrTargetParamsReply = 2;
inline constexpr uint64_t kLinuxHdrVoConfiguredReply = 3;

// Whether the loaded libmpv was compiled with the Wayland Vulkan context. Creates
// an uninitialised handle only: no VO, window, display connection or thread is
// started. mpv validates gpu-context entries against its compiled context list,
// so a libmpv without Wayland rejects "waylandvk" here.
bool linuxLibmpvSupportsWaylandVk();

// "NAME=CODE" (split at the last '='; NAME may itself be '='). CODE is an integer
// owned by the application. Rejects names mpv's command parser could misread.
std::optional<std::pair<std::string, int>> parseLinuxHdrKeyBinding(const std::string &entry);

// The mpv input command bound to one key: a broadcast script-message carrying only
// the integer code, so no key name or user text is ever re-parsed by mpv.
std::string linuxHdrKeyBindingCommand(int code);

// Installs user-level bindings after mpv_initialize. Returns how many were accepted.
int installLinuxHdrKeyBindings(mpv_handle *mpv, const std::vector<std::string> &entries);

// The application code carried by a session key client message, if this is one.
std::optional<int> linuxHdrKeyCode(const mpv_event &event);

// "linuxHdrTarget/<gamma>/<primaries>" for a video-target-params node snapshot;
// empty fields when mpv has not rendered to a target yet.
std::string linuxHdrTargetEvent(const mpv_node *params);

// True for error-level VO/context failures, e.g. a missing Wayland display or an
// unusable Vulkan surface. Only meaningful while the session is still starting.
bool linuxHdrVoFailureLog(const mpv_event &event);
