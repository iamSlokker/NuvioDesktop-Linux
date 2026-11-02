#pragma once

// Windows' subtitle rules (refreshSubtitleAssOverrideMode, applySubtitleStyle). sub-ass-override and
// sub-scale depend on the selected track: an ASS/SSA track gets what the user asked for, a plain-text
// one stays at "force" and scale 1, or the ASS size control would resize every SRT as well. mpv
// reports the selected codec through current-tracks/sub/codec, observed by the bridge, so every way a
// track gets selected (auto selection at load, the HUD, an addon subtitle landing) is covered.

#include <mpv/client.h>
#include <algorithm>
#include <cstdio>
#include <optional>
#include <string>
#include <utility>
#include <vector>

inline bool linuxSubtitleCodecIsAss(const std::string &codec) {
    return codec.find("ass") != std::string::npos || codec.find("ssa") != std::string::npos;
}

class LinuxSubtitleAssMode {
public:
    struct Values {
        std::string mode;
        double scale;
    };

    // False for a mode mpv does not have or nothing new: the current values stay.
    bool request(const std::string &mode, double scale) {
        if (mode != "no" && mode != "yes" && mode != "scale" && mode != "force") return false;
        const double clamped = std::clamp(scale, 0.1, 5.0);
        if (mode == requestedMode_ && clamped == requestedScale_) return false;
        requestedMode_ = mode;
        requestedScale_ = clamped;
        return true;
    }

    // What to write for the selected codec, or nothing when the override level has not changed. A
    // new request forces the write: the scale can change while the level stays the same.
    std::optional<Values> refresh(const std::string &codec, bool force = false) {
        const bool ass = linuxSubtitleCodecIsAss(codec);
        const std::string mode = ass ? requestedMode_ : "force";
        if (!force && mode == appliedMode_) return std::nullopt;
        appliedMode_ = mode;
        return Values{mode, ass ? requestedScale_ : 1.0};
    }

private:
    std::string requestedMode_ = "no";
    double requestedScale_ = 1.0;
    std::string appliedMode_; // Empty until the first refresh, so that one always writes.
};

inline std::string linuxSelectedSubtitleCodec(mpv_handle *mpv) {
    char *value = mpv_get_property_string(mpv, "current-tracks/sub/codec");
    if (!value) return {};
    std::string codec(value);
    mpv_free(value);
    return codec;
}

inline void linuxRefreshSubtitleAssMode(mpv_handle *mpv, LinuxSubtitleAssMode &state, bool force = false) {
    const auto values = state.refresh(linuxSelectedSubtitleCodec(mpv), force);
    if (!values) return;
    const std::string scale = std::to_string(values->scale);
    for (const auto &[name, value] : {std::pair<const char *, const std::string &>{"sub-ass-override", values->mode},
                                      std::pair<const char *, const std::string &>{"sub-scale", scale}}) {
        const int result = mpv_set_property_string(mpv, name, value.c_str());
        if (result < 0) std::fprintf(stderr, "Linux mpv property %s: %s\n", name, mpv_error_string(result));
    }
}

struct LinuxSubtitleStyle {
    std::string color;
    std::string background;
    std::string outline;
    double outlineSize = 0.0;
    bool bold = false;
    double fontSize = 0.0;
    int position = 0;
    std::string font;
};

// The sub-* properties for a style, with Windows' fallbacks and limits. Empty colours and an empty
// font name mean "no choice made"; the size floor matches toMpvSubtitleFontSize() in Kotlin.
inline std::vector<std::pair<std::string, std::string>> linuxSubtitleStyleProperties(const LinuxSubtitleStyle &style) {
    const std::string background = style.background.empty() ? "#00000000" : style.background;
    return {
        {"sub-font", style.font.empty() ? "sans-serif" : style.font},
        {"sub-color", style.color.empty() ? "#FFFFFFFF" : style.color},
        {"sub-back-color", background},
        {"sub-outline-color", style.outline.empty() ? "#FF000000" : style.outline},
        // Keyed on what was sent, as on Windows: an empty background leaves the opaque box selected.
        {"sub-border-style", style.background.rfind("#00", 0) == 0 ? "outline-and-shadow" : "opaque-box"},
        {"sub-bold", style.bold ? "yes" : "no"},
        {"sub-outline-size", std::to_string(std::clamp(style.outlineSize, 0.0, 8.0))},
        {"sub-font-size", std::to_string(std::clamp(style.fontSize, 18.0, 96.0))},
        {"sub-pos", std::to_string(std::clamp(style.position, 0, 150))},
    };
}
