// Windows' seek policy (seek_coalesce.h) against a fake clock, then real libmpv with null outputs:
// mpv takes both seek modes, and the end guard keeps a seek past the end from reaching EOF (with an
// unguarded seek as the negative control). Never creates a window or opens a device.
#include "seek_coalesce.h"
#include <mpv/client.h>
#include <unistd.h>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <thread>
#include <utility>
#include <vector>

namespace {
using Coalescer = LinuxSeekCoalescer;
using std::chrono::milliseconds;

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const char *operation) {
    require(result >= 0, std::string(operation) + ": " + mpv_error_string(result));
}
Coalescer::StepInput stepOf(double offset, std::optional<double> timePos = 100.0) {
    Coalescer::StepInput input;
    input.offsetSeconds = offset;
    input.duration = 1000.0;
    input.timePos = timePos;
    return input;
}

void clampRules() {
    require(Coalescer::clamp(-5.0, 100.0) == 0.0, "no negative targets");
    require(Coalescer::clamp(50.0, 100.0) == 50.0, "targets inside the file pass");
    require(Coalescer::clamp(100.0, 100.0) == 99.0, "the end guard holds");
    require(Coalescer::clamp(500.0, 100.0) == 99.0, "past the end is held too");
    require(Coalescer::clamp(50.0, 0.0) == 50.0, "unknown duration leaves the target alone");
    require(Coalescer::clamp(50.0, 1.0) == 50.0, "a duration shorter than the guard is not clamped to 0");
    require(linuxSeekSourceIsLocal("/films/a.mkv") && linuxSeekSourceIsLocal("file:///a.mkv"), "local sources");
    require(!linuxSeekSourceIsLocal("https://host/a.mkv"), "remote source");
}

void burstRules() {
    Coalescer seeks;
    auto now = Coalescer::Time{} + std::chrono::hours(1);

    auto first = seeks.step(stepOf(10.0), now);
    require(first && !first->deferred && first->target == 110.0, "an isolated press seeks at once");
    seeks.issued(first->target, now);

    now += milliseconds(100);
    auto second = seeks.step(stepOf(10.0), now);
    require(second && second->deferred && second->target == 120.0, "a following step chains off the target");
    require(seeks.deferred() == 120.0, "position tracks the deferred target");
    require(!seeks.takeDue(now), "not due inside the window");

    now += milliseconds(100);
    auto third = seeks.step(stepOf(-5.0), now);
    require(third && third->deferred && third->target == 115.0, "steps accumulate both ways");

    now += Coalescer::CoalesceWindow;
    auto due = seeks.takeDue(now);
    require(due && *due == 115.0, "one trailing seek after the burst");
    require(seeks.deferred() < 0.0 && !seeks.takeDue(now), "cleared, so a rejected seek cannot re-arm");
    seeks.issued(*due, now);

    now += Coalescer::CoalesceWindow;
    auto settled = seeks.step(stepOf(10.0, 500.0), now + Coalescer::PendingFresh);
    require(settled && !settled->deferred && settled->target == 510.0, "a settled player steps from time-pos");
}

void heldKeyRules() {
    // A key held for five seconds at the usual repeat rate: 50 steps, one request every 350 ms or so.
    Coalescer seeks;
    auto now = Coalescer::Time{} + std::chrono::hours(1);
    int seekCount = 0;
    double position = 100.0;
    for (int i = 0; i < 50; ++i) {
        if (const auto due = seeks.takeDue(now)) { seeks.issued(*due, now); position = *due; ++seekCount; }
        auto held = seeks.step(stepOf(1.0, position), now);
        require(bool(held), "held key keeps stepping");
        if (!held->deferred) { seeks.issued(held->target, now); position = held->target; ++seekCount; }
        now += milliseconds(100);
    }
    require(seekCount >= 5 && seekCount <= 16, "a held key seeks periodically, not 50 times: " + std::to_string(seekCount));
    const double reached = seeks.deferred() >= 0.0 ? seeks.deferred() : position;
    require(reached == 150.0, "no step lost: " + std::to_string(reached));
}

void exemptions() {
    Coalescer local;
    auto now = Coalescer::Time{} + std::chrono::hours(1);
    auto first = local.step(stepOf(10.0), now);
    local.issued(first->target, now);
    auto input = stepOf(10.0);
    input.localFile = true;
    auto step = local.step(input, now + milliseconds(50));
    require(step && !step->deferred, "local files never coalesce");

    Coalescer cached;
    first = cached.step(stepOf(10.0), now);
    cached.issued(first->target, now);
    input = stepOf(10.0);
    input.cachedUntil = 300.0;
    step = cached.step(input, now + milliseconds(50));
    require(step && !step->deferred, "a forward step inside the cache never coalesces");
    cached.issued(step->target, now + milliseconds(50));
    input = stepOf(-10.0);
    input.cachedUntil = 300.0;
    step = cached.step(input, now + milliseconds(100));
    require(step && step->deferred, "a backward step can leave the cache");

    Coalescer empty;
    require(!empty.step(stepOf(10.0, std::nullopt), now), "no position means no seek, not a jump to 0:00");
    auto seeded = stepOf(10.0, std::nullopt);
    empty.issued(50.0, now);
    auto fromPending = empty.step(seeded, now + std::chrono::seconds(5));
    require(fromPending && fromPending->target == 60.0, "a lost time-pos falls back to the last target");
}

void resetRules() {
    Coalescer seeks;
    auto now = Coalescer::Time{} + std::chrono::hours(1);
    auto first = seeks.step(stepOf(10.0), now);
    seeks.issued(first->target, now);
    seeks.step(stepOf(10.0), now + milliseconds(10));
    require(seeks.deferred() >= 0.0, "armed");
    seeks.reset();
    require(seeks.deferred() < 0.0 && !seeks.takeDue(now + std::chrono::hours(1)), "a new file drops it");
    auto fresh = seeks.step(stepOf(10.0, 7.0), now + milliseconds(20));
    require(fresh && !fresh->deferred && fresh->target == 17.0, "and starts from its own position");
}

struct Media {
    std::filesystem::path directory = std::filesystem::temp_directory_path() /
        ("nuvio-seek-coalesce-" + std::to_string(::getpid()));
    Media() {
        std::filesystem::create_directories(directory);
        std::ofstream video(directory / "video.y4m", std::ios::binary);
        video << "YUV4MPEG2 W2 H2 F25:1 Ip A1:1 C420jpeg\n";
        for (int frame = 0; frame < 250; ++frame) video << "FRAME\n" << std::string(6, '\x40');
    }
    ~Media() { std::error_code ignored; std::filesystem::remove_all(directory, ignored); }
};

using Player = std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)>;
Player play(const std::string &video) {
    Player player(mpv_create(), mpv_terminate_destroy);
    require(bool(player), "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"vo", "null"}, {"ao", "null"},
             {"terminal", "no"}, {"pause", "yes"}, {"idle", "yes"}, {"keep-open", "yes"}, {"hr-seek", "no"}})
        check(mpv_set_option_string(player.get(), name, value), name);
    check(mpv_initialize(player.get()), "initialize");
    const char *load[] = {"loadfile", video.c_str(), nullptr};
    check(mpv_command(player.get(), load), "loadfile");
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (mpv_wait_event(player.get(), 0.1)->event_id != MPV_EVENT_PLAYBACK_RESTART)
        require(std::chrono::steady_clock::now() < deadline, "no first frame");
    return player;
}
double seekAndSettle(mpv_handle *mpv, double target, const char *mode) {
    const std::string seconds = std::to_string(target);
    const char *seek[] = {"seek", seconds.c_str(), mode, nullptr};
    check(mpv_command(mpv, seek), mode);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (mpv_wait_event(mpv, 0.1)->event_id != MPV_EVENT_PLAYBACK_RESTART) {
        int eof = 0;
        mpv_get_property(mpv, "eof-reached", MPV_FORMAT_FLAG, &eof);
        if (eof) break;
        require(std::chrono::steady_clock::now() < deadline, "seek did not settle");
    }
    double position = -1.0;
    check(mpv_get_property(mpv, "time-pos", MPV_FORMAT_DOUBLE, &position), "time-pos");
    return position;
}
bool eofReached(mpv_handle *mpv) {
    std::this_thread::sleep_for(milliseconds(200));
    int eof = 0;
    mpv_get_property(mpv, "eof-reached", MPV_FORMAT_FLAG, &eof);
    return eof != 0;
}

void realLibmpv() {
    Media media;
    const auto video = (media.directory / "video.y4m").string();
    auto player = play(video);
    double duration = 0.0;
    check(mpv_get_property(player.get(), "duration", MPV_FORMAT_DOUBLE, &duration), "duration");
    require(duration > 9.0, "fixture is ten seconds");

    const double middle = seekAndSettle(player.get(), 4.0, kLinuxTimelineSeekMode);
    require(middle > 3.5 && middle < 4.5, "a timeline seek lands");
    const double step = seekAndSettle(player.get(), 2.0, kLinuxStepSeekMode);
    require(step > 1.9 && step < 2.1, "a step lands exactly");

    const double guarded = seekAndSettle(player.get(), Coalescer::clamp(duration + 60.0, duration), kLinuxTimelineSeekMode);
    require(guarded < duration - 0.5 && !eofReached(player.get()), "the guard keeps a seek off EOF");

    auto unguarded = play(video);
    seekAndSettle(unguarded.get(), duration + 60.0, kLinuxTimelineSeekMode);
    require(eofReached(unguarded.get()), "negative control: the same seek without the guard reaches EOF");
}
}

int main() {
    try {
        clampRules();
        std::cout << "PASS end guard\n";
        burstRules();
        std::cout << "PASS a burst folds into one trailing seek\n";
        heldKeyRules();
        std::cout << "PASS a held key seeks periodically\n";
        exemptions();
        std::cout << "PASS local files and cached steps are exempt; no position means no seek\n";
        resetRules();
        std::cout << "PASS a new file starts clean\n";
        realLibmpv();
        std::cout << "PASS real libmpv: both modes land, the guard keeps a seek off EOF\n";
        std::cout << "SEEK_COALESCE_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
