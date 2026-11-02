// Live TV cache-only seeks (live_cache_seek.h): demuxer-cache-state parsing, the range clamp, the
// hr-seek demuxer offset and Go live's target. Pure data; no libmpv calls, window or network.
#include "live_cache_seek.h"
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

bool near(double a, double b) { return std::fabs(a - b) < 1e-9; }

// Owns the storage an mpv_node tree points into, shaped like mpv's demuxer-cache-state.
struct CacheStateNode {
    struct Range {
        std::vector<char *> keys;
        std::vector<mpv_node> values;
        mpv_node_list list{};
        mpv_node node{};
    };
    std::vector<Range> ranges;
    std::vector<mpv_node> rangeNodes;
    mpv_node_list rangeList{};
    std::vector<char *> keys;
    std::vector<mpv_node> values;
    mpv_node_list list{};
    mpv_node node{};

    static mpv_node number(double value) {
        mpv_node n{};
        n.format = MPV_FORMAT_DOUBLE;
        n.u.double_ = value;
        return n;
    }

    CacheStateNode(const std::vector<std::pair<double, double>> &spans, double cacheEnd, bool malformed = false) {
        ranges.resize(spans.size() + (malformed ? 2 : 0));
        size_t index = 0;
        for (const auto &span : spans) {
            Range &range = ranges[index++];
            range.keys = {const_cast<char *>("start"), const_cast<char *>("end")};
            range.values = {number(span.first), number(span.second)};
        }
        if (malformed) {
            // An empty span, and a span whose end is not a double: both ignored.
            Range &empty = ranges[index++];
            empty.keys = {const_cast<char *>("start"), const_cast<char *>("end")};
            empty.values = {number(200.0), number(200.0)};
            Range &text = ranges[index++];
            mpv_node string{};
            string.format = MPV_FORMAT_STRING;
            string.u.string = const_cast<char *>("300");
            text.keys = {const_cast<char *>("start"), const_cast<char *>("end")};
            text.values = {number(250.0), string};
        }
        for (Range &range : ranges) {
            range.list.num = (int)range.values.size();
            range.list.keys = range.keys.data();
            range.list.values = range.values.data();
            range.node.format = MPV_FORMAT_NODE_MAP;
            range.node.u.list = &range.list;
            rangeNodes.push_back(range.node);
        }
        rangeList.num = (int)rangeNodes.size();
        rangeList.values = rangeNodes.data();
        mpv_node rangesNode{};
        rangesNode.format = MPV_FORMAT_NODE_ARRAY;
        rangesNode.u.list = &rangeList;
        keys = {const_cast<char *>("bof-cached"), const_cast<char *>("seekable-ranges")};
        mpv_node flag{};
        flag.format = MPV_FORMAT_FLAG;
        flag.u.flag = 1;
        values = {flag, rangesNode};
        if (cacheEnd >= 0.0) {
            keys.push_back(const_cast<char *>("cache-end"));
            values.push_back(number(cacheEnd));
        }
        list.num = (int)values.size();
        list.keys = keys.data();
        list.values = values.data();
        node.format = MPV_FORMAT_NODE_MAP;
        node.u.list = &list;
    }
};

void testParsing() {
    CacheStateNode state({{10.0, 50.0}, {60.0, 100.0}}, 120.0, true);
    const auto cache = linuxLiveCacheState(state.node);
    require(cache.ranges.size() == 2, "two valid ranges, malformed ones dropped");
    require(near(cache.ranges[1].start, 60.0) && near(cache.ranges[1].end, 100.0), "range values");
    require(near(cache.cacheEnd, 120.0), "cache-end");

    mpv_node none{};
    const auto empty = linuxLiveCacheState(none);
    require(empty.ranges.empty() && empty.cacheEnd < 0.0, "no cache state");
}

void testClamp() {
    CacheStateNode state({{10.0, 50.0}, {60.0, 100.0}}, 120.0);
    const auto cache = linuxLiveCacheState(state.node);
    require(near(linuxClampIntoCachedRanges(cache, 30.0), 30.0), "inside a range stays");
    require(near(linuxClampIntoCachedRanges(cache, 52.0), 49.75), "gap goes to the nearest edge, margin in");
    require(near(linuxClampIntoCachedRanges(cache, 59.0), 60.25), "nearest edge of the next range");
    require(near(linuxClampIntoCachedRanges(cache, 2.0), 10.25), "before the cache: oldest edge");
    // The newest range reaches on to the received end less the reserve.
    require(near(linuxClampIntoCachedRanges(cache, 105.0), 105.0), "past the newest keyframe, before cache-end");
    require(near(linuxClampIntoCachedRanges(cache, 130.0), 119.0), "past cache-end: reserve kept");

    CacheStateNode noEnd({{60.0, 100.0}}, -1.0);
    require(near(linuxClampIntoCachedRanges(linuxLiveCacheState(noEnd.node), 130.0), 99.75), "no cache-end: range edge");

    CacheStateNode none({}, 120.0);
    require(linuxClampIntoCachedRanges(linuxLiveCacheState(none.node), 30.0) < 0.0, "no range: skipped");
}

void testDemuxerOffset() {
    CacheStateNode state({{10.0, 50.0}, {60.0, 100.0}}, 120.0);
    const auto cache = linuxLiveCacheState(state.node);
    require(near(linuxCachedSeekDemuxerOffset(cache, 105.0), 5.25), "offset past the newest keyframe");
    require(near(linuxCachedSeekDemuxerOffset(cache, 99.75), 0.0), "reachable: no offset");
    require(near(linuxCachedSeekDemuxerOffset(cache, 30.0), 0.0), "older range: no offset");
}

void testGoLive() {
    CacheStateNode state({{10.0, 50.0}, {60.0, 100.0}}, 120.0);
    const auto cache = linuxLiveCacheState(state.node);
    require(near(linuxCachedLiveEdgeTarget(cache, 90.0, 3000), 117.0), "cushion short of cache-end");
    require(linuxCachedLiveEdgeTarget(cache, 115.0, 3000) < 0.0, "a jump under the minimum is skipped");
    require(near(linuxCachedLiveEdgeTarget(cache, 90.0, -5000), 119.0), "negative cushion: reserve only");

    CacheStateNode noEnd({{60.0, 100.0}}, -1.0);
    require(near(linuxCachedLiveEdgeTarget(linuxLiveCacheState(noEnd.node), 80.0, 3000), 97.0), "no cache-end: range end");

    CacheStateNode none({}, -1.0);
    require(linuxCachedLiveEdgeTarget(linuxLiveCacheState(none.node), 10.0, 3000) < 0.0, "no cache: skipped");
}

void testGoLiveStep() {
    CacheStateNode ranged({{60.0, 100.0}}, 120.0);
    const auto withRange = linuxLiveCacheState(ranged.node);
    require(linuxGoLiveStep(withRange, 90.0, 3000) == LinuxGoLiveStep::Seek, "a range is seeked inside");
    require(linuxGoLiveStep(withRange, 115.0, 3000) == LinuxGoLiveStep::AlreadyLive, "a short jump is not worth a reopen");

    // The log that found this: SIC Radical, 77 s behind with nothing seekable.
    CacheStateNode unseekable({}, 124.03);
    const auto noRange = linuxLiveCacheState(unseekable.node);
    require(noRange.ranges.empty(), "fixture has no range");
    require(linuxCachedLiveEdgeTarget(noRange, 47.0, 3000) < 0.0, "no seek target");
    require(linuxGoLiveStep(noRange, 47.0, 3000) == LinuxGoLiveStep::NoSeekableRange, "behind with no range: reopen");
    require(linuxGoLiveStep(noRange, 120.0, 3000) == LinuxGoLiveStep::AlreadyLive, "at the edge: nothing to do");
    require(linuxGoLiveStep(noRange, -1.0, 3000) == LinuxGoLiveStep::AlreadyLive, "unknown position: nothing shows it is behind");

    CacheStateNode empty({}, -1.0);
    require(linuxGoLiveStep(linuxLiveCacheState(empty.node), 10.0, 3000) == LinuxGoLiveStep::AlreadyLive, "no cache yet: no reopen");
}

}  // namespace

int main() {
    try {
        testParsing();
        testClamp();
        testDemuxerOffset();
        testGoLive();
        testGoLiveStep();
    } catch (const std::exception &error) {
        std::cerr << "live cache seek test failed: " << error.what() << '\n';
        return 1;
    }
    std::cout << "LIVE_CACHE_SEEK_PASS\n";
    return 0;
}
