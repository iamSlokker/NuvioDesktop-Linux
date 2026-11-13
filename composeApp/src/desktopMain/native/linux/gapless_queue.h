#pragma once

// fork:gapless (Linux) - the next music track queued behind the one playing, in the same mpv, as the
// Windows bridge's queueNext does: mpv opens it near the end of this one (prefetch-playlist) and starts
// it the moment this one ends. All calls are made under the player's operation lock.

#include <mpv/client.h>
#include <string>
#include <vector>

// "name=value,..." per-file options for loadfile: the request headers (commas and backslashes escaped,
// as mpv's string list wants them) and the caller's "name=value" options. Values are length-quoted
// (%n%), so commas and brackets inside them stay in them.
inline std::string linuxGaplessPerFileOptions(const std::vector<std::string> &headerLines,
                                              const std::vector<std::string> &options) {
    std::string perFile;
    auto add = [&perFile](const std::string &name, const std::string &value) {
        if (name.empty()) return;
        if (!perFile.empty()) perFile.push_back(',');
        perFile += name + "=%" + std::to_string(value.size()) + "%" + value;
    };
    if (!headerLines.empty()) {
        std::string headers;
        for (size_t index = 0; index < headerLines.size(); ++index) {
            if (index > 0) headers.push_back(',');
            for (char character : headerLines[index]) {
                if (character == '\\' || character == ',') headers.push_back('\\');
                headers.push_back(character);
            }
        }
        add("http-header-fields", headers);
    }
    for (const auto &option : options) {
        const size_t equals = option.find('=');
        if (equals == std::string::npos) continue;
        add(option.substr(0, equals), option.substr(equals + 1));
    }
    return perFile;
}

class LinuxGaplessQueue {
public:
    // Drops whatever was queued, then queues url (an empty url only drops it). Returns mpv's result.
    int queue(mpv_handle *mpv, const std::string &url, const std::vector<std::string> &headerLines,
              const std::vector<std::string> &options) {
        const char *clear[] = {"playlist-clear", nullptr};
        mpv_command(mpv, clear);
        queued_ = false;
        if (url.empty()) return 0;
        mpv_set_property_string(mpv, "prefetch-playlist", "yes");
        mpv_set_property_string(mpv, "gapless-audio", "weak");
        const std::string perFile = linuxGaplessPerFileOptions(headerLines, options);
        // mpv 0.38+ takes an index before the options; older builds take the options third.
        const char *withIndex[] = {"loadfile", url.c_str(), "append", "-1", perFile.c_str(), nullptr};
        int result = mpv_command(mpv, withIndex);
        if (result < 0) {
            const char *withoutIndex[] = {"loadfile", url.c_str(), "append", perFile.c_str(), nullptr};
            result = mpv_command(mpv, withoutIndex);
        }
        queued_ = result >= 0;
        return result;
    }

    // An end of file while a track is queued is the player moving on to it, not playback ending.
    // Returns true (and forgets the queue) for that one EOF.
    bool takesEof() {
        if (!queued_) return false;
        queued_ = false;
        return true;
    }

    bool queued() const { return queued_; }

private:
    bool queued_ = false;
};
