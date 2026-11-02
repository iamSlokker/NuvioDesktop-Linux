// mpv must accept and keep Windows' HDR baseline as written, and take the HDR mode's target
// properties at run time (they are written from Kotlin during playback). A bad value here would only
// print a line to stderr while the picture quietly stayed on mpv's defaults. Real libmpv with null
// outputs: this says nothing about how the picture looks, which needs a screen.
#include "player_session_options.h"
#include <mpv/client.h>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <stdexcept>

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const std::string &operation) {
    require(result >= 0, operation + ": " + mpv_error_string(result));
}
std::string option(mpv_handle *mpv, const std::string &name) {
    char *value = mpv_get_property_string(mpv, ("options/" + name).c_str());
    require(value != nullptr, "read " + name);
    std::string result(value);
    mpv_free(value);
    return result;
}
bool sameNumber(const std::string &left, const std::string &right) {
    char *leftEnd = nullptr, *rightEnd = nullptr;
    const double a = std::strtod(left.c_str(), &leftEnd), b = std::strtod(right.c_str(), &rightEnd);
    // mpv stores these as floats and prints 99.8 back as 99.800003.
    return !*leftEnd && !*rightEnd && leftEnd != left.c_str() && std::fabs(a - b) < 1e-4;
}
bool same(const std::string &left, const std::string &right) { return left == right || sameNumber(left, right); }

using Player = std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)>;
Player create() {
    Player mpv(mpv_create(), mpv_terminate_destroy);
    require(bool(mpv), "mpv_create");
    for (const auto &[name, value] : LinuxMpvOptionList{{"config", "no"}, {"load-scripts", "no"}, {"vo", "null"},
             {"ao", "null"}, {"terminal", "no"}, {"idle", "yes"}})
        check(mpv_set_option_string(mpv.get(), name.c_str(), value.c_str()), name);
    return mpv;
}
}

int main() {
    try {
        {
            auto mpv = create();
            for (const auto &[name, value] : linuxToneMappingDefaults())
                check(mpv_set_option_string(mpv.get(), name.c_str(), value.c_str()), name);
            check(mpv_initialize(mpv.get()), "initialize");
            for (const auto &[name, value] : linuxToneMappingDefaults())
                require(same(option(mpv.get(), name), value), name + " kept " + option(mpv.get(), name));
            std::cout << "PASS mpv keeps the HDR baseline as written\n";
        }
        {
            // Pins why the list leaves it out: remove this and the comment once mpv has it again.
            auto mpv = create();
            require(mpv_set_option_string(mpv.get(), "tone-mapping-mode", "hybrid") == MPV_ERROR_OPTION_NOT_FOUND,
                "tone-mapping-mode exists again: add it to linuxToneMappingDefaults");
            std::cout << "PASS tone-mapping-mode does not exist in this mpv\n";
        }
        {
            // desktopHdrTargetMpvProperties in PlayerEngine.desktop.kt, as written while playing.
            auto mpv = create();
            check(mpv_initialize(mpv.get()), "initialize");
            const LinuxMpvOptionList modes[] = {
                {{"target-colorspace-hint", "auto"}, {"target-colorspace-hint-mode", "target"}, {"target-prim", "auto"},
                 {"target-trc", "auto"}, {"target-peak", "auto"}, {"target-contrast", "auto"}},
                {{"target-colorspace-hint", "yes"}, {"target-colorspace-hint-mode", "target"}, {"target-prim", "bt.709"},
                 {"target-trc", "bt.1886"}, {"target-peak", "203"}, {"target-contrast", "1000"}},
                {{"target-contrast", "inf"}},
            };
            for (const auto &mode : modes) {
                for (const auto &[name, value] : mode) {
                    check(mpv_set_property_string(mpv.get(), name.c_str(), value.c_str()), name);
                    require(same(option(mpv.get(), name), value), name + " kept " + option(mpv.get(), name));
                }
            }
            std::cout << "PASS the HDR mode's targets are accepted at run time\n";
        }
        std::cout << "TONE_MAPPING_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
