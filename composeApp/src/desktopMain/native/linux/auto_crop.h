#pragma once

// Auto crop (PlayerResizeMode.AutoCrop, resize modes 3 and 4) for the Linux bridge: the black bars baked
// into a frame are measured from screenshot-raw grabs, trimmed with video-crop, and panscan=1
// fills the window with what is left, as Zoom does for a frame without bars. Ported from the
// Windows bridge (native/windows/player_bridge.cpp, "Auto crop"): detectAutoCropBars is copied as
// is, and the learning rules are the same; only mpv access goes through Host so the rules can be
// tested without a player. Callers serialise every call (the Linux bridge holds Player::operations).

#include <mpv/client.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <string>

// Auto crop: how much of each edge of one captured frame is black bar, as fractions of the frame.
struct AutoCropBars {
    double top = 0.0, bottom = 0.0, left = 0.0, right = 0.0;
};

// Finds the black bars baked into one packed frame from screenshot-raw (bytesPerPixel 4 for
// bgr0/rgb0-style formats, 8 for rgba64). Each edge is walked inwards until a row/column has
// more than a couple of bright samples (logos and noise are tolerated). Bars are made symmetric
// (only what both sides agree on is trimmed) and hairlines are dropped. Returns false when the
// frame cannot be judged, e.g. a fade to black.
inline bool detectAutoCropBars(const uint8_t *data, int64_t width, int64_t height, int64_t stride,
                               int bytesPerPixel, AutoCropBars &out) {
    constexpr int threshold = 16;  // 0-255; at or below counts as black
    constexpr int samples = 64;    // probes per row/column, across the middle 90% of the other axis
    constexpr int stray = 2;       // bright probes tolerated per row/column
    constexpr double minimumBar = 0.012;
    if (!data || width < 64 || height < 64 || stride < width * bytesPerPixel) return false;

    auto bright = [&](int64_t x, int64_t y) {
        const uint8_t *pixel = data + y * stride + x * bytesPerPixel;
        if (bytesPerPixel == 8) {
            // 16-bit little-endian channels: the high byte is enough at this threshold.
            return pixel[1] > threshold || pixel[3] > threshold || pixel[5] > threshold;
        }
        // Channel order doesn't matter: any of the three colour bytes being bright is picture.
        return pixel[0] > threshold || pixel[1] > threshold || pixel[2] > threshold;
    };
    auto probe = [](int64_t span, int index) {
        return (int64_t)(span * (0.05 + 0.90 * (index + 0.5) / samples));
    };
    auto rowHasPicture = [&](int64_t y) {
        int hits = 0;
        for (int i = 0; i < samples; ++i) {
            if (bright(probe(width, i), y) && ++hits > stray) return true;
        }
        return false;
    };
    auto columnHasPicture = [&](int64_t x, int64_t top, int64_t bottom) {
        int hits = 0;
        const int64_t span = bottom - top;
        for (int i = 0; i < samples; ++i) {
            const int64_t y = top + (int64_t)(span * (i + 0.5) / samples);
            if (bright(x, y) && ++hits > stray) return true;
        }
        return false;
    };

    int64_t top = 0;
    while (top < height && !rowHasPicture(top)) ++top;
    if (top >= height) return false;
    int64_t bottom = height;
    while (bottom > top && !rowHasPicture(bottom - 1)) --bottom;
    int64_t left = 0;
    while (left < width && !columnHasPicture(left, top, bottom)) ++left;
    if (left >= width) return false;
    int64_t right = width;
    while (right > left && !columnHasPicture(right - 1, top, bottom)) --right;

    double vertical = std::min((double)top / height, (double)(height - bottom) / height);
    double horizontal = std::min((double)left / width, (double)(width - right) / width);
    if (vertical < minimumBar) vertical = 0.0;
    if (horizontal < minimumBar) horizontal = 0.0;
    out.top = out.bottom = vertical;
    out.left = out.right = horizontal;
    return true;
}

// Grabs the current video frame (screenshot-raw "video": no OSD/subtitles, after filters) and
// measures its bars. The caller owns mpv's lifetime for the duration of the call.
inline bool captureAutoCropBars(mpv_handle *mpv, AutoCropBars &bars, int64_t &grabWidth, int64_t &grabHeight) {
    char commandName[] = "screenshot-raw";
    char commandFlags[] = "video";
    mpv_node items[2];
    items[0].format = MPV_FORMAT_STRING;
    items[0].u.string = commandName;
    items[1].format = MPV_FORMAT_STRING;
    items[1].u.string = commandFlags;
    mpv_node_list list{2, items, nullptr};
    mpv_node args;
    args.format = MPV_FORMAT_NODE_ARRAY;
    args.u.list = &list;
    mpv_node result{};
    const int error = mpv_command_node(mpv, &args, &result);
    if (error < 0) {
        std::fprintf(stderr, "Linux auto crop: screenshot-raw failed: %s\n", mpv_error_string(error));
        return false;
    }
    int64_t width = 0, height = 0, stride = 0;
    std::string format;
    const uint8_t *data = nullptr;
    if (result.format == MPV_FORMAT_NODE_MAP && result.u.list) {
        for (int i = 0; i < result.u.list->num; ++i) {
            const std::string key = result.u.list->keys[i];
            const mpv_node &value = result.u.list->values[i];
            if (key == "w" && value.format == MPV_FORMAT_INT64) width = value.u.int64;
            else if (key == "h" && value.format == MPV_FORMAT_INT64) height = value.u.int64;
            else if (key == "stride" && value.format == MPV_FORMAT_INT64) stride = value.u.int64;
            else if (key == "format" && value.format == MPV_FORMAT_STRING && value.u.string) format = value.u.string;
            else if (key == "data" && value.format == MPV_FORMAT_BYTE_ARRAY && value.u.ba)
                data = static_cast<const uint8_t *>(value.u.ba->data);
        }
    }
    const int bytesPerPixel = format == "rgba64" ? 8
        : (format == "bgr0" || format == "bgra" || format == "rgb0" || format == "rgba") ? 4 : 0;
    bool judged = false;
    if (bytesPerPixel > 0) judged = detectAutoCropBars(data, width, height, stride, bytesPerPixel, bars);
    else std::fprintf(stderr, "Linux auto crop: unsupported screenshot format \"%s\"\n", format.c_str());
    mpv_free_node_contents(&result);
    grabWidth = width;
    grabHeight = height;
    return judged;
}

// video-crop; an empty value is mpv's "no crop", and the zero rect only covers builds without it.
inline void setAutoCropVideoCrop(mpv_handle *mpv, const std::string &crop) {
    int error = mpv_set_property_string(mpv, "video-crop", crop.c_str());
    if (error < 0 && crop.empty()) error = mpv_set_property_string(mpv, "video-crop", "0x0+0+0");
    if (error < 0) std::fprintf(stderr, "Linux auto crop: setting video-crop failed: %s\n", mpv_error_string(error));
}

class LinuxAutoCrop {
public:
    using Clock = std::chrono::steady_clock;

    // What the rules need from the player.
    struct Host {
        virtual ~Host() = default;
        virtual bool pausedOrSeeking() = 0;
        // The decoder's display size (video-params/dw, dh): unlike dwidth/dheight it isn't
        // changed by video-crop.
        virtual double displayWidth() = 0;
        virtual double displayHeight() = 0;
        // The frame video-crop applies to (video-out-params/w, h, else video-params/w, h).
        virtual int64_t frameWidth() = 0;
        virtual int64_t frameHeight() = 0;
        // Grabs the current video frame (no OSD/subtitles, after filters) and measures its bars.
        virtual bool capture(AutoCropBars &bars, int64_t &grabWidth, int64_t &grabHeight) = 0;
        // video-crop; empty means no crop.
        virtual void setCrop(const std::string &crop) = 0;
        virtual bool titleTaggedImax() = 0;
        virtual void log(const std::string &line) = 0;
    };

    explicit LinuxAutoCrop(Host &host) : host(host) {}

    bool enabled() const { return isEnabled; }
    bool locked() const { return isLocked; }
    const std::string &appliedCrop() const { return applied; }

    // Resize mode 3 or 4 selected (true) or any other mode (false). `quick` is mode 4, the hero
    // trailer's: same crop, decided in about two seconds.
    void setEnabled(bool enable, Clock::time_point now, bool quickDecision = false) {
        if (enable) {
            quick = quickDecision;
            if (isEnabled) return;
            isEnabled = true;
            reset();
            nextCheckAt = now + (quick ? std::chrono::milliseconds(0) : std::chrono::milliseconds(500));
            host.log(quick ? "auto crop enabled (quick)" : "auto crop enabled");
            return;
        }
        quick = false;
        if (!isEnabled) return;
        isEnabled = false;
        reset();
        host.log("auto crop disabled");
    }

    // A new file starts learning from scratch (video-crop is a global option, so a crop learned
    // for the previous file would otherwise carry over).
    void fileLoaded(Clock::time_point now) {
        reset();
        // Quick: start as soon as there is a frame (a check without one just waits for the next).
        nextCheckAt = now + (quick ? kQuickInterval : std::chrono::milliseconds(2000));
    }

    // After a seek the scene (and, while still learning, the bars) may differ. Also the first frame
    // of a file, which is when a quick crop starts looking.
    void playbackRestart(Clock::time_point now) {
        if (!isEnabled) return;
        const auto soon = now + (quick ? std::chrono::milliseconds(0) : std::chrono::milliseconds(700));
        if (soon < nextCheckAt) nextCheckAt = soon;
    }

    // Runs a check once one is due. Cheap enough for every event-loop tick.
    void tick(Clock::time_point now) {
        if (!isEnabled || now < nextCheckAt) return;
        check(now);
    }

private:
    struct Sample {
        Clock::time_point at;
        AutoCropBars bars;
    };

    static constexpr std::chrono::milliseconds kQuickInterval{400};
    static constexpr size_t kQuickSamples = 4;
    static constexpr double kQuickTolerance = 0.015;

    Host &host;
    bool isEnabled = false;
    bool quick = false;
    Clock::time_point nextCheckAt{};
    std::deque<Sample> history;
    AutoCropBars appliedBars;
    std::string applied; // video-crop value, empty = none
    int64_t appliedFrameWidth = 0;
    int64_t appliedFrameHeight = 0;
    int learnChecks = 0;
    int attempts = 0;
    bool isLocked = false;
    bool variableAspect = false;
    double referenceAspect = 0.0;
    int aspectChanges = 0;

    void reset() {
        history.clear();
        appliedBars = AutoCropBars{};
        learnChecks = 0;
        attempts = 0;
        isLocked = false;
        referenceAspect = 0.0;
        aspectChanges = 0;
        variableAspect = isEnabled && host.titleTaggedImax();
        if (variableAspect) host.log("auto crop: IMAX tag in title, checking continuously");
        if (!applied.empty()) {
            applied.clear();
            host.setCrop(std::string());
        }
        appliedFrameWidth = 0;
        appliedFrameHeight = 0;
    }

    void check(Clock::time_point now) {
        // Once locked, a grab every 30 s that can only loosen the crop: a film that opens up to a
        // taller picture after the learning minute (IMAX sequences) gets those scenes back uncut,
        // while a dark or black frame can't tighten anything.
        const bool loosenOnly = isLocked && !variableAspect;
        nextCheckAt = now + (loosenOnly ? std::chrono::milliseconds(30000)
            : quick ? kQuickInterval : std::chrono::milliseconds(3000));
        if (host.pausedOrSeeking()) {
            nextCheckAt = now + (quick ? kQuickInterval : std::chrono::milliseconds(1000));
            return;
        }
        const double frameWidth = host.displayWidth();
        const double frameHeight = host.displayHeight();
        if (frameWidth <= 0.0 || frameHeight <= 0.0) return;

        // A film too dark to ever give 20 usable frames still stops grabbing after ~2 minutes,
        // keeping whatever it has applied by then.
        if (!loosenOnly && !variableAspect && ++attempts > 40) {
            isLocked = true;
            host.log("auto crop: too few usable frames, locked as applied");
            return;
        }
        AutoCropBars measured;
        int64_t grabWidth = 0, grabHeight = 0;
        if (!host.capture(measured, grabWidth, grabHeight)) return; // unjudgeable frame: keep whatever is applied
        const AutoCropBars bars = barsOfFullFrame(measured, grabWidth, grabHeight);
        const double frameAspect = frameWidth / frameHeight;
        const double visibleWidth = 1.0 - bars.left - bars.right;
        const double visibleHeight = 1.0 - bars.top - bars.bottom;
        const double visibleAspect = frameAspect * visibleWidth / visibleHeight;
        // Too little picture, or wider than any film: a dark scene rather than bars.
        if (visibleWidth * visibleHeight < 0.40 || visibleAspect > 2.9) {
            host.log("auto crop: check " + std::to_string(attempts) + " ignored as a dark scene (picture " +
                std::to_string(visibleAspect) + ":1)");
            return;
        }
        if (loosenOnly) {
            // A clear margin, so a frame that differs by a row or two doesn't nudge the crop.
            if (bars.top + 0.01 < appliedBars.top || bars.left + 0.01 < appliedBars.left) {
                AutoCropBars loosened = appliedBars;
                loosened.top = loosened.bottom = std::min(loosened.top, bars.top);
                loosened.left = loosened.right = std::min(loosened.left, bars.left);
                host.log("auto crop: taller picture seen after lock (" + std::to_string(visibleAspect) +
                    ":1), loosening crop");
                apply(loosened);
            }
            return;
        }

        // A film whose picture keeps changing shape while learning is variable-aspect (IMAX).
        if (!isLocked && !variableAspect) {
            if (referenceAspect <= 0.0) {
                referenceAspect = visibleAspect;
            } else if (std::fabs(visibleAspect / referenceAspect - 1.0) > 0.15 && ++aspectChanges >= 3) {
                variableAspect = true;
                host.log("auto crop: variable aspect ratio (" + std::to_string(visibleAspect) + " vs " +
                    std::to_string(referenceAspect) + "), checking continuously");
            }
        }

        host.log("auto crop: check " + std::to_string(attempts) + " bars top/bottom " +
            std::to_string(bars.top * 100.0) + "% sides " + std::to_string(bars.left * 100.0) +
            "% (picture " + std::to_string(visibleAspect) + ":1)");
        history.push_back({now, bars});
        // Learning keeps every sample; continuous checking trusts only the last few seconds.
        if (variableAspect) {
            while (!history.empty() && now - history.front().at > std::chrono::seconds(8)) history.pop_front();
        }
        // The smallest bars seen are the safe estimate: bright pixels prove picture is there,
        // while a dark scene can only ever make bars look bigger.
        AutoCropBars smallest{1.0, 1.0, 1.0, 1.0};
        for (const Sample &sample : history) {
            smallest.top = std::min(smallest.top, sample.bars.top);
            smallest.bottom = std::min(smallest.bottom, sample.bars.bottom);
            smallest.left = std::min(smallest.left, sample.bars.left);
            smallest.right = std::min(smallest.right, sample.bars.right);
        }
        bool ready = history.size() >= 3 && now - history.front().at >= std::chrono::seconds(5);
        if (quick && !variableAspect) {
            // The trailer rule: the last four samples agree to within 1.5% of the frame, and the
            // crop is the smallest of those four. A trailer is two minutes long; five seconds of
            // learning was most of what someone watched of it zoomed out.
            ready = false;
            smallest = AutoCropBars{1.0, 1.0, 1.0, 1.0};
            if (history.size() >= kQuickSamples) {
                AutoCropBars largest{};
                for (auto it = history.end() - (std::ptrdiff_t)kQuickSamples; it != history.end(); ++it) {
                    smallest.top = std::min(smallest.top, it->bars.top);
                    smallest.left = std::min(smallest.left, it->bars.left);
                    largest.top = std::max(largest.top, it->bars.top);
                    largest.left = std::max(largest.left, it->bars.left);
                }
                smallest.bottom = smallest.top;
                smallest.right = smallest.left;
                ready = largest.top - smallest.top <= kQuickTolerance && largest.left - smallest.left <= kQuickTolerance;
            }
            // Not settled: the newest sample may still zoom out (the block below never zooms in).
            if (!ready) smallest = history.back().bars;
        }
        AutoCropBars target = smallest;
        if (!ready) {
            // Too little history to zoom in yet, but zooming out is always allowed.
            target.top = std::min(target.top, appliedBars.top);
            target.bottom = std::min(target.bottom, appliedBars.bottom);
            target.left = std::min(target.left, appliedBars.left);
            target.right = std::min(target.right, appliedBars.right);
        }
        // Quick decides once; the 30 s loosen-only checks still give a trailer that opens up to a
        // taller picture its scenes back.
        if (!variableAspect && (++learnChecks >= 20 || quick) && ready) {
            isLocked = true;
            host.log("auto crop: bars locked top/bottom " + std::to_string(smallest.top * 100.0) + "% sides " +
                std::to_string(smallest.left * 100.0) + "%, frame checks stopped");
        }
        apply(target);
    }

    // Trims the bars off the frame itself with video-crop; panscan=1 then fills the window with
    // what is left. Cropping (rather than video-zoom) keeps the video rectangle on screen, which
    // is what subtitles are placed in, and lets mpv re-fit on window resizes by itself.
    void apply(const AutoCropBars &bars) {
        const int64_t frameWidth = host.frameWidth();
        const int64_t frameHeight = host.frameHeight();
        if (frameWidth <= 0 || frameHeight <= 0) return;
        const int64_t x = (int64_t)std::llround(frameWidth * bars.left);
        const int64_t y = (int64_t)std::llround(frameHeight * bars.top);
        const int64_t width = frameWidth - 2 * x;
        const int64_t height = frameHeight - 2 * y;
        if (width <= 0 || height <= 0) return;
        const std::string crop = (x == 0 && y == 0) ? std::string()
            : std::to_string(width) + "x" + std::to_string(height) + "+" + std::to_string(x) + "+" + std::to_string(y);
        appliedBars = bars;
        if (crop == applied) return;
        applied = crop;
        appliedFrameWidth = frameWidth;
        appliedFrameHeight = frameHeight;
        host.setCrop(crop);
        host.log("auto crop: video-crop=" + (crop.empty() ? std::string("none") : crop) + " for bars top/bottom " +
            std::to_string(bars.top * 100.0) + "% sides " + std::to_string(bars.left * 100.0) + "% of " +
            std::to_string(frameWidth) + "x" + std::to_string(frameHeight));
    }

    // Bars measured on a grab, as fractions of the full frame. Whether screenshot-raw returns
    // the frame before or after video-crop isn't something to rely on, so it is told apart by
    // size: a grab the size of the applied crop shows only what is left, and its bars are added
    // to the ones already trimmed.
    AutoCropBars barsOfFullFrame(const AutoCropBars &measured, int64_t grabWidth, int64_t grabHeight) const {
        if (applied.empty() || appliedFrameWidth <= 0 || appliedFrameHeight <= 0) return measured;
        const double croppedWidth = appliedFrameWidth * (1.0 - 2.0 * appliedBars.left);
        const double croppedHeight = appliedFrameHeight * (1.0 - 2.0 * appliedBars.top);
        const bool grabIsCropped = std::llabs(grabWidth - (int64_t)std::llround(croppedWidth)) <= 2 &&
            std::llabs(grabHeight - (int64_t)std::llround(croppedHeight)) <= 2 &&
            (grabWidth != appliedFrameWidth || grabHeight != appliedFrameHeight);
        if (!grabIsCropped) return measured;
        const double keptHeight = 1.0 - 2.0 * appliedBars.top;
        const double keptWidth = 1.0 - 2.0 * appliedBars.left;
        AutoCropBars full;
        full.top = full.bottom = appliedBars.top + measured.top * keptHeight;
        full.left = full.right = appliedBars.left + measured.left * keptWidth;
        return full;
    }
};
