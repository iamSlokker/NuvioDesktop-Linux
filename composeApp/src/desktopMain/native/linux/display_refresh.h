#pragma once

#include <algorithm>
#include <cmath>
#include <optional>
#include <string>
#include <vector>

// Settings > Playback > Match display refresh rate on Linux (upstream's DisplayRefreshMatch.kt,
// NativePlayerBridge.displayRefreshRates / setDisplayRefreshRate). GNOME's Wayland session
// switches through Mutter's DisplayConfig (a temporary configuration, never saved), a real X11
// session through XRandR; anything else (KDE, wlroots, Xwayland without Mutter) offers no rates
// and the display is left alone.
namespace nuvio::refresh {

// Windows lists whole numbers, and DisplayRefreshMatch.kt reads them that way: 24.000 as 24 but
// the NTSC family one lower (23.976 as 23, 59.94 as 59), which effectiveRefreshHz turns back into
// n / 1.001. Linux reports the real rate, so it is listed the same way.
inline int listedHz(double hz) {
    if (!std::isfinite(hz) || hz <= 1.0) return 0;
    const double whole = std::round(hz);
    if (std::fabs(hz - whole) <= 0.01) return static_cast<int>(whole);
    const double ntsc = std::round(hz * 1.001);
    if (ntsc >= 24.0 && std::fabs(hz * 1.001 - ntsc) <= 0.01) return static_cast<int>(ntsc) - 1;
    return static_cast<int>(std::floor(hz));
}

struct Mode {
    std::string id;
    int width = 0;
    int height = 0;
    double hz = 0.0;
    bool variable = false; // A VRR mode: only ever traded for another VRR mode.
    bool interlaced = false;
};

inline bool sameShape(const Mode &current, const Mode &mode) {
    return mode.width == current.width && mode.height == current.height &&
           mode.variable == current.variable && !mode.interlaced;
}

// As the Windows bridge returns them: element 0 is the current rate, the rest are the rates the
// same resolution offers, each once.
inline std::vector<int> offeredRates(const Mode &current, const std::vector<Mode> &modes) {
    std::vector<int> rates;
    const int now = listedHz(current.hz);
    if (now <= 1) return rates;
    rates.push_back(now);
    for (const auto &mode : modes) {
        const int hz = listedHz(mode.hz);
        if (sameShape(current, mode) && hz > 1 && std::find(rates.begin() + 1, rates.end(), hz) == rates.end())
            rates.push_back(hz);
    }
    return rates;
}

// The mode of the current resolution listed as [hz]; the current mode itself when it is.
inline std::optional<Mode> modeFor(const Mode &current, const std::vector<Mode> &modes, int hz) {
    if (listedHz(current.hz) == hz) return current;
    for (const auto &mode : modes)
        if (sameShape(current, mode) && listedHz(mode.hz) == hz) return mode;
    return std::nullopt;
}

// The rates for the monitor [window] (an X11 window id) is on; empty when they cannot be read.
std::vector<int> rates(unsigned long window);

// Switches that monitor to [hz] until restored; [hz] 0 restores whichever monitor was switched.
bool set(unsigned long window, int hz);

#ifdef NUVIO_DISPLAY_REFRESH_TESTING
// Forgets this process's switch, as a new run would start (its record stays on disk).
void forgetForTesting();
#endif

}
