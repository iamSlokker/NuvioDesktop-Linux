// mpv must take Windows' stats size and shader cache options as written. Real libmpv with null
// outputs: whether files appear in the cache folder needs a real gpu-next window.
#include "player_session_options.h"
#include <mpv/client.h>
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
}

int main() {
    try {
        std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)> player(mpv_create(), mpv_terminate_destroy);
        mpv_handle *mpv = player.get();
        require(mpv != nullptr, "mpv_create");
        for (const auto &[name, value] : LinuxMpvOptionList{{"config", "no"}, {"load-scripts", "no"}, {"vo", "null"},
                 {"ao", "null"}, {"terminal", "no"}, {"idle", "yes"}})
            check(mpv_set_option_string(mpv, name.c_str(), value.c_str()), name);
        for (const auto &[name, value] : linuxStartupDefaults())
            check(mpv_set_option_string(mpv, name.c_str(), value.c_str()), name);
        // Kotlin's linuxShaderCacheOption, with a space because a home folder can have one.
        check(mpv_set_option_string(mpv, "gpu-shader-cache-dir", "/data/My Nuvio/shader-cache"), "gpu-shader-cache-dir");
        check(mpv_initialize(mpv), "initialize");
        require(option(mpv, "gpu-shader-cache") == "yes", "shader cache on");
        require(option(mpv, "gpu-shader-cache-dir") == "/data/My Nuvio/shader-cache", "cache folder kept whole");
        const std::string scripts = option(mpv, "script-opts");
        require(scripts.find("stats-font_size=15") != std::string::npos, "stats font size: " + scripts);
        require(scripts.find("stats-border_size=1.25") != std::string::npos, "stats border: " + scripts);
        std::cout << "STARTUP_DEFAULTS_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
