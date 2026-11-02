#pragma once

// Windows' seek policy (seekToMilliseconds, seekByMilliseconds, flushDeferredSeek) without mpv calls.
// Timeline seeks go by keyframe: the jump is large enough to cross one and the speed is worth more
// than sub-second accuracy. Relative steps are exact, because the step can be shorter than a GOP and
// a keyframe seek would then land behind the current position. On a high-bitrate remux each step
// outside the cache is one HTTP range request, and hosts that throttle connection opens answer a
// burst with 429, so steps following a recent seek fold into one trailing seek. An isolated press
// still seeks at once. Callers serialize on the player's operation lock; deferred() is also read by
// the event loop to choose its wait.

#include <algorithm>
#include <atomic>
#include <chrono>
#include <optional>
#include <string>

constexpr const char *kLinuxTimelineSeekMode = "absolute+keyframes";
constexpr const char *kLinuxStepSeekMode = "absolute+exact";

class LinuxSeekCoalescer {
public:
    using Clock = std::chrono::steady_clock;
    using Time = Clock::time_point;
    static constexpr double EndGuardSeconds = 1.0;
    static constexpr auto CoalesceWindow = std::chrono::milliseconds(350);
    static constexpr auto CoalesceMaxWait = std::chrono::milliseconds(1000);
    static constexpr auto PendingFresh = std::chrono::milliseconds(800);

    // Keeps a target off the file's last second, where mpv would run into EOF. No clamp at the end
    // while the duration is unknown (0), or the guard would send every seek to the start.
    static double clamp(double seconds, double duration) {
        double clamped = std::max(0.0, seconds);
        if (duration > EndGuardSeconds) clamped = std::min(clamped, duration - EndGuardSeconds);
        return clamped;
    }

    struct StepInput {
        double offsetSeconds = 0.0;
        double duration = 0.0;
        std::optional<double> timePos; // Empty while mpv has no position.
        bool seeking = false;
        double cachedUntil = -1.0;     // demuxer-cache-time, or negative when unknown.
        bool localFile = false;
    };
    struct Step {
        double target;
        bool deferred; // Otherwise the caller seeks to target now and reports issued().
    };

    // Empty when there is no position to step from: dropping a step beats jumping to 0:00.
    std::optional<Step> step(const StepInput &input, Time now) {
        const auto base = stepBase(input, now);
        if (!base) return std::nullopt;
        const double target = clamp(*base + input.offsetSeconds, input.duration);
        // Coalescing only spares remote hosts, so not for local files or a forward step the demuxer
        // has already cached.
        const bool offNetwork = input.localFile ||
            (target >= *base && input.cachedUntil > 0.0 && target <= input.cachedUntil);
        if (offNetwork || lastIssuedAt_ == Time{} || now - lastIssuedAt_ >= CoalesceWindow) return Step{target, false};
        if (deferred_.load() < 0.0) deferredStartedAt_ = now;
        deferred_ = target;
        // Each step pushes the deadline out, so steady steps would defer for ever without the cap.
        deferredDueAt_ = std::min<Time>(now + CoalesceWindow, deferredStartedAt_ + CoalesceMaxWait);
        return Step{target, true};
    }

    void issued(double target, Time now) {
        pendingTarget_ = target;
        pendingIssuedAt_ = now;
        lastIssuedAt_ = now;
        deferred_ = -1.0;
    }

    // The trailing seek once its burst has settled. Cleared here so that a rejected seek command
    // cannot re-arm itself on every tick.
    std::optional<double> takeDue(Time now) {
        const double target = deferred_.load();
        if (target < 0.0 || now < deferredDueAt_) return std::nullopt;
        deferred_ = -1.0;
        return target;
    }

    // While steps are being folded the HUD tracks the accumulated target: time-pos still sits at the
    // pre-burst position, which would make a held arrow key look like it had stopped responding.
    double deferred() const { return deferred_.load(); }

    // A target aimed at the previous file must not fire against, or seed steps in, the new one.
    void reset() {
        pendingTarget_ = -1.0;
        deferred_ = -1.0;
        deferredDueAt_ = {};
        deferredStartedAt_ = {};
        lastIssuedAt_ = {};
    }

private:
    double pendingTarget_ = -1.0;
    Time pendingIssuedAt_{};
    Time lastIssuedAt_{};
    std::atomic<double> deferred_{-1.0};
    Time deferredDueAt_{};
    Time deferredStartedAt_{};

    // Chains off a seek not yet landed, because time-pos lags (or is briefly unavailable) until mpv
    // finishes seeking and reading it raw makes rapid steps collapse into one.
    std::optional<double> stepBase(const StepInput &input, Time now) const {
        if (deferred_.load() >= 0.0) return deferred_.load();
        const bool fresh = pendingTarget_ >= 0.0 && now - pendingIssuedAt_ < PendingFresh;
        if (pendingTarget_ >= 0.0 && (input.seeking || fresh)) return pendingTarget_;
        if (input.timePos) return std::max(0.0, *input.timePos);
        if (pendingTarget_ >= 0.0) return pendingTarget_;
        return std::nullopt;
    }
};

// As on Windows: a bare path or file:// opens no HTTP range requests.
inline bool linuxSeekSourceIsLocal(const std::string &source) {
    return source.find("://") == std::string::npos || source.rfind("file://", 0) == 0;
}
