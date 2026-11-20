#include "linux_gamepad.h"
#include <algorithm>
#include <deque>
#include <functional>
#include <iostream>
#include <map>
#include <stdexcept>
#include <tuple>

using namespace nuvio::gamepad;
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(std::string(__func__) + ":" + std::to_string(__LINE__) + " " #condition); } while (false)

void axis(Capabilities& c, int code, int low, int high) {
    c.axes.set(code); c.ranges[code].minimum = low; c.ranges[code].maximum = high;
}
Capabilities standard(bool playstation = false) {
    Capabilities c;
    for (int k : {BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH, BTN_TL, BTN_TR,
                  BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR, BTN_MODE}) c.keys.set(k);
    for (int a : {ABS_X, ABS_Y, ABS_RX, ABS_RY}) axis(c, a, playstation ? 0 : -32768, playstation ? 255 : 32767);
    if (playstation) { c.keys.set(BTN_TL2); c.keys.set(BTN_TR2); }
    axis(c, ABS_Z, 0, playstation ? 255 : 1023); axis(c, ABS_RZ, 0, playstation ? 255 : 1023);
    axis(c, ABS_HAT0X, -1, 1); axis(c, ABS_HAT0Y, -1, 1);
    return c;
}
State rest(const Capabilities& c) {
    State s;
    for (int a = 0; a < ABS_CNT; ++a) s.axes[a] = c.ranges[a].minimum;
    for (int a : {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_HAT0X, ABS_HAT0Y})
        s.axes[a] = c.ranges[a].minimum + (static_cast<int64_t>(c.ranges[a].maximum) - c.ranges[a].minimum + 1) / 2;
    return s;
}
input_event event(int type, int code, int value) {
    input_event e{}; e.type = type; e.code = code; e.value = value; return e;
}

struct FakeSystem : System {
    struct Pad {
        Capabilities caps = standard();
        State state = rest(caps);
        std::deque<input_event> events;
        bool denied = false, failed = false, snapshotFailed = false;
        int emptyInterruptions = 0;
        std::function<void(Pad&)> afterSnapshot;
        // HID side: hidraw reports queued for reading, what was written, the LED files' answer.
        bool hidPresent = false, hidFailed = false, ledsWritable = false;
        std::deque<std::vector<unsigned char>> reports;
        std::vector<std::vector<unsigned char>> written;
        std::vector<std::tuple<int, unsigned int, bool>> ledWrites;
    };
    std::map<std::string, Pad> pads;
    std::vector<Node> nodes;
    std::map<int, std::string> handles;
    int next = 1, opens = 0, closes = 0, reads = 0, snapshots = 0, enumerations = 0;
    bool directoryFailed = false;
    uint64_t time = 0, clockStep = DiscoveryIntervalMs;
    uint64_t nowMs() override { const auto now = time; time += clockStep; return now; }
    Pad& add(const std::string& path, uint64_t inode = 1) {
        nodes.push_back({path, 13, inode});
        return pads[path];
    }
    bool enumerate(std::vector<Node>& out) override { ++enumerations; out = nodes; return !directoryFailed; }
    int open(const Node& n, Capabilities& c, State& s) override {
        ++opens;
        auto& p = pads.at(n.path);
        if (p.denied) return -1;
        c = p.caps; s = p.state;
        handles[next] = n.path;
        return next++;
    }
    ReadResult read(int fd, input_event& out) override {
        ++reads;
        auto& p = pads.at(handles.at(fd));
        if (p.failed) return ReadResult::Failed;
        if (p.events.empty()) {
            if (p.emptyInterruptions > 0) { --p.emptyInterruptions; return ReadResult::Interrupted; }
            return ReadResult::Empty;
        }
        out = p.events.front(); p.events.pop_front(); return ReadResult::Event;
    }
    bool snapshot(int fd, const Capabilities&, State& s) override {
        ++snapshots;
        auto& p = pads.at(handles.at(fd)); s = p.state;
        if (p.afterSnapshot) p.afterSnapshot(p);
        return !p.snapshotFailed;
    }
    void close(int fd) override { CHECK(handles.erase(fd) == 1); ++closes; }

    std::map<int, std::string> hids;
    int hidOpens = 0, hidCloses = 0;
    std::vector<std::string> logs;
    int openHid(const Node& n) override {
        ++hidOpens;
        if (!pads.at(n.path).hidPresent) return -1;
        hids[next] = n.path;
        return next++;
    }
    int readHid(int fd, unsigned char* buffer, size_t size) override {
        auto& p = pads.at(hids.at(fd));
        if (p.hidFailed) return -1;
        if (p.reports.empty()) return 0;
        const auto report = p.reports.front(); p.reports.pop_front();
        const size_t length = std::min(size, report.size());
        std::copy(report.begin(), report.begin() + length, buffer);
        return static_cast<int>(length);
    }
    bool writeHid(int fd, const std::vector<unsigned char>& report) override {
        pads.at(hids.at(fd)).written.push_back(report); return true;
    }
    bool writeLeds(const Node& n, int mode, unsigned int rgb, bool player) override {
        auto& p = pads.at(n.path);
        if (!p.ledsWritable) return false;
        p.ledWrites.emplace_back(mode, rgb, player); return true;
    }
    void closeHid(int fd) override { CHECK(hids.erase(fd) == 1); ++hidCloses; }
    void log(const std::string& line) override { logs.push_back(line); }
};

// DualSense fixtures: hid-playstation's evdev ids, and its hidraw input reports with the mic byte set or not.
FakeSystem::Pad& dualSense(FakeSystem& sys, bool bluetooth, const std::string& path = "event0") {
    auto& p = sys.add(path);
    p.caps = standard(true);
    p.caps.id.bustype = bluetooth ? BUS_BLUETOOTH : BUS_USB; p.caps.id.vendor = 0x054c; p.caps.id.product = 0x0ce6;
    p.state = rest(p.caps);
    p.hidPresent = true;
    return p;
}
std::vector<unsigned char> micReport(bool bluetooth, bool held) {
    std::vector<unsigned char> report(bluetooth ? 78 : 64, 0);
    report[0] = bluetooth ? 0x31 : 0x01;
    report[bluetooth ? 11 : 10] = held ? 0x04 | 0xF8 : 0xF8; // only bit 2 is the mic; the rest must not count
    return report;
}
// Bitwise CRC-32, independent of the table the bridge uses.
uint32_t referenceCrc(const std::vector<unsigned char>& bytes) {
    uint32_t crc = 0xFFFFFFFFu;
    for (unsigned char byte : bytes) {
        crc ^= byte;
        for (int k = 0; k < 8; ++k) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}
void resetConfig() { configure(false, 0, 0, false); }

void qualification() {
    auto c = standard(); CHECK(qualifies(c));
    c.accelerometer = true; CHECK(!qualifies(c));
    c = standard(); c.keys.set(BTN_TOUCH); CHECK(!qualifies(c));
    c = standard(); c.axes.set(ABS_MT_POSITION_X); CHECK(!qualifies(c));
    c = standard(); c.keys.reset(); CHECK(!qualifies(c));
    c = standard(); c.axes.reset(); CHECK(!qualifies(c));
    // Generic digital pad, no sticks required.
    for (int k : {BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT}) c.keys.set(k);
    CHECK(qualifies(c));
}
void buttonsAndHats() {
    auto c = standard(); auto s = rest(c);
    const std::pair<int, int> mapping[] = {{BTN_SOUTH, 0x1000}, {BTN_EAST, 0x2000}, {BTN_WEST, 0x4000},
        {BTN_NORTH, 0x8000}, {BTN_TL, 0x100}, {BTN_TR, 0x200}, {BTN_START, 0x10},
        {BTN_SELECT, 0x20}, {BTN_THUMBL, 0x40}, {BTN_THUMBR, 0x80},
        {BTN_DPAD_UP, 1}, {BTN_DPAD_DOWN, 2}, {BTN_DPAD_LEFT, 4}, {BTN_DPAD_RIGHT, 8}};
    for (auto [code, bit] : mapping) {
        c.keys.set(code); s.keys.reset(); s.keys.set(code); CHECK(normalize(c, s)[0] == bit);
    }
    s.keys.reset(); s.keys.set(BTN_MODE); CHECK(normalize(c, s)[0] == 0);
    s.axes[ABS_HAT0X] = -1; s.axes[ABS_HAT0Y] = 1; CHECK(normalize(c, s)[0] == 6);
    s.axes[ABS_HAT0X] = 1; s.axes[ABS_HAT0Y] = -1; CHECK(normalize(c, s)[0] == 9);
}
void stickNormalization() {
    for (bool sony : {false, true}) {
        const auto c = standard(sony); auto s = rest(c);
        for (int index = 3; index <= 6; ++index) CHECK(normalize(c, s)[index] == 0);
        const int axes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY};
        for (int i = 0; i < 4; ++i) {
            const int code = axes[i], sign = i % 2 ? -1 : 1;
            s.axes[code] = c.ranges[code].minimum; CHECK(normalize(c, s)[i + 3] == -32767 * sign);
            s.axes[code] = c.ranges[code].maximum; CHECK(normalize(c, s)[i + 3] == 32767 * sign);
        }
    }
    auto c = standard(); auto s = rest(c);
    axis(c, ABS_X, 10, 10); CHECK(normalize(c, s)[3] == 0);
    axis(c, ABS_X, -100, 100); s.axes[ABS_X] = 1000; CHECK(normalize(c, s)[3] == 32767);
}
void triggerNormalization() {
    for (bool sony : {false, true}) {
        auto c = standard(sony); auto s = rest(c);
        CHECK(normalize(c, s)[1] == 0 && normalize(c, s)[2] == 0);
        s.axes[ABS_Z] = c.ranges[ABS_Z].maximum / 2; CHECK(normalize(c, s)[1] == 127);
        s.axes[ABS_Z] = c.ranges[ABS_Z].maximum; s.axes[ABS_RZ] = c.ranges[ABS_RZ].maximum;
        CHECK(normalize(c, s)[1] == 255 && normalize(c, s)[2] == 255);
    }
    auto c = standard(); axis(c, ABS_Z, -32768, 32767); auto s = rest(c);
    CHECK(normalize(c, s)[1] == 0); s.axes[ABS_Z] = 0; CHECK(normalize(c, s)[1] == 127);
}
void alternateTriggers() {
    auto c = standard(); c.axes.reset(ABS_RX); c.axes.reset(ABS_RY);
    axis(c, ABS_Z, -100, 100); axis(c, ABS_RZ, -100, 100);
    axis(c, ABS_BRAKE, 0, 1023); axis(c, ABS_GAS, 0, 1023);
    auto s = rest(c); s.axes[ABS_Z] = 100; s.axes[ABS_RZ] = -100;
    s.axes[ABS_BRAKE] = 1023; s.axes[ABS_GAS] = 512;
    const auto result = normalize(c, s);
    CHECK(result[1] == 255 && result[2] == 127 && result[5] == 32767 && result[6] == 32767);
    c = standard(); axis(c, ABS_HAT2Y, 0, 255); axis(c, ABS_HAT2X, 0, 255); s = rest(c);
    s.axes[ABS_HAT2Y] = 255; CHECK(normalize(c, s)[1] == 255 && normalize(c, s)[2] == 0);
}
void digitalAndAmbiguousTriggers() {
    auto c = standard(); c.axes.reset(ABS_RX); c.axes.reset(ABS_RY);
    auto s = rest(c); s.axes[ABS_Z] = 1023; s.axes[ABS_RZ] = 1023;
    CHECK(normalize(c, s)[1] == 0 && normalize(c, s)[2] == 0);
    c.keys.set(BTN_TL2); c.keys.set(BTN_TR2); s.keys.set(BTN_TL2); s.keys.set(BTN_TR2);
    CHECK(normalize(c, s)[1] == 255 && normalize(c, s)[2] == 255);
}
void hybridAnalogTriggersWin() {
    const auto c = standard(true); auto s = rest(c);
    for (int left : {0, 20, 59, 60, 61, 100, 255}) {
        for (int right : {0, 20, 59, 60, 61, 100, 255}) {
            s.axes[ABS_Z] = left; s.axes[ABS_RZ] = right;
            for (bool leftDown : {false, true}) for (bool rightDown : {false, true}) {
                s.keys[BTN_TL2] = leftDown; s.keys[BTN_TR2] = rightDown;
                const auto sample = normalize(c, s);
                CHECK(sample[1] == left && sample[2] == right);
            }
        }
    }
}
void independentAnalogTriggers() {
    for (bool left : {false, true}) for (bool degenerate : {false, true}) {
        auto c = standard(true);
        const int good = left ? ABS_Z : ABS_RZ, missing = left ? ABS_RZ : ABS_Z;
        if (degenerate) axis(c, missing, 10, 10); else c.axes.reset(missing);
        auto s = rest(c); s.axes[good] = 100;
        const int analog = left ? 1 : 2, digital = left ? 2 : 1;
        CHECK(normalize(c, s)[analog] == 100 && normalize(c, s)[digital] == 0);
        s.keys.set(BTN_TL2); s.keys.set(BTN_TR2);
        CHECK(normalize(c, s)[analog] == 100 && normalize(c, s)[digital] == 255);
        // Removing the established right stick must not turn Z/RZ into triggers.
        c.axes.reset(ABS_RX); c.axes.reset(ABS_RY); s.keys.reset();
        CHECK(normalize(c, s)[1] == 0 && normalize(c, s)[2] == 0);
    }
    auto c = standard(true); c.axes.reset(ABS_Z); c.axes.reset(ABS_RZ); auto s = rest(c);
    for (bool left : {false, true}) for (bool right : {false, true}) {
        s.keys[BTN_TL2] = left; s.keys[BTN_TR2] = right;
        CHECK(normalize(c, s)[1] == (left ? 255 : 0));
        CHECK(normalize(c, s)[2] == (right ? 255 : 0));
    }
}
void independentAlternateTriggers() {
    for (auto codes : {std::pair{ABS_BRAKE, ABS_GAS}, std::pair{ABS_HAT2Y, ABS_HAT2X}}) {
        for (bool left : {false, true}) {
            auto c = standard(true); const int code = left ? codes.first : codes.second;
            axis(c, code, 0, 255); auto s = rest(c);
            s.axes[code] = 100; s.axes[left ? ABS_RZ : ABS_Z] = 60;
            s.keys.set(BTN_TL2); s.keys.set(BTN_TR2);
            CHECK(normalize(c, s)[left ? 1 : 2] == 100);
            CHECK(normalize(c, s)[left ? 2 : 1] == 60);
        }
    }
    auto c = standard(true);
    axis(c, ABS_BRAKE, 0, 255); axis(c, ABS_GAS, 0, 255);
    axis(c, ABS_HAT2Y, 0, 255); axis(c, ABS_HAT2X, 0, 255);
    auto s = rest(c); s.axes[ABS_BRAKE] = 20; s.axes[ABS_GAS] = 60;
    s.axes[ABS_HAT2Y] = 100; s.axes[ABS_HAT2X] = 100;
    s.axes[ABS_Z] = 255; s.axes[ABS_RZ] = 255;
    CHECK(normalize(c, s)[1] == 20 && normalize(c, s)[2] == 60);
}
void eventFixtures() {
    for (int kind = 0; kind < 3; ++kind) {
        FakeSystem sys; auto& p = sys.add("event0");
        p.caps = standard(kind == 1);
        if (kind == 2) { axis(p.caps, ABS_X, -1000, 1000); p.caps.keys.set(BTN_DPAD_UP); }
        p.state = rest(p.caps);
        Reader reader(sys); std::array<int, 64> out{}; CHECK(reader.poll(255, out.data()) == 1);
        p.events = {event(EV_KEY, BTN_SOUTH, 1), event(EV_ABS, ABS_X, p.caps.ranges[ABS_X].maximum),
            event(EV_ABS, ABS_Z, p.caps.ranges[ABS_Z].maximum), event(EV_SYN, SYN_REPORT, 0)};
        CHECK(reader.poll(1, out.data()) == 1);
        CHECK(out[0] == 0x1000 && out[1] == 255 && out[3] == 32767 && out[7] == 1);
    }
}
void reportBoundary() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); p.events.push_back(event(EV_KEY, BTN_SOUTH, 1));
    r.poll(1, out.data()); CHECK(out[0] == 0);
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0)); r.poll(1, out.data()); CHECK(out[0] == 0x1000);
    p.events = {event(EV_KEY, BTN_SOUTH, 0), event(EV_SYN, SYN_REPORT, 0)};
    r.poll(1, out.data()); CHECK(out[0] == 0);
}
void droppedRecovery() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); p.state.keys.set(BTN_EAST);
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_KEY, BTN_SOUTH, 1)};
    r.poll(1, out.data()); CHECK(sys.snapshots == 0 && out[0] == 0);
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0)); r.poll(1, out.data());
    CHECK(sys.snapshots == 1 && out[0] == 0x2000);
    p.snapshotFailed = true; p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    CHECK(r.poll(1, out.data()) == 0 && out[0] == 0 && sys.handles.empty());
}
void droppedBacklogAcrossPolls() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0),
        event(EV_ABS, ABS_X, 32767), event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0)};
    for (int i = 0; i < MaxEventsPerPoll * 3; ++i) p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    p.events.push_back(event(EV_ABS, ABS_X, 0)); p.events.push_back(event(EV_KEY, BTN_SOUTH, 0));
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    int polls = 0;
    do {
        const int before = sys.reads;
        CHECK(r.poll(1, out.data()) == 1);
        CHECK(out[0] == 0 && out[3] == 0);
        CHECK(sys.reads - before <= MaxEventsPerPoll);
        CHECK(++polls <= 5);
    } while (!p.events.empty());
    CHECK(polls > 1 && sys.snapshots == 1 && out[7] == 1);
    // Events after the verified snapshot still follow normal report boundaries.
    p.events = {event(EV_ABS, ABS_X, 32767), event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0)};
    r.poll(1, out.data()); CHECK(out[0] == 0x1000 && out[3] == 32767);
}
void droppedSnapshotArrivals() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    p.afterSnapshot = [&](FakeSystem::Pad& pad) {
        if (sys.snapshots != 1) return;
        pad.state.keys.set(BTN_EAST); pad.state.axes[ABS_X] = -32768;
        pad.events = {event(EV_ABS, ABS_X, 32767), event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0)};
        for (int i = 0; i < MaxEventsPerPoll; ++i) pad.events.push_back(event(EV_SYN, SYN_REPORT, 0));
        pad.events.push_back(event(EV_ABS, ABS_X, -32768));
        pad.events.push_back(event(EV_KEY, BTN_SOUTH, 0));
        pad.events.push_back(event(EV_KEY, BTN_EAST, 1));
        pad.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    };
    int before = sys.reads; r.poll(1, out.data());
    CHECK(sys.reads - before == MaxEventsPerPoll && out[0] == 0 && out[3] == 0 && out[7] == 0);
    before = sys.reads; r.poll(1, out.data());
    CHECK(sys.reads - before <= MaxEventsPerPoll && sys.snapshots == 2);
    CHECK(out[0] == 0x2000 && out[3] == -32767 && out[7] == 1);
}
void droppedPartialReportAndDisconnect() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    p.afterSnapshot = [&](FakeSystem::Pad& pad) {
        if (sys.snapshots != 1) return;
        pad.state.axes[ABS_X] = 32767;
        pad.events.push_back(event(EV_ABS, ABS_X, 32767));
    };
    r.poll(1, out.data()); CHECK(sys.snapshots == 1 && out[3] == 0);
    r.poll(1, out.data()); CHECK(sys.snapshots == 1 && out[3] == 0);
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    r.poll(1, out.data()); CHECK(sys.snapshots == 2 && out[3] == 32767);
    p.events = {event(EV_SYN, SYN_DROPPED, 0)}; r.poll(1, out.data());
    p.failed = true;
    CHECK(r.poll(1, out.data()) == 0 && out[3] == 0 && sys.handles.empty() && sys.closes == 1);
}
void droppedContinuousArrivalsBounded() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    p.afterSnapshot = [](FakeSystem::Pad& pad) {
        pad.events.push_back(event(EV_ABS, ABS_X, 32767));
        pad.events.push_back(event(EV_KEY, BTN_SOUTH, 1));
        pad.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    };
    for (int poll = 0; poll < 4; ++poll) {
        const int reads = sys.reads, snapshots = sys.snapshots;
        CHECK(r.poll(1, out.data()) == 1);
        CHECK(sys.reads - reads == MaxEventsPerPoll && sys.snapshots - snapshots <= MaxEventsPerPoll);
        CHECK(out[0] == 0 && out[3] == 0 && out[7] == 0);
    }
    p.afterSnapshot = {}; p.state.keys.set(BTN_EAST); p.state.axes[ABS_X] = -32768;
    r.poll(1, out.data()); CHECK(out[0] == 0x2000 && out[3] == -32767 && out[7] == 1);
}
void droppedRollsBackUnpublishedReports() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_ABS, ABS_X, 32767), event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0),
        event(EV_SYN, SYN_DROPPED, 0)};
    r.poll(1, out.data()); CHECK(out[0] == 0 && out[3] == 0 && out[7] == 0);
    // A repeated overflow restarts the report barrier.
    p.events = {event(EV_SYN, SYN_REPORT, 0), event(EV_SYN, SYN_DROPPED, 0)};
    r.poll(1, out.data()); CHECK(sys.snapshots == 0 && out[3] == 0);
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    p.state.keys.set(BTN_EAST); p.state.axes[ABS_X] = -32768;
    r.poll(1, out.data()); CHECK(out[0] == 0x2000 && out[3] == -32767);
    // Retain the last published state, rather than inventing a release during resync.
    p.events.push_back(event(EV_SYN, SYN_DROPPED, 0));
    r.poll(1, out.data()); CHECK(out[0] == 0x2000 && out[3] == -32767);
}
void droppedSnapshotBudgetBoundary() {
    for (bool arrivals : {false, true}) {
        FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
        r.poll(255, out.data()); p.state.keys.set(BTN_EAST); p.state.axes[ABS_X] = -32768;
        p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
        for (int i = 0; i < MaxEventsPerPoll - 3; ++i) p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
        int before = sys.reads; r.poll(1, out.data());
        CHECK(sys.reads - before == MaxEventsPerPoll && sys.snapshots == 1);
        CHECK(out[0] == 0 && out[3] == 0 && out[7] == 0); // Candidate is not published without verification.
        if (arrivals) {
            p.state = rest(p.caps);
            p.events = {event(EV_ABS, ABS_X, 0), event(EV_KEY, BTN_EAST, 0), event(EV_SYN, SYN_REPORT, 0)};
        }
        r.poll(1, out.data());
        CHECK(out[0] == (arrivals ? 0 : 0x2000) && out[3] == (arrivals ? 0 : -32767));
        CHECK(sys.snapshots == (arrivals ? 2 : 1) && out[7] == 1);
    }
}
void droppedInterruptedReadIsNotEmpty() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); p.state.keys.set(BTN_EAST); p.state.axes[ABS_X] = -32768;
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    p.afterSnapshot = [&](FakeSystem::Pad& pad) {
        if (sys.snapshots == 1) pad.emptyInterruptions = MaxEventsPerPoll;
    };
    const int before = sys.reads; r.poll(1, out.data());
    CHECK(sys.reads - before == MaxEventsPerPoll && sys.snapshots == 1);
    CHECK(out[0] == 0 && out[3] == 0 && out[7] == 0);
    r.poll(1, out.data()); CHECK(out[0] == 0x2000 && out[3] == -32767 && out[7] == 1);
}
void boundedDrain() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); sys.reads = 0;
    for (int i = 0; i < 1000; ++i) p.events.push_back(event(EV_ABS, ABS_X, i));
    r.poll(1, out.data()); CHECK(sys.reads == MaxEventsPerPoll && p.events.size() == 1000 - MaxEventsPerPoll);
    CHECK(out[3] == 0); // No partial report published.
}
void stableSlotsAndIdenticalModels() {
    FakeSystem sys; sys.add("event0").state.keys.set(BTN_SOUTH); sys.add("event1").state.keys.set(BTN_EAST);
    Reader r(sys); std::array<int, 64> out{};
    CHECK(r.poll(255, out.data()) == 3 && out[0] == 0x1000 && out[8] == 0x2000);
    std::reverse(sys.nodes.begin(), sys.nodes.end());
    CHECK(r.poll(255, out.data()) == 3 && out[0] == 0x1000 && out[8] == 0x2000 && sys.opens == 2);
    sys.nodes.push_back(sys.nodes.front()); r.poll(255, out.data()); CHECK(sys.opens == 2);
}
void disconnectAndReuse() {
    FakeSystem sys; auto& p = sys.add("event0"); p.state.keys.set(BTN_SOUTH); p.state.axes[ABS_X] = 32767;
    Reader r(sys); std::array<int, 64> out{}; r.poll(255, out.data());
    p.failed = true; CHECK(r.poll(1, out.data()) == 0 && out[0] == 0 && out[3] == 0 && sys.closes == 1);
    p.failed = false; p.state = rest(p.caps);
    CHECK(r.poll(255, out.data()) == 1 && out[0] == 0 && sys.opens == 2);
}
void replacedNodeHasDisconnectBarrier() {
    FakeSystem sys; sys.add("event0"); Reader r(sys); std::array<int, 64> out{}; r.poll(255, out.data());
    sys.nodes[0].inode = 2;
    const int mask = r.poll(255, out.data()); CHECK((mask & 1) == 0 && sys.closes == 1);
    // Replacement may occupy another free slot immediately, but never the retired slot.
    CHECK(mask == 2); CHECK(r.poll(255, out.data()) == 2);
}
void unplugFromDirectory() {
    FakeSystem sys; sys.add("event0"); Reader r(sys); std::array<int, 64> out{}; r.poll(255, out.data());
    sys.nodes.clear(); CHECK(r.poll(255, out.data()) == 0 && sys.handles.empty());
}
void slotLimit() {
    FakeSystem sys; for (int i = 0; i < 10; ++i) sys.add("event" + std::to_string(i));
    Reader r(sys); std::array<int, 64> out{};
    CHECK(r.poll(255, out.data()) == 255 && sys.handles.size() == 8);
    sys.nodes.erase(sys.nodes.begin());
    CHECK(r.poll(255, out.data()) == 254); // Full capacity: retired slot stays clear for one poll.
    CHECK(r.poll(255, out.data()) == 255 && sys.handles.size() == 8);
}
void permissionAndDirectoryRecovery() {
    FakeSystem sys; auto& p = sys.add("event0"); p.denied = true;
    Reader r(sys); std::array<int, 64> out{}; CHECK(r.poll(255, out.data()) == 0);
    p.denied = false; CHECK(r.poll(255, out.data()) == 1);
    sys.directoryFailed = true; CHECK(r.poll(255, out.data()) == 1);
    sys.directoryFailed = false; CHECK(r.poll(255, out.data()) == 1);
}
void discoveryCadenceAndCleanup() {
    FakeSystem sys; sys.add("event0"); std::array<int, 64> out{};
    for (int i = 0; i < 20; ++i) {
        Reader r(sys); CHECK(r.poll(255, out.data()) == 1);
        const int scans = sys.enumerations;
        for (int j = 0; j < 10; ++j) r.poll(1, out.data());
        CHECK(sys.enumerations == scans);
    }
    CHECK(sys.opens == 20 && sys.closes == 20 && sys.handles.empty());
}
void rejectUnrelatedDevices() {
    FakeSystem sys; sys.add("sensor").caps.accelerometer = true; sys.add("pad");
    Reader r(sys); std::array<int, 64> out{};
    CHECK(r.poll(255, out.data()) == 1 && sys.closes == 1 && sys.handles.size() == 1);
}
void fullMaskDoesNotFloodDiscovery() {
    FakeSystem sys; sys.clockStep = 0;
    for (int i = 0; i < 8; ++i) sys.add("event" + std::to_string(i));
    Reader r(sys); std::array<int, 64> out{};
    for (int i = 0; i < 100; ++i) CHECK(r.poll(255, out.data()) == 255);
    CHECK(sys.enumerations == 1);
    sys.time = DiscoveryIntervalMs;
    r.poll(255, out.data()); CHECK(sys.enumerations == 2);
}

void dualSenseMicButtonFromHidraw() {
    for (bool bluetooth : {false, true}) {
        resetConfig();
        FakeSystem sys; auto& p = dualSense(sys, bluetooth); Reader r(sys); std::array<int, 64> out{};
        r.poll(255, out.data());
        // Voice control off: the hidraw node is not even opened, as upstream asks for full reports only then.
        p.reports.push_back(micReport(bluetooth, true));
        r.poll(1, out.data()); CHECK(sys.hidOpens == 0 && out[0] == 0);
        configure(true, 0, 0, false);
        r.poll(1, out.data()); CHECK(sys.hidOpens == 1 && out[0] == DualSenseMicBit);
        // Nothing new queued: the button stays held, as the evdev state does between reports.
        r.poll(1, out.data()); CHECK(out[0] == DualSenseMicBit);
        p.reports = {micReport(bluetooth, true), micReport(bluetooth, false)};
        r.poll(1, out.data()); CHECK(out[0] == 0);
        // The mic bit rides beside the evdev buttons.
        p.reports.push_back(micReport(bluetooth, true));
        p.events = {event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0)};
        r.poll(1, out.data()); CHECK(out[0] == (0x1000 | DualSenseMicBit));
        // Voice control off again: the mic is let go.
        configure(false, 0, 0, false);
        r.poll(1, out.data()); CHECK(out[0] == 0x1000);
        CHECK(sys.pads.at("event0").written.empty());
    }
    resetConfig();
}
void dualSenseReportsWithoutTheMicByte() {
    const auto usb = micReport(false, true), bt = micReport(true, true);
    CHECK(dualSenseMicButton(false, usb.data(), usb.size()) == 1);
    CHECK(dualSenseMicButton(true, bt.data(), bt.size()) == 1);
    // Bluetooth's short 0x01 report carries a frame counter where the mic byte would be.
    std::vector<unsigned char> shortReport(10, 0xFF); shortReport[0] = 0x01;
    CHECK(dualSenseMicButton(true, shortReport.data(), shortReport.size()) == -1);
    // Other report ids (feature or audio reports) and truncated ones say nothing.
    auto other = usb; other[0] = 0x05; CHECK(dualSenseMicButton(false, other.data(), other.size()) == -1);
    CHECK(dualSenseMicButton(false, usb.data(), 10) == -1);
    CHECK(dualSenseMicButton(true, bt.data(), 11) == -1);
}
void onlyADualSenseHasAHidSide() {
    configure(true, 2, 0x112233, true);
    FakeSystem sys; auto& p = sys.add("event0"); p.caps = standard(true);
    p.caps.id.bustype = BUS_USB; p.caps.id.vendor = 0x054c; p.caps.id.product = 0x09cc; // DualShock 4
    p.hidPresent = true; p.ledsWritable = true; p.reports.push_back(micReport(false, true));
    auto& xbox = sys.add("event1"); xbox.caps.id.bustype = BUS_USB; xbox.caps.id.vendor = 0x045e; xbox.hidPresent = true;
    Reader r(sys); std::array<int, 64> out{};
    CHECK(r.poll(255, out.data()) == 3 && r.poll(3, out.data()) == 3);
    CHECK(sys.hidOpens == 0 && p.ledWrites.empty() && out[0] == 0);
    resetConfig();
}
void dualSenseLightsPreferTheLedFiles() {
    resetConfig();
    FakeSystem sys; auto& p = dualSense(sys, false); p.ledsWritable = true; Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); CHECK(p.ledWrites.empty()); // Leave as is: nothing is ever written.
    configure(false, 2, 0x3366FF, true);
    r.poll(1, out.data()); r.poll(1, out.data());
    CHECK(p.ledWrites.size() == 1 && p.ledWrites[0] == std::make_tuple(2, 0x3366FFu, true));
    CHECK(sys.hidOpens == 0 && p.written.empty());
    // Only a change writes again; the same settings sent twice a second do not.
    configure(false, 2, 0x3366FF, true); r.poll(1, out.data()); CHECK(p.ledWrites.size() == 1);
    configure(false, 1, 0x3366FF, false); r.poll(1, out.data());
    CHECK(p.ledWrites.size() == 2 && p.ledWrites[1] == std::make_tuple(1, 0x3366FFu, false));
    // Back to Leave as is: nothing is written, the pad keeps what it shows.
    configure(false, 0, 0, false); r.poll(1, out.data()); CHECK(p.ledWrites.size() == 2);
    resetConfig();
}
void dualSenseLightsOverHidraw() {
    for (bool bluetooth : {false, true}) {
        resetConfig();
        FakeSystem sys; auto& p = dualSense(sys, bluetooth); Reader r(sys); std::array<int, 64> out{};
        r.poll(255, out.data());
        configure(false, 2, 0x3366FF, true);
        r.poll(1, out.data());
        CHECK(sys.hidOpens == 1 && p.written.size() == 1);
        const auto& first = p.written[0];
        const size_t header = bluetooth ? 3 : 1;
        CHECK(first.size() == (bluetooth ? 78u : 63u) && first[0] == (bluetooth ? 0x31 : 0x02));
        const unsigned char* common = first.data() + header;
        CHECK(common[0] == 0 && common[1] == 0x14 && common[38] == 0x02 && common[41] == 0x02);
        CHECK(common[43] == 0x04 && common[44] == 0x33 && common[45] == 0x66 && common[46] == 0xFF);
        if (bluetooth) {
            CHECK(first[1] == 0x00 && first[2] == 0x10);
            std::vector<unsigned char> signed_(1, 0xA2); signed_.insert(signed_.end(), first.begin(), first.end() - 4);
            const uint32_t crc = referenceCrc(signed_);
            CHECK(first[74] == (crc & 0xFF) && first[75] == ((crc >> 8) & 0xFF) &&
                  first[76] == ((crc >> 16) & 0xFF) && first[77] == ((crc >> 24) & 0xFF));
        }
        // The next change: no light-bar setup again, the light bar off, the player light off, the next sequence.
        configure(false, 1, 0x3366FF, false);
        r.poll(1, out.data());
        CHECK(p.written.size() == 2);
        const auto& second = p.written[1];
        const unsigned char* again = second.data() + header;
        CHECK(again[38] == 0 && again[41] == 0 && again[43] == 0 && again[44] == 0 && again[45] == 0 && again[46] == 0);
        if (bluetooth) CHECK(second[1] == 0x10);
        // A reconnected pad gets the settings again, with the setup.
        p.failed = true; r.poll(1, out.data()); CHECK(sys.hidCloses == 1);
        p.failed = false; r.poll(255, out.data()); r.poll(1, out.data());
        CHECK(p.written.size() == 3 && p.written[2][header + 41] == 0x02);
    }
    resetConfig();
}
void dualSenseWithoutHidAccess() {
    resetConfig();
    FakeSystem sys; auto& p = dualSense(sys, false); p.hidPresent = false; Reader r(sys); std::array<int, 64> out{};
    configure(true, 2, 0xFF0000, true);
    p.events = {event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0)};
    CHECK(r.poll(255, out.data()) == 1 && r.poll(1, out.data()) == 1 && r.poll(1, out.data()) == 1);
    // Input still works; the node is asked for once per connection and the log says why once.
    CHECK(out[0] == 0x1000 && sys.hidOpens == 1 && sys.logs.size() == 1);
    resetConfig();
}
void dualSenseHidFailureKeepsTheGamepad() {
    resetConfig();
    configure(true, 0, 0, false);
    FakeSystem sys; auto& p = dualSense(sys, true); Reader r(sys); std::array<int, 64> out{};
    p.reports.push_back(micReport(true, true));
    r.poll(255, out.data()); r.poll(1, out.data()); CHECK(out[0] == DualSenseMicBit);
    p.hidFailed = true;
    CHECK(r.poll(1, out.data()) == 1 && out[0] == 0 && sys.hidCloses == 1 && sys.closes == 0);
    // Not reopened every poll.
    r.poll(1, out.data()); CHECK(sys.hidOpens == 1);
    resetConfig();
}
void hidrawBacklogIsDrained() {
    resetConfig();
    configure(true, 0, 0, false);
    FakeSystem sys; auto& p = dualSense(sys, false); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    for (int i = 0; i < 300; ++i) p.reports.push_back(micReport(false, i % 2 == 0));
    p.reports.push_back(micReport(false, true));
    r.poll(1, out.data()); CHECK(p.reports.empty() && out[0] == DualSenseMicBit);
    resetConfig();
}

int main() {
    const std::pair<const char*, std::function<void()>> tests[] = {
        {"qualification", qualification}, {"buttons and both dpad forms", buttonsAndHats},
        {"stick normalization", stickNormalization}, {"trigger normalization", triggerNormalization},
        {"alternate triggers", alternateTriggers}, {"digital and ambiguous triggers", digitalAndAmbiguousTriggers},
        {"hybrid analog triggers authoritative", hybridAnalogTriggersWin},
        {"independent analog and digital triggers", independentAnalogTriggers},
        {"independent alternate trigger sources", independentAlternateTriggers},
        {"Xbox PlayStation generic event fixtures", eventFixtures}, {"report boundary", reportBoundary},
        {"SYN_DROPPED recovery", droppedRecovery}, {"bounded drain", boundedDrain},
        {"SYN_DROPPED backlog across polls", droppedBacklogAcrossPolls},
        {"SYN_DROPPED arrivals during snapshot", droppedSnapshotArrivals},
        {"SYN_DROPPED partial report and disconnect", droppedPartialReportAndDisconnect},
        {"SYN_DROPPED continuous arrivals bounded", droppedContinuousArrivalsBounded},
        {"SYN_DROPPED unpublished reports and repeated overflow", droppedRollsBackUnpublishedReports},
        {"SYN_DROPPED snapshot at poll budget boundary", droppedSnapshotBudgetBoundary},
        {"SYN_DROPPED interrupted read is not EAGAIN", droppedInterruptedReadIsNotEmpty},
        {"stable slots and identical models", stableSlotsAndIdenticalModels}, {"disconnect and reuse", disconnectAndReuse},
        {"replacement barrier", replacedNodeHasDisconnectBarrier}, {"directory removal", unplugFromDirectory},
        {"eight slot limit", slotLimit}, {"permission recovery", permissionAndDirectoryRecovery},
        {"discovery cadence and cleanup", discoveryCadenceAndCleanup}, {"unrelated nodes", rejectUnrelatedDevices},
        {"full mask scan limit", fullMaskDoesNotFloodDiscovery},
        {"DualSense mic button from hidraw", dualSenseMicButtonFromHidraw},
        {"DualSense reports without the mic byte", dualSenseReportsWithoutTheMicByte},
        {"only a DualSense has a HID side", onlyADualSenseHasAHidSide},
        {"DualSense lights prefer the LED files", dualSenseLightsPreferTheLedFiles},
        {"DualSense lights over hidraw", dualSenseLightsOverHidraw},
        {"DualSense without hidraw access", dualSenseWithoutHidAccess},
        {"DualSense hidraw failure keeps the gamepad", dualSenseHidFailureKeepsTheGamepad},
        {"hidraw backlog drained", hidrawBacklogIsDrained},
    };
    int failed = 0;
    for (const auto& [name, test] : tests) {
        try { test(); std::cout << "PASS " << name << '\n'; }
        catch (const std::exception& e) { ++failed; std::cerr << "FAIL " << name << ": " << e.what() << '\n'; }
    }
    std::cout << std::size(tests) << " tests, " << failed << " failures\n";
    return failed ? 1 : 0;
}
