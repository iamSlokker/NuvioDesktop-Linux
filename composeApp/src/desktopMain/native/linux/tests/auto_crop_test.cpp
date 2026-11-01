// Auto crop (auto_crop.h): the learning rules against a scripted host, then the production capture
// and video-crop helpers with real libmpv on a letterboxed still image. Null outputs: never
// creates a window, initializes GTK/JNI, accesses the network, or opens an audio device.
#include "auto_crop.h"
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = LinuxAutoCrop::Clock;
using namespace std::chrono_literals;

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

// A packed 4-byte frame: grey picture between rows/columns, black bars elsewhere.
std::vector<uint8_t> frame(int width, int height, int top, int bottom, int left, int right, uint8_t picture = 128) {
    std::vector<uint8_t> data(size_t(width) * height * 4, 0);
    for (int y = top; y < height - bottom; ++y)
        for (int x = left; x < width - right; ++x)
            std::memset(&data[(size_t(y) * width + x) * 4], picture, 3);
    return data;
}

void testDetection() {
    AutoCropBars bars;
    auto scope = frame(1920, 1080, 140, 140, 0, 0);
    require(detectAutoCropBars(scope.data(), 1920, 1080, 1920 * 4, 4, bars), "letterbox judged");
    require(std::abs(bars.top - 140.0 / 1080) < 1e-9 && bars.bottom == bars.top && bars.left == 0.0, "letterbox bars");

    // A channel logo in the bottom bar is tolerated; uneven bars keep only what both sides share.
    auto logo = frame(1920, 1080, 140, 160, 0, 0);
    for (int x = 1700; x < 1720; ++x) std::memset(&logo[(size_t(1050) * 1920 + x) * 4], 255, 3);
    require(detectAutoCropBars(logo.data(), 1920, 1080, 1920 * 4, 4, bars), "logo frame judged");
    require(std::abs(bars.top - 140.0 / 1080) < 1e-9 && bars.bottom == bars.top, "symmetric bars, logo ignored");

    auto pillar = frame(1920, 1080, 0, 0, 240, 240);
    require(detectAutoCropBars(pillar.data(), 1920, 1080, 1920 * 4, 4, bars), "pillarbox judged");
    require(bars.top == 0.0 && std::abs(bars.left - 240.0 / 1920) < 1e-9, "pillarbox bars");

    auto hairline = frame(1920, 1080, 4, 4, 0, 0);
    require(detectAutoCropBars(hairline.data(), 1920, 1080, 1920 * 4, 4, bars) && bars.top == 0.0, "hairlines dropped");

    auto black = frame(1920, 1080, 0, 0, 0, 0, 0);
    require(!detectAutoCropBars(black.data(), 1920, 1080, 1920 * 4, 4, bars), "a black frame is not judged");
}

// Scripted host: the next grab returns `bars`, unless `judged` is false.
struct FakeHost : LinuxAutoCrop::Host {
    AutoCropBars bars;
    bool judged = true;
    bool paused = false;
    std::string title = "film.mkv";
    int64_t grabWidth = 1920, grabHeight = 1080;
    std::vector<std::string> crops;
    int captures = 0;
    bool pausedOrSeeking() override { return paused; }
    double displayWidth() override { return 1920; }
    double displayHeight() override { return 1080; }
    int64_t frameWidth() override { return 1920; }
    int64_t frameHeight() override { return 1080; }
    bool capture(AutoCropBars &out, int64_t &width, int64_t &height) override {
        ++captures;
        out = bars;
        width = grabWidth;
        height = grabHeight;
        return judged;
    }
    void setCrop(const std::string &crop) override { crops.push_back(crop); }
    bool titleTaggedImax() override { return title.find("imax") != std::string::npos; }
    void log(const std::string &) override {}
};

AutoCropBars letterbox(double fraction) {
    AutoCropBars bars;
    bars.top = bars.bottom = fraction;
    return bars;
}

// Ticks every 100 ms like the bridge's event loop, for `duration`.
void run(LinuxAutoCrop &crop, Clock::time_point &now, Clock::duration duration,
         const std::function<void()> &each = {}) {
    for (const auto end = now + duration; now < end; now += 100ms) {
        if (each) each();
        crop.tick(now);
    }
}

void testLearningAppliesThenLocks() {
    FakeHost host;
    host.bars = letterbox(140.0 / 1080);
    LinuxAutoCrop crop(host);
    Clock::time_point now{};
    crop.setEnabled(true, now);
    run(crop, now, 4s);
    require(host.crops.empty(), "no zoom before 3 samples over 5 s");
    run(crop, now, 4s);
    require(host.crops == std::vector<std::string>{"1920x800+0+140"}, "crop applied once learned: " +
        (host.crops.empty() ? std::string("none") : host.crops.back()));
    run(crop, now, 60s);
    require(crop.locked(), "locked after 20 learning checks");
    const int captures = host.captures;
    run(crop, now, 61s);
    require(host.captures == captures + 2, "a locked crop is only re-checked every 30 s: " +
        std::to_string(host.captures - captures) + " grabs in 61 s");
    require(host.crops.size() == 1, "the same crop is not set again");
}

// Mode 4, the hero trailer's: four agreeing samples 400 ms apart decide it, once.
void testQuickDecision() {
    FakeHost host;
    host.bars = letterbox(140.0 / 1080);
    LinuxAutoCrop crop(host);
    Clock::time_point now{};
    crop.setEnabled(true, now, true);
    run(crop, now, 1s);
    require(host.crops.empty(), "no zoom before four samples");
    run(crop, now, 1s);
    require(host.crops == std::vector<std::string>{"1920x800+0+140"}, "quick crop within two seconds");
    require(crop.locked(), "quick decides once");

    // Samples that disagree by more than 1.5% of the frame do not zoom in until they settle.
    FakeHost shifting;
    LinuxAutoCrop unsettled(shifting);
    Clock::time_point later{};
    unsettled.setEnabled(true, later, true);
    int ticks = 0;
    run(unsettled, later, 3s, [&] { shifting.bars = letterbox(ticks++ / 4 % 2 ? 0.10 : 0.12); });
    require(shifting.crops.empty() && !unsettled.locked(), "unsettled samples never zoom in");
    shifting.bars = letterbox(0.11);
    run(unsettled, later, 2s);
    require(!shifting.crops.empty() && unsettled.locked(), "settled samples decide");

    // Leaving the mode forgets it: the next plain Auto Crop takes its usual five seconds.
    FakeHost plain;
    plain.bars = letterbox(140.0 / 1080);
    LinuxAutoCrop again(plain);
    Clock::time_point start{};
    again.setEnabled(true, start, true);
    again.setEnabled(false, start);
    again.setEnabled(true, start);
    run(again, start, 4s);
    require(plain.crops.empty(), "mode 3 keeps its learning time after mode 4");
}

void testDarkScenesNeverTighten() {
    FakeHost host;
    host.bars = letterbox(140.0 / 1080);
    LinuxAutoCrop crop(host);
    Clock::time_point now{};
    crop.setEnabled(true, now);
    run(crop, now, 8s);
    require(crop.appliedCrop() == "1920x800+0+140", "learned crop");
    host.bars = letterbox(0.35); // mostly black: under 40% picture
    run(crop, now, 10s);
    require(crop.appliedCrop() == "1920x800+0+140", "a dark scene is not taken for bars");
    host.judged = false; // fade to black
    run(crop, now, 10s);
    require(crop.appliedCrop() == "1920x800+0+140", "an unjudgeable frame keeps the crop");
}

void testLooseningAfterLockAndCroppedGrabs() {
    FakeHost host;
    host.bars = letterbox(140.0 / 1080);
    LinuxAutoCrop crop(host);
    Clock::time_point now{};
    crop.setEnabled(true, now);
    // After the crop is applied, grabs come back already cropped (1920x800, no bars): the bars
    // must be read as the full frame's, not as "no bars any more".
    run(crop, now, 70s, [&] {
        if (!crop.appliedCrop().empty()) {
            host.bars = AutoCropBars{};
            host.grabWidth = 1920;
            host.grabHeight = 800;
        }
    });
    require(crop.locked() && crop.appliedCrop() == "1920x800+0+140", "cropped grabs keep the learned crop");
    // A taller (IMAX-like) scene after the lock opens the crop up, never the other way round.
    host.grabWidth = 1920;
    host.grabHeight = 1080;
    host.bars = letterbox(60.0 / 1080);
    run(crop, now, 31s);
    require(crop.appliedCrop() == "1920x960+0+60", "loosened to the taller picture: " + crop.appliedCrop());
    host.bars = letterbox(140.0 / 1080);
    run(crop, now, 31s);
    require(crop.appliedCrop() == "1920x960+0+60", "never tightened again after the lock");
}

void testDisableAndNewFileClearTheCrop() {
    FakeHost host;
    host.bars = letterbox(140.0 / 1080);
    LinuxAutoCrop crop(host);
    Clock::time_point now{};
    crop.setEnabled(true, now);
    run(crop, now, 8s);
    require(!crop.appliedCrop().empty(), "learned crop");
    crop.fileLoaded(now);
    require(crop.appliedCrop().empty() && host.crops.back().empty(), "a new file starts uncropped");
    run(crop, now, 10s);
    require(!crop.appliedCrop().empty(), "relearned on the new file");
    crop.setEnabled(false, now);
    require(!crop.enabled() && host.crops.back().empty(), "another resize mode removes the crop");
    const int captures = host.captures;
    run(crop, now, 10s);
    require(host.captures == captures, "no grabs while disabled");
}

void testPausedAndImax() {
    FakeHost host;
    host.bars = letterbox(140.0 / 1080);
    host.paused = true;
    LinuxAutoCrop crop(host);
    Clock::time_point now{};
    crop.setEnabled(true, now);
    run(crop, now, 10s);
    require(host.captures == 0, "no grabs while paused or seeking");

    FakeHost imax;
    imax.title = "film.imax.mkv";
    imax.bars = letterbox(140.0 / 1080);
    LinuxAutoCrop variable(imax);
    now = {};
    variable.setEnabled(true, now);
    run(variable, now, 120s);
    require(!variable.locked() && imax.captures > 30, "an IMAX title keeps checking continuously");
}

// --- real libmpv ---------------------------------------------------------------------------

void check(int result, const char *operation) {
    require(result >= 0, std::string(operation) + ": " + mpv_error_string(result));
}

std::string property(mpv_handle *mpv, const char *name) {
    char *value = mpv_get_property_string(mpv, name);
    if (!value) return {};
    std::string result(value);
    mpv_free(value);
    return result;
}

// The bridge's host, minus the Player: same production capture and video-crop helpers.
struct MpvHost : LinuxAutoCrop::Host {
    mpv_handle *mpv;
    explicit MpvHost(mpv_handle *handle) : mpv(handle) {}
    int64_t integer(const char *name, int64_t fallback) {
        int64_t value = fallback;
        return mpv_get_property(mpv, name, MPV_FORMAT_INT64, &value) < 0 ? fallback : value;
    }
    double number(const char *name) {
        double value = 0.0;
        return mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &value) < 0 ? 0.0 : value;
    }
    bool pausedOrSeeking() override { return property(mpv, "pause") == "yes" || property(mpv, "seeking") == "yes"; }
    double displayWidth() override { return number("video-params/dw"); }
    double displayHeight() override { return number("video-params/dh"); }
    int64_t frameWidth() override { return integer("video-out-params/w", integer("video-params/w", 0)); }
    int64_t frameHeight() override { return integer("video-out-params/h", integer("video-params/h", 0)); }
    bool capture(AutoCropBars &bars, int64_t &width, int64_t &height) override {
        return captureAutoCropBars(mpv, bars, width, height);
    }
    void setCrop(const std::string &crop) override { setAutoCropVideoCrop(mpv, crop); }
    bool titleTaggedImax() override { return false; }
    void log(const std::string &line) override { std::cerr << line << '\n'; }
};

void testRealMpv() {
    std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)> owner(mpv_create(), mpv_terminate_destroy);
    mpv_handle *mpv = owner.get();
    require(mpv != nullptr, "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"vo", "null"}, {"ao", "null"},
             {"audio", "no"}, {"hwdec", "no"}, {"terminal", "no"}, {"idle", "yes"},
             {"image-display-duration", "inf"}}) {
        check(mpv_set_option_string(mpv, name, value), name);
    }
    check(mpv_initialize(mpv), "mpv_initialize");
    // A 2.40:1 grey picture letterboxed into 1920x1080 (140-row black bars), as a binary PPM.
    const auto image = std::filesystem::temp_directory_path() / "nuvio-auto-crop-test.ppm";
    {
        const auto pixels = frame(1920, 1080, 140, 140, 0, 0);
        std::ofstream out(image, std::ios::binary);
        out << "P6\n1920 1080\n255\n";
        for (size_t i = 0; i < pixels.size(); i += 4) out.write(reinterpret_cast<const char *>(&pixels[i]), 3);
        require(bool(out), "write test image");
    }
    const std::string path = image.string();
    const char *load[] = {"loadfile", path.c_str(), nullptr};
    check(mpv_command(mpv, load), "loadfile");
    MpvHost host(mpv);
    LinuxAutoCrop crop(host);
    bool loaded = false;
    const auto deadline = Clock::now() + 30s;
    while (Clock::now() < deadline && crop.appliedCrop().empty()) {
        mpv_event *event = mpv_wait_event(mpv, 0.1);
        require(event->event_id != MPV_EVENT_END_FILE, "test image failed to play");
        const auto now = Clock::now();
        if (event->event_id == MPV_EVENT_FILE_LOADED) {
            loaded = true;
            crop.fileLoaded(now);
            crop.setEnabled(true, now);
        }
        if (event->event_id == MPV_EVENT_PLAYBACK_RESTART) crop.playbackRestart(now);
        if (loaded) crop.tick(now);
    }
    require(crop.appliedCrop() == "1920x800+0+140", "real-mpv crop: " + crop.appliedCrop());
    const std::string applied = property(mpv, "video-crop");
    require(applied == "1920x800+0+140", "mpv video-crop property: " + applied);
    crop.setEnabled(false, Clock::now());
    const std::string cleared = property(mpv, "video-crop");
    require(cleared.empty() || cleared.rfind("0x0", 0) == 0, "video-crop cleared: " + cleared);
    std::filesystem::remove(image);
}
} // namespace

int main(int argc, char **argv) {
    try {
        testDetection();
        testLearningAppliesThenLocks();
        testQuickDecision();
        testDarkScenesNeverTighten();
        testLooseningAfterLockAndCroppedGrabs();
        testDisableAndNewFileClearTheCrop();
        testPausedAndImax();
        if (!(argc > 1 && std::string(argv[1]) == "--rules-only")) testRealMpv();
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
    std::cout << "AUTO_CROP_PASS\n";
    return 0;
}
