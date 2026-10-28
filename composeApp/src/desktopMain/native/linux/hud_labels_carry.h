#pragma once

// The player HUD's own text (HudLabels.kt) rides once per page, at the start of a controls payload:
// {"hudLabels":{...},...}. The Linux overlay keeps only the latest payload until the page is ready
// (and coalesces later ones), so a payload with labels is often replaced by one without before
// anything is delivered, and the HUD would stay in English. The overlay keeps the labels object
// aside and delivers it on its own when the payload that does get delivered has none.

#include <cstddef>
#include <string>
#include <string_view>

// The {"..."} object of a payload that begins with "hudLabels", or empty for any other payload.
inline std::string linuxHudLabelsObject(std::string_view json) {
    constexpr std::string_view prefix = "{\"hudLabels\":";
    if (json.substr(0, prefix.size()) != prefix) return {};
    const size_t begin = prefix.size();
    if (begin >= json.size() || json[begin] != '{') return {};
    int depth = 0;
    bool inString = false;
    bool escaped = false;
    for (size_t i = begin; i < json.size(); ++i) {
        const char c = json[i];
        if (inString) {
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') inString = false;
            continue;
        }
        if (c == '"') inString = true;
        else if (c == '{') ++depth;
        else if (c == '}' && --depth == 0) return std::string(json.substr(begin, i - begin + 1));
    }
    return {};
}
