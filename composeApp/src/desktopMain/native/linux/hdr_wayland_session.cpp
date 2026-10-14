#include "hdr_wayland_session.h"

#include <climits>
#include <cstring>

bool linuxLibmpvSupportsWaylandVk() {
    mpv_handle *mpv = mpv_create();
    if (!mpv) return false;
    const bool supported = mpv_set_option_string(mpv, "gpu-context", "waylandvk") >= 0;
    // Never initialised: no VO, input, audio or display state exists to tear down.
    mpv_terminate_destroy(mpv);
    return supported;
}

std::optional<std::pair<std::string, int>> parseLinuxHdrKeyBinding(const std::string &entry) {
    const auto equals = entry.rfind('=');
    if (equals == std::string::npos || equals == 0 || equals + 1 >= entry.size()) return std::nullopt;
    const std::string name = entry.substr(0, equals);
    const std::string number = entry.substr(equals + 1);
    if (name.size() > 32) return std::nullopt;
    for (unsigned char c : name) {
        // Key names are passed as a separate argument, but keep them to printable
        // ASCII without whitespace/quotes so a binding can never become a command.
        if (c <= 0x20 || c >= 0x7f || c == '"' || c == '\'' || c == '\\' || c == '#') return std::nullopt;
    }
    long value = 0;
    size_t index = 0;
    bool negative = false;
    if (number[0] == '-') { negative = true; index = 1; }
    if (index >= number.size() || number.size() - index > 6) return std::nullopt;
    for (; index < number.size(); ++index) {
        if (number[index] < '0' || number[index] > '9') return std::nullopt;
        value = value * 10 + (number[index] - '0');
    }
    return std::make_pair(name, static_cast<int>(negative ? -value : value));
}

std::string linuxHdrKeyBindingCommand(int code) {
    return std::string("script-message ") + kLinuxHdrKeyMessage + " " + std::to_string(code);
}

int installLinuxHdrKeyBindings(mpv_handle *mpv, const std::vector<std::string> &entries) {
    int installed = 0;
    for (const auto &entry : entries) {
        const auto binding = parseLinuxHdrKeyBinding(entry);
        if (!binding) continue;
        const std::string command = linuxHdrKeyBindingCommand(binding->second);
        const char *args[] = {"keybind", binding->first.c_str(), command.c_str(), nullptr};
        if (mpv_command(mpv, args) >= 0) ++installed;
    }
    return installed;
}

std::optional<int> linuxHdrKeyCode(const mpv_event &event) {
    if (event.event_id != MPV_EVENT_CLIENT_MESSAGE || !event.data) return std::nullopt;
    const auto *message = static_cast<const mpv_event_client_message *>(event.data);
    if (message->num_args != 2 || !message->args || !message->args[0] || !message->args[1] ||
        std::strcmp(message->args[0], kLinuxHdrKeyMessage) != 0) return std::nullopt;
    const auto parsed = parseLinuxHdrKeyBinding(std::string("k=") + message->args[1]);
    if (!parsed) return std::nullopt;
    return parsed->second;
}

std::string linuxHdrTargetEvent(const mpv_node *params) {
    auto field = [params](const char *name) -> std::string {
        if (!params || params->format != MPV_FORMAT_NODE_MAP || !params->u.list) return {};
        const mpv_node_list &list = *params->u.list;
        if (!list.keys || !list.values) return {};
        for (int i = 0; i < list.num; ++i) {
            if (!list.keys[i] || std::strcmp(list.keys[i], name) != 0) continue;
            const mpv_node &value = list.values[i];
            if (value.format != MPV_FORMAT_STRING || !value.u.string) return {};
            std::string text = value.u.string;
            // Event names are '/'-separated; mpv colour names never contain one.
            for (char &c : text) if (c == '/' || static_cast<unsigned char>(c) < 0x20) c = '_';
            return text;
        }
        return {};
    };
    return "linuxHdrTarget/" + field("gamma") + "/" + field("primaries");
}

bool linuxHdrVoFailureLog(const mpv_event &event) {
    if (event.event_id != MPV_EVENT_LOG_MESSAGE || !event.data) return false;
    const auto *log = static_cast<const mpv_event_log_message *>(event.data);
    if (log->log_level > MPV_LOG_LEVEL_ERROR || !log->prefix) return false;
    const std::string prefix = log->prefix;
    if (prefix == "vo" || prefix.rfind("vo/", 0) == 0) return true;
    return prefix == "cplayer" && log->text && std::strstr(log->text, "video_out");
}
