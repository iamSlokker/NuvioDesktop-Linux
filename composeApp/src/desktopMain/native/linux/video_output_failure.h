#pragma once

// With no working Vulkan driver the embedded player has no video output at all: the bundled libmpv
// only has the Vulkan GPU contexts, and mpv then carries on with the audio (or ends the file with "no
// audio or video data played" when there is none), so the user got sound over black or an error that
// names no cause. Nothing else can draw here, so this ends playback with a message that says why.

#include "http_recovery_events.h"
#include <mpv/client.h>
#include <cstring>
#include <optional>
#include <utility>

// Starts with PLAYBACK_VIDEO_OUTPUT_FAILURE in PlaybackSourceFailure.kt: no other source can do
// better, so failover must not walk the list.
constexpr const char *kLinuxVideoOutputFailure =
    "Video output could not start: Nuvio needs a working Vulkan driver for your graphics card "
    "(on Mesa, the package is usually mesa-vulkan-drivers).";

// mpv's own verdict once it has given up on the configured output, whatever the reason underneath.
inline bool linuxVideoOutputInitFailed(const mpv_event &event) {
    if (event.event_id != MPV_EVENT_LOG_MESSAGE || !event.data) return false;
    const auto *log = static_cast<const mpv_event_log_message *>(event.data);
    return log->log_level <= MPV_LOG_LEVEL_ERROR && log->prefix && log->text &&
        std::strcmp(log->prefix, "cplayer") == 0 &&
        std::strstr(log->text, "Error opening/initializing the selected video_out") != nullptr;
}

// Turns mpv's verdict into the one failure of this load. libmpv hands out queued events ahead of log
// messages, so with no audio to carry on with, END_FILE ("no audio or video data played") arrives
// before the verdict that explains it. That one failure is held until the log queue has been read:
// the next empty wait, 0.2 s at most, or the next file. Callers feed it every event, in order.
class LinuxVideoOutputFailures {
public:
    void filter(const mpv_event &event, LinuxHttpRecoveryEvents &recovery, LinuxHttpRecoveryEvents::Result &result) {
        const bool verdict = linuxVideoOutputInitFailed(event);
        if (held_) {
            if (verdict) {
                held_->event = held_->event.substr(0, held_->event.find(':') + 1) + kLinuxVideoOutputFailure;
                result.failure = std::exchange(held_, std::nullopt);
            } else if (event.event_id == MPV_EVENT_NONE || event.event_id == MPV_EVENT_START_FILE) {
                result.failure = std::exchange(held_, std::nullopt);
            }
            return;
        }
        if (result.failure && event.event_id == MPV_EVENT_END_FILE && event.data &&
            static_cast<const mpv_event_end_file *>(event.data)->error == MPV_ERROR_NOTHING_TO_PLAY) {
            held_ = std::exchange(result.failure, std::nullopt);
            return;
        }
        if (!result.failure && verdict) result = recovery.outputFailure(kLinuxVideoOutputFailure);
    }

private:
    std::optional<LinuxHttpRecoveryEvents::Failure> held_;
};
