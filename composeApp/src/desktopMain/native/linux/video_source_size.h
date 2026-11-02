#pragma once

// Windows' videoSourceSize event, which the "4K HDR" quality label and the stream badges read. Both
// sides ride in one value so Kotlin never observes half a size (DesktopVideoSourceSize.unpack).

#include <cstdint>

// w * 65536 + h, exact in a double; 0 for a size mpv does not know or Kotlin could not unpack.
inline int64_t linuxPackedVideoSize(int64_t width, int64_t height) {
    return width > 0 && width <= 65535 && height > 0 && height <= 65535 ? width * 65536 + height : 0;
}

// A size is worth sending when it is real and new: a brief 0 between two reconfigurations must not
// send the same size again.
class LinuxVideoSizeReport {
public:
    bool changed(int64_t packed) {
        if (packed <= 0 || packed == last_) return false;
        last_ = packed;
        return true;
    }

private:
    int64_t last_ = 0;
};
