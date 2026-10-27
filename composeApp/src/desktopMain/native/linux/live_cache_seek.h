#pragma once

// Live TV seeks kept inside mpv's demuxer cache, for the Linux bridge. A live .ts channel cannot
// seek on its connection, so a seek that misses the cache's seekable ranges falls through to
// FFmpeg ("Cannot seek backward in linear streams"): mpv drops the cache and reads on from the
// connection, which stalls. Ported from the Windows bridge (native/windows/player_bridge.cpp,
// seekWithinCacheLocked / seekToCachedLiveEdge): the range clamp, the hr-seek demuxer offset and
// Go live's target are the same rules. These need neither JNI nor a player, so they are tested
// headless; player_bridge.cpp reads demuxer-cache-state and issues the seeks.

#include <mpv/client.h>
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

struct LinuxCachedSeekRange {
    double start;
    double end;
};

// demuxer-cache-state's seekable ranges and the received end (cache-end, -1 when unknown).
struct LinuxLiveCacheState {
    std::vector<LinuxCachedSeekRange> ranges;
    double cacheEnd = -1.0;
};

// A target lands this far inside a range edge; the edge itself was judged just outside the range.
constexpr double kLinuxLiveEdgeRangeMarginSeconds = 0.25;
// Go live skips a jump this short: a second or two is not worth the visible seek.
constexpr double kLinuxLiveEdgeMinJumpSeconds = 3.0;
// Kept between a live seek and the newest packet received, so it never lands on the last one.
constexpr double kLinuxLiveSeekEdgeReserveSeconds = 1.0;

inline LinuxLiveCacheState linuxLiveCacheState(const mpv_node &state) {
    LinuxLiveCacheState cache;
    if (state.format != MPV_FORMAT_NODE_MAP || !state.u.list) return cache;
    const mpv_node_list &map = *state.u.list;
    for (int i = 0; i < map.num; ++i) {
        const std::string key = map.keys && map.keys[i] ? map.keys[i] : "";
        const mpv_node &value = map.values[i];
        if (key == "cache-end" && value.format == MPV_FORMAT_DOUBLE) {
            cache.cacheEnd = value.u.double_;
        } else if (key == "seekable-ranges" && value.format == MPV_FORMAT_NODE_ARRAY && value.u.list) {
            const mpv_node_list &ranges = *value.u.list;
            for (int r = 0; r < ranges.num; ++r) {
                const mpv_node &range = ranges.values[r];
                if (range.format != MPV_FORMAT_NODE_MAP || !range.u.list) continue;
                double start = -1.0;
                double end = -1.0;
                const mpv_node_list &fields = *range.u.list;
                for (int f = 0; f < fields.num; ++f) {
                    const std::string field = fields.keys && fields.keys[f] ? fields.keys[f] : "";
                    if (fields.values[f].format != MPV_FORMAT_DOUBLE) continue;
                    if (field == "start") start = fields.values[f].u.double_;
                    if (field == "end") end = fields.values[f].u.double_;
                }
                if (start >= 0.0 && end > start) cache.ranges.push_back({start, end});
            }
        }
    }
    return cache;
}

inline double linuxNewestCachedRangeEnd(const LinuxLiveCacheState &cache) {
    double newestEnd = -1.0;
    for (const auto &range : cache.ranges) newestEnd = std::max(newestEnd, range.end);
    return newestEnd;
}

// A seek target moved into a seekable range: kept where it is inside one, otherwise onto the
// nearest range's edge, kLinuxLiveEdgeRangeMarginSeconds in. Negative when there is no range.
// The newest range reaches on to the received end (cache-end, less a reserve) rather than
// stopping at its own end: a range ends at the newest keyframe, which on a long-GOP channel is
// 10 s short of what has arrived. linuxCachedSeekDemuxerOffset reaches past it.
inline double linuxClampIntoCachedRanges(const LinuxLiveCacheState &cache, double target) {
    const double newestEnd = linuxNewestCachedRangeEnd(cache);
    double best = -1.0;
    double bestDistance = 0.0;
    for (const auto &range : cache.ranges) {
        const double low = range.start + kLinuxLiveEdgeRangeMarginSeconds;
        double high = std::max(low, range.end - kLinuxLiveEdgeRangeMarginSeconds);
        if (range.end == newestEnd && cache.cacheEnd > range.end) {
            high = std::max(high, cache.cacheEnd - kLinuxLiveSeekEdgeReserveSeconds);
        }
        const double clamped = std::min(std::max(target, low), high);
        const double distance = std::fabs(clamped - target);
        if (best < 0.0 || distance < bestDistance) {
            best = clamped;
            bestDistance = distance;
        }
    }
    return best;
}

// hr-seek-demuxer-offset for an exact seek to [target]. Past the newest keyframe (the end of the
// seekable range) mpv's range check refuses the demuxer seek and it falls through to FFmpeg, so the
// demuxer is sent back into the range by this much and the exact seek decodes forward through the
// cached packets. 0 when the target is reachable as it is.
inline double linuxCachedSeekDemuxerOffset(const LinuxLiveCacheState &cache, double target) {
    const double reachable = linuxNewestCachedRangeEnd(cache) - kLinuxLiveEdgeRangeMarginSeconds;
    return target > reachable ? target - reachable : 0.0;
}

// Live TV's "Go live": [cushionMs] short of the received end, inside the cache. Negative when
// there is nowhere to go or the jump is too short to be worth it.
inline double linuxCachedLiveEdgeTarget(const LinuxLiveCacheState &cache, double timePos, long long cushionMs) {
    const double edge = cache.cacheEnd > 0.0 ? cache.cacheEnd : linuxNewestCachedRangeEnd(cache);
    const double target = linuxClampIntoCachedRanges(cache, edge - (double)std::max(0LL, cushionMs) / 1000.0);
    if (target < 0.0 || target <= timePos + kLinuxLiveEdgeMinJumpSeconds) return -1.0;
    return target;
}
