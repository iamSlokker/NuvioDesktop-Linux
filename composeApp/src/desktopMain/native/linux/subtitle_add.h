#pragma once

// Addon subtitles are added by URL. mpv_command() waits for sub-add to download and parse the
// file, and the bridge held the player's operation lock meanwhile, so picking an addon subtitle
// stalled the app's UI thread (which handles the HUD's choice) and every playback poll for the
// whole download. The add runs as an async command instead. As with the blocking call, the last
// subtitle choice wins: a newer add or track choice abandons an add still in flight, and an
// abandoned add that completes anyway is taken out again. Callers hold the player's operation lock.

#include <mpv/client.h>
#include <cstdint>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

class LinuxSubtitleAdds {
public:
    static constexpr uint64_t kReplyTag = 0x5375624164640000ull; // "SubAdd" + a 16-bit sequence
    static bool owns(uint64_t reply) { return (reply & ~0xffffull) == kReplyTag; }

    // After an abandoned add completed anyway: its track to take out, and the choice to restore
    // (an mpv sid value, or the URL of a finished newer add). Empty fields need nothing.
    struct Repair {
        std::string removeUrl;
        std::string sid;
        std::string selectUrl;
    };

    // Starts adding url, selected once loaded. Returns mpv's result for queueing the command.
    int add(mpv_handle *mpv, const std::string &url) {
        abandon(mpv, std::string());
        const uint64_t reply = kReplyTag | (++sequence_ & 0xffffull);
        const char *command[] = {"sub-add", url.c_str(), "select", nullptr};
        const int result = mpv_command_async(mpv, reply, command);
        if (result >= 0) {
            pending_ = reply;
            inFlight_[reply] = url;
        }
        wantedUrl_ = url;
        return result;
    }

    // A track choice or a clear (sid "no" or a track id): an add in flight must not select itself later.
    void abandon(mpv_handle *mpv, const std::string &sid) {
        if (pending_ != 0) mpv_abort_async_command(mpv, pending_);
        pending_ = 0;
        wantedSid_ = sid;
        wantedUrl_.clear();
    }

    // A COMMAND_REPLY this class owns. Sets url to the add's URL; returns a repair only when an
    // abandoned add completed anyway.
    std::optional<Repair> finished(uint64_t reply, int error, std::string &url) {
        const auto found = inFlight_.find(reply);
        if (found == inFlight_.end()) return std::nullopt;
        url = std::move(found->second);
        inFlight_.erase(found);
        if (reply == pending_) {
            pending_ = 0;
            return std::nullopt;
        }
        if (error < 0) return std::nullopt; // Abandoned before it added anything.
        Repair repair;
        if (url != wantedUrl_) repair.removeUrl = url;
        // A newer add still in flight selects itself when it lands.
        if (pending_ == 0) {
            repair.sid = wantedSid_;
            repair.selectUrl = wantedUrl_;
        }
        return repair;
    }

    // Whether reply belongs to the latest add, still wanted (an abandoned one's failure is expected).
    bool current(uint64_t reply) const { return reply == pending_; }

private:
    uint64_t sequence_ = 0;
    uint64_t pending_ = 0;
    std::unordered_map<uint64_t, std::string> inFlight_;
    std::string wantedSid_;
    std::string wantedUrl_;
};

namespace linux_subtitle_add_detail {
inline std::string property(mpv_handle *mpv, const std::string &name) {
    char *value = mpv_get_property_string(mpv, name.c_str());
    if (!value) return {};
    std::string result(value);
    mpv_free(value);
    return result;
}

// Ids of the external subtitle tracks loaded from url.
inline std::vector<std::string> externalTrackIds(mpv_handle *mpv, const std::string &url) {
    std::vector<std::string> ids;
    int64_t count = 0;
    if (mpv_get_property(mpv, "track-list/count", MPV_FORMAT_INT64, &count) < 0) return ids;
    for (int64_t index = 0; index < count; ++index) {
        const std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (property(mpv, prefix + "type") != "sub" || property(mpv, prefix + "external") != "yes" ||
            property(mpv, prefix + "external-filename") != url) continue;
        const std::string id = property(mpv, prefix + "id");
        if (!id.empty()) ids.push_back(id);
    }
    return ids;
}
}

// Applies a repair from LinuxSubtitleAdds::finished. Returns false when something was left undone.
inline bool applyLinuxSubtitleRepair(mpv_handle *mpv, const LinuxSubtitleAdds::Repair &repair) {
    using namespace linux_subtitle_add_detail;
    bool ok = true;
    if (!repair.removeUrl.empty()) {
        for (const auto &id : externalTrackIds(mpv, repair.removeUrl)) {
            const char *command[] = {"sub-remove", id.c_str(), nullptr};
            ok = mpv_command(mpv, command) >= 0 && ok;
        }
    }
    if (!repair.sid.empty()) {
        ok = mpv_set_property_string(mpv, "sid", repair.sid.c_str()) >= 0 && ok;
    } else if (!repair.selectUrl.empty()) {
        const auto ids = externalTrackIds(mpv, repair.selectUrl);
        ok = !ids.empty() && mpv_set_property_string(mpv, "sid", ids.back().c_str()) >= 0 && ok;
    }
    return ok;
}
