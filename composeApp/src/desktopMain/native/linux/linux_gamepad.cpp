#include "linux_gamepad.h"

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <chrono>
#include <dirent.h>
#include <fcntl.h>
#include <cstdio>
#include <linux/hidraw.h>
#include <memory>
#include <mutex>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace nuvio::gamepad {
namespace {
bool axis(const Capabilities& caps, int code) {
    return caps.axes[code] && caps.ranges[code].maximum > caps.ranges[code].minimum;
}
bool pair(const Capabilities& caps, int x, int y) { return axis(caps, x) && axis(caps, y); }

int scale(const Capabilities& caps, const State& state, int code, bool stick = false) {
    if (!axis(caps, code)) return 0;
    const auto& range = caps.ranges[code];
    const int64_t low = range.minimum, high = range.maximum;
    const int64_t value = std::clamp<int64_t>(state.axes[code], low, high);
    if (!stick) return static_cast<int>((value - low) * 255 / (high - low));
    // Integer midpoint matches common 0..255 and -32768..32767 kernel ranges.
    const int64_t center = low + (high - low + 1) / 2;
    if (value == center) return 0;
    return value < center
        ? static_cast<int>(-(center - value) * 32767 / (center - low))
        : static_cast<int>((value - center) * 32767 / (high - center));
}

class PosixSystem final : public System {
    template<size_t N> static bool bits(int fd, unsigned long request, std::bitset<N>& result) {
        std::array<unsigned char, (N + 7) / 8> bytes{};
        if (ioctl(fd, request, bytes.data()) < 0) return false;
        result.reset();
        for (size_t i = 0; i < N; ++i) result[i] = (bytes[i / 8] & (1u << (i % 8))) != 0;
        return true;
    }
public:
    uint64_t nowMs() override {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    bool enumerate(std::vector<Node>& nodes) override {
        const auto closeDirectory = [](DIR* directory) { closedir(directory); };
        const std::unique_ptr<DIR, decltype(closeDirectory)> directory(opendir("/dev/input"), closeDirectory);
        if (!directory) return false;
        // Count every entry, including unrelated entries, to bound discovery work.
        for (int count = 0; count < MaxDiscoveryEntries; ++count) {
            const auto* entry = readdir(directory.get());
            if (!entry) break;
            const std::string name(entry->d_name);
            if (name.size() <= 5 || name.compare(0, 5, "event") != 0 ||
                !std::all_of(name.begin() + 5, name.end(), [](unsigned char c) { return std::isdigit(c); })) continue;
            const std::string path = "/dev/input/" + name;
            struct stat info{};
            if (lstat(path.c_str(), &info) == 0 && S_ISCHR(info.st_mode))
                nodes.push_back({path, static_cast<uint64_t>(info.st_rdev), static_cast<uint64_t>(info.st_ino)});
        }
        std::sort(nodes.begin(), nodes.end(), [](const Node& a, const Node& b) { return a.path < b.path; });
        return true;
    }

    int open(const Node& node, Capabilities& caps, State& state) override {
        const int fd = ::open(node.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) return -1; // EACCES/ENOENT are retried at the next discovery pass.
        struct stat info{};
        std::bitset<INPUT_PROP_CNT> properties;
        bool valid = fstat(fd, &info) == 0 && S_ISCHR(info.st_mode) &&
            node.device == static_cast<uint64_t>(info.st_rdev) && node.inode == static_cast<uint64_t>(info.st_ino) &&
            bits(fd, EVIOCGBIT(EV_KEY, (KEY_CNT + 7) / 8), caps.keys) &&
            bits(fd, EVIOCGBIT(EV_ABS, (ABS_CNT + 7) / 8), caps.axes);
        if (bits(fd, EVIOCGPROP((INPUT_PROP_CNT + 7) / 8), properties))
            caps.accelerometer = properties[INPUT_PROP_ACCELEROMETER];
        if (ioctl(fd, EVIOCGID, &caps.id) < 0) caps.id = input_id{};
        for (int i = 0; valid && i < ABS_CNT; ++i) {
            if (caps.axes[i] && ioctl(fd, EVIOCGABS(i), &caps.ranges[i]) < 0) valid = false;
        }
        if (!valid || !qualifies(caps) || !snapshot(fd, caps, state)) {
            ::close(fd);
            return -1;
        }
        return fd;
    }

    ReadResult read(int fd, input_event& event) override {
        const auto size = ::read(fd, &event, sizeof(event));
        if (size == sizeof(event)) return ReadResult::Event;
        if (size < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return ReadResult::Empty;
        if (size < 0 && errno == EINTR) return ReadResult::Interrupted;
        return ReadResult::Failed;
    }

    bool snapshot(int fd, const Capabilities& caps, State& state) override {
        State fresh;
        if (!bits(fd, EVIOCGKEY((KEY_CNT + 7) / 8), fresh.keys)) return false;
        for (int i = 0; i < ABS_CNT; ++i) {
            if (!caps.axes[i]) continue;
            input_absinfo info{};
            if (ioctl(fd, EVIOCGABS(i), &info) < 0) return false;
            fresh.axes[i] = info.value;
        }
        state = fresh;
        return true;
    }
    void close(int fd) override { ::close(fd); }

    // /sys/class/input/eventN/device is the input device, and its parent the HID device that owns the
    // hidraw node and the LED files: the same pad, never a guess by model.
    static std::string hidDirectory(const Node& node) {
        const auto slash = node.path.rfind('/');
        return "/sys/class/input/" + node.path.substr(slash + 1) + "/device/device";
    }
    static std::vector<std::string> entries(const std::string& directory) {
        std::vector<std::string> names;
        const auto closeDirectory = [](DIR* d) { closedir(d); };
        const std::unique_ptr<DIR, decltype(closeDirectory)> handle(opendir(directory.c_str()), closeDirectory);
        if (!handle) return names;
        for (int count = 0; count < MaxDiscoveryEntries; ++count) {
            const auto* entry = readdir(handle.get());
            if (!entry) break;
            if (entry->d_name[0] != '.') names.emplace_back(entry->d_name);
        }
        std::sort(names.begin(), names.end());
        return names;
    }
    static bool writeFile(const std::string& path, const std::string& text) {
        const int fd = ::open(path.c_str(), O_WRONLY | O_CLOEXEC);
        if (fd < 0) return false;
        const bool ok = ::write(fd, text.data(), text.size()) == static_cast<ssize_t>(text.size());
        ::close(fd);
        return ok;
    }

    int openHid(const Node& node) override {
        struct stat input{};
        if (stat(node.path.c_str(), &input) != 0 || static_cast<uint64_t>(input.st_rdev) != node.device) return -1;
        for (const auto& name : entries(hidDirectory(node) + "/hidraw")) {
            if (name.compare(0, 6, "hidraw") != 0) continue;
            const std::string path = "/dev/" + name;
            int fd = ::open(path.c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
            if (fd < 0) fd = ::open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
            if (fd < 0) return -1;
            hidraw_devinfo info{};
            if (ioctl(fd, HIDIOCGRAWINFO, &info) < 0 || static_cast<uint16_t>(info.vendor) != 0x054c) {
                ::close(fd);
                return -1;
            }
            return fd;
        }
        return -1;
    }
    int readHid(int fd, unsigned char* buffer, size_t size) override {
        for (;;) {
            const auto length = ::read(fd, buffer, size);
            if (length > 0) return static_cast<int>(length);
            if (length < 0 && errno == EINTR) continue;
            if (length < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
            return -1;
        }
    }
    bool writeHid(int fd, const std::vector<unsigned char>& report) override {
        for (;;) {
            const auto written = ::write(fd, report.data(), report.size());
            if (written < 0 && errno == EINTR) continue;
            return written == static_cast<ssize_t>(report.size());
        }
    }
    bool writeLeds(const Node& node, int lightMode, unsigned int rgb, bool playerLight) override {
        const std::string leds = hidDirectory(node) + "/leds/";
        std::string bar;
        std::vector<std::pair<std::string, int>> players;
        for (const auto& name : entries(leds)) {
            const auto ends = [&](const std::string& suffix) {
                return name.size() > suffix.size() && name.compare(name.size() - suffix.size(), suffix.size(), suffix) == 0;
            };
            if (ends(":rgb:indicator")) bar = leds + name;
            const auto player = name.find(":white:player-");
            if (player != std::string::npos && name.size() == player + 15)
                players.emplace_back(leds + name, name.back() - '0');
        }
        if (bar.empty() || players.size() != 5) return false;
        const unsigned int color = lightMode == 2 ? rgb : 0u;
        char intensity[32];
        std::snprintf(intensity, sizeof(intensity), "%u %u %u", (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF);
        // Root-owned on a stock system: a write that is refused leaves the pad as it was, and hidraw is tried.
        if (!writeFile(bar + "/multi_intensity", intensity) || !writeFile(bar + "/brightness", color ? "255" : "0")) return false;
        for (const auto& [path, index] : players) {
            if (!writeFile(path + "/brightness", playerLight && index == 3 ? "1" : "0")) return false;
        }
        return true;
    }
    void closeHid(int fd) override { ::close(fd); }
    void log(const std::string& line) override { std::fprintf(stderr, "Linux %s\n", line.c_str()); }
};

std::mutex configLock;
PadConfig currentConfig{false, 0, 0, false, 1};
} // namespace

void configure(bool fullReports, int lightMode, unsigned int rgb, bool playerLight) {
    std::lock_guard<std::mutex> guard(configLock);
    const bool lightsChanged = currentConfig.lightMode != lightMode || currentConfig.rgb != rgb ||
        currentConfig.playerLight != playerLight;
    currentConfig.fullReports = fullReports;
    currentConfig.lightMode = lightMode;
    currentConfig.rgb = rgb & 0xFFFFFFu;
    currentConfig.playerLight = playerLight;
    if (lightsChanged) ++currentConfig.generation;
}

PadConfig padConfig() {
    std::lock_guard<std::mutex> guard(configLock);
    return currentConfig;
}

bool isDualSense(const input_id& id) {
    // DualSense and DualSense Edge, over USB or Bluetooth. A DualShock 4 has neither the mic nor this report.
    return id.vendor == 0x054c && (id.product == 0x0ce6 || id.product == 0x0df2) &&
        (id.bustype == BUS_USB || id.bustype == BUS_BLUETOOTH);
}

// CRC-32 (zlib's), which a DualSense checks on every Bluetooth output report.
uint32_t crc32(uint32_t crc, const unsigned char* data, size_t length) {
    static const auto table = [] {
        std::array<uint32_t, 256> values{};
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            values[i] = c;
        }
        return values;
    }();
    crc = ~crc;
    for (size_t i = 0; i < length; ++i) crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

std::vector<unsigned char> dualSenseLightReport(bool bluetooth, int lightMode, unsigned int rgb, bool playerLight,
                                                bool setup, unsigned char sequence) {
    // Upstream's Windows writeLights(): the common block behind 0x02 (USB) or 0x31 + sequence/tag (Bluetooth).
    std::vector<unsigned char> report(bluetooth ? DualSenseBluetoothOutputSize : DualSenseUsbOutputSize, 0);
    const size_t header = bluetooth ? 3 : 1;
    if (bluetooth) {
        report[0] = 0x31;
        report[1] = static_cast<unsigned char>((sequence & 0x0F) << 4);
        report[2] = 0x10; // "HID data" tag
    } else {
        report[0] = 0x02;
    }
    unsigned char* common = report.data() + header;
    common[1] = 0x04 | 0x10; // valid_flag1: light bar control, player indicator control
    if (setup) {
        common[38] = 0x02; // valid_flag2: light bar setup control
        common[41] = 0x02; // light bar setup: light out (the fade the pad shows before a host takes over)
    }
    common[42] = 0x00; // player light brightness: high
    common[43] = playerLight ? 0x04 : 0x00; // the middle of the five player lights: player 1
    const unsigned int color = lightMode == 2 ? rgb : 0u;
    common[44] = static_cast<unsigned char>((color >> 16) & 0xFF);
    common[45] = static_cast<unsigned char>((color >> 8) & 0xFF);
    common[46] = static_cast<unsigned char>(color & 0xFF);
    if (bluetooth) {
        const unsigned char seed = 0xA2; // the output report header the CRC starts from
        const size_t at = DualSenseBluetoothOutputSize - 4;
        const uint32_t crc = crc32(crc32(0, &seed, 1), report.data(), at);
        for (int i = 0; i < 4; ++i) report[at + i] = static_cast<unsigned char>((crc >> (8 * i)) & 0xFF);
    }
    return report;
}

int dualSenseMicButton(bool bluetooth, const unsigned char* report, size_t length) {
    // The third button byte (PS 0x01, touchpad 0x02, mic 0x04): USB report 0x01 has it at 10, the
    // Bluetooth full report 0x31 one byte later. Bluetooth's short 0x01 report has no mic button, but
    // hid-playstation always switches the pad to full reports when it binds.
    if (!bluetooth && length >= 11 && report[0] == 0x01) return (report[10] & 0x04) ? 1 : 0;
    if (bluetooth && length >= 12 && report[0] == 0x31) return (report[11] & 0x04) ? 1 : 0;
    return -1;
}

bool qualifies(const Capabilities& caps) {
    // Standard gamepad face controls plus navigation. Reject sensor and touch collections.
    return !caps.accelerometer && !caps.keys[BTN_TOUCH] && !caps.axes[ABS_MT_POSITION_X] &&
        caps.keys[BTN_SOUTH] && caps.keys[BTN_EAST] &&
        (pair(caps, ABS_X, ABS_Y) || pair(caps, ABS_HAT0X, ABS_HAT0Y) ||
         (caps.keys[BTN_DPAD_UP] && caps.keys[BTN_DPAD_DOWN] &&
          caps.keys[BTN_DPAD_LEFT] && caps.keys[BTN_DPAD_RIGHT]));
}

Sample normalize(const Capabilities& caps, const State& state) {
    Sample result{};
    constexpr std::pair<int, int> buttons[] = {
        {BTN_DPAD_UP, 1}, {BTN_DPAD_DOWN, 2}, {BTN_DPAD_LEFT, 4}, {BTN_DPAD_RIGHT, 8},
        {BTN_START, 0x10}, {BTN_SELECT, 0x20}, {BTN_THUMBL, 0x40}, {BTN_THUMBR, 0x80},
        {BTN_TL, 0x100}, {BTN_TR, 0x200}, {BTN_SOUTH, 0x1000}, {BTN_EAST, 0x2000},
        {BTN_WEST, 0x4000}, {BTN_NORTH, 0x8000},
    };
    for (auto [code, bit] : buttons) if (caps.keys[code] && state.keys[code]) result[0] |= bit;
    // BTN_MODE has no existing logical binding (also ignored by Windows).
    if (axis(caps, ABS_HAT0X)) result[0] |= state.axes[ABS_HAT0X] < 0 ? 4 : state.axes[ABS_HAT0X] > 0 ? 8 : 0;
    if (axis(caps, ABS_HAT0Y)) result[0] |= state.axes[ABS_HAT0Y] < 0 ? 1 : state.axes[ABS_HAT0Y] > 0 ? 2 : 0;
    result[3] = scale(caps, state, ABS_X, true);
    result[4] = -scale(caps, state, ABS_Y, true);

    int rightX = ABS_RX, rightY = ABS_RY;
    if (pair(caps, ABS_BRAKE, ABS_GAS)) {
        // Android-style layouts explicitly separate trigger axes from Z/RZ right stick.
        if (!pair(caps, ABS_RX, ABS_RY) && pair(caps, ABS_Z, ABS_RZ)) {
            rightX = ABS_Z; rightY = ABS_RZ;
        }
    }
    // Resolve each side independently; only an established RX/RY stick disambiguates
    // Z/RZ as triggers. Never reuse the Android-style Z/RZ stick as trigger axes.
    const auto triggerSource = [&](int dedicated, int hat, int z) {
        if (axis(caps, dedicated)) return dedicated;
        if (axis(caps, hat)) return hat;
        return pair(caps, ABS_RX, ABS_RY) && axis(caps, z) ? z : -1;
    };
    const int leftTrigger = triggerSource(ABS_BRAKE, ABS_HAT2Y, ABS_Z);
    const int rightTrigger = triggerSource(ABS_GAS, ABS_HAT2X, ABS_RZ);
    // Z/RZ without an established right stick or separate triggers is ambiguous; don't guess.
    if (pair(caps, rightX, rightY)) {
        result[5] = scale(caps, state, rightX, true);
        result[6] = -scale(caps, state, rightY, true);
    }
    result[1] = leftTrigger >= 0 ? scale(caps, state, leftTrigger)
        : caps.keys[BTN_TL2] && state.keys[BTN_TL2] ? 255 : 0;
    result[2] = rightTrigger >= 0 ? scale(caps, state, rightTrigger)
        : caps.keys[BTN_TR2] && state.keys[BTN_TR2] ? 255 : 0;
    return result;
}

Reader::~Reader() { for (auto& device : devices_) disconnect(device); }

void Reader::disconnect(Device& device) {
    if (device.hid >= 0) system_.closeHid(device.hid);
    if (device.fd >= 0) system_.close(device.fd);
    device = Device{};
}

void Reader::discover() {
    std::vector<Node> nodes;
    if (!system_.enumerate(nodes)) return; // A transient directory failure is not a disconnection.
    if (nodes.size() > MaxDiscoveryEntries) nodes.resize(MaxDiscoveryEntries);
    int retired = 0;
    for (int i = 0; i < Slots; ++i) {
        auto& device = devices_[i];
        if (device.fd >= 0 && std::find(nodes.begin(), nodes.end(), device.node) == nodes.end()) {
            disconnect(device);
            retired |= 1 << i;
        }
    }
    for (const auto& node : nodes) {
        if (std::any_of(devices_.begin(), devices_.end(), [&](const Device& d) { return d.fd >= 0 && d.node == node; })) continue;
        int slot = 0;
        while (slot < Slots && (devices_[slot].fd >= 0 || (retired & (1 << slot)))) ++slot;
        if (slot == Slots) break;
        Device fresh;
        fresh.node = node;
        fresh.fd = system_.open(node, fresh.caps, fresh.pending);
        if (fresh.fd < 0) continue;
        if (!qualifies(fresh.caps)) { system_.close(fresh.fd); continue; }
        fresh.committed = normalize(fresh.caps, fresh.pending);
        devices_[slot] = std::move(fresh);
    }
    // Retired slots cannot be reused until a later poll: Kotlin must observe the disconnect.
}

bool Reader::drain(Device& device) {
    using Sync = Device::Sync;
    const Sample published = device.committed;
    for (int i = 0; i < MaxEventsPerPoll; ++i) {
        input_event event{};
        const auto read = system_.read(device.fd, event);
        if (read == ReadResult::Failed) return false;
        if (read == ReadResult::Interrupted) continue; // EINTR does not prove the queue is empty.
        if (read == ReadResult::Empty) {
            if (device.sync == Sync::DiscardBacklog) {
                // EVIOCGABS does not flush old ABS events. Drain to EAGAIN first,
                // then verify that no events arrived during the multi-ioctl snapshot.
                if (!system_.snapshot(device.fd, device.caps, device.pending)) return false;
                device.sync = Sync::VerifySnapshot;
                continue;
            }
            if (device.sync == Sync::VerifySnapshot) {
                device.committed = normalize(device.caps, device.pending);
                device.committed[7] = static_cast<int>(++device.packet & 0x7fffffff);
                device.sync = Sync::Normal;
            }
            break;
        }
        if (event.type == EV_SYN && event.code == SYN_DROPPED) {
            device.sync = Sync::AwaitReport;
            // Reports consumed earlier in this poll have not reached Kotlin yet.
            device.committed = published;
            continue;
        }
        if (device.sync != Sync::Normal) {
            // Discard history, including arrivals during a snapshot. A new snapshot
            // after their report/queue boundary recovers the genuinely current state.
            device.sync = event.type == EV_SYN && event.code == SYN_REPORT
                ? Sync::DiscardBacklog : Sync::AwaitReport;
            continue;
        }
        if (event.type == EV_SYN && event.code == SYN_REPORT) {
            device.committed = normalize(device.caps, device.pending);
            device.committed[7] = static_cast<int>(++device.packet & 0x7fffffff);
        } else {
            if (event.type == EV_KEY && event.code < KEY_CNT && device.caps.keys[event.code])
                device.pending.keys[event.code] = event.value != 0;
            if (event.type == EV_ABS && event.code < ABS_CNT && device.caps.axes[event.code])
                device.pending.axes[event.code] = event.value;
        }
    }
    return true;
}

void Reader::applyHid(Device& device, const PadConfig& config) {
    const bool bluetooth = device.caps.id.bustype == BUS_BLUETOOTH;
    const auto ensureHid = [&] {
        if (device.hid >= 0 || device.hidTried) return device.hid >= 0;
        device.hidTried = true; // Once per connection: a pad that refuses is not asked again every poll.
        device.hid = system_.openHid(device.node);
        if (device.hid < 0)
            system_.log("gamepad DualSense: no access to its hidraw node (Steam's udev rules grant it); mic button and lights unavailable");
        return device.hid >= 0;
    };
    if (config.lightMode != 0 && device.appliedLightGeneration != config.generation) {
        device.appliedLightGeneration = config.generation;
        if (system_.writeLeds(device.node, config.lightMode, config.rgb, config.playerLight)) {
            system_.log("gamepad DualSense lights set");
        } else if (ensureHid()) {
            const auto report = dualSenseLightReport(bluetooth, config.lightMode, config.rgb, config.playerLight,
                                                     !device.lightSetupDone, device.outputSequence);
            if (system_.writeHid(device.hid, report)) {
                device.lightSetupDone = true;
                if (bluetooth) device.outputSequence = static_cast<unsigned char>((device.outputSequence + 1) & 0x0F);
                system_.log("gamepad DualSense lights set");
            } else {
                system_.log("gamepad DualSense lights write failed");
            }
        }
    }
    // Upstream reads the mic button only while voice control asks for full reports; so does this.
    if (config.fullReports) ensureHid();
    if (device.hid < 0) { device.micHeld = false; return; }
    // Drained to EAGAIN: hidraw keeps 64 reports and drops the newest once full, so a backlog would go stale.
    for (int i = 0; i < MaxEventsPerPoll * 2; ++i) {
        unsigned char report[128];
        const int length = system_.readHid(device.hid, report, sizeof(report));
        if (length == 0) break;
        if (length < 0) {
            // The HID side went away (the evdev node usually follows); input keeps working without it.
            system_.closeHid(device.hid);
            device.hid = -1;
            device.micHeld = false;
            return;
        }
        const int mic = dualSenseMicButton(bluetooth, report, static_cast<size_t>(length));
        if (mic >= 0) device.micHeld = mic == 1;
    }
    if (!config.fullReports) device.micHeld = false;
}

int Reader::poll(int slotMask, int* output) {
    std::fill(output, output + Slots * Stride, 0);
    if ((slotMask & 0xff) == 0xff) {
        const auto now = system_.nowMs();
        // With eight connected pads a narrow poll also has mask 0xff. Bound scans natively too.
        if (now >= nextDiscoveryMs_) {
            nextDiscoveryMs_ = now + DiscoveryIntervalMs;
            discover();
        }
    }
    int connected = 0;
    const PadConfig config = padConfig();
    for (int i = 0; i < Slots; ++i) {
        auto& device = devices_[i];
        if (device.fd < 0) continue;
        // Always check open devices, even if the caller's last mask omitted a slot.
        if (!drain(device)) { disconnect(device); continue; }
        if (isDualSense(device.caps.id)) applyHid(device, config);
        connected |= 1 << i;
        std::copy(device.committed.begin(), device.committed.end(), output + i * Stride);
        if (device.micHeld) output[i * Stride] |= DualSenseMicBit;
    }
    return connected;
}
} // namespace nuvio::gamepad

#ifndef NUVIO_GAMEPAD_TEST
#include <jni.h>
#include <new>
namespace {
struct Session {
    nuvio::gamepad::PosixSystem system;
    nuvio::gamepad::Reader reader{system};
};
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_input_LinuxGamepadNative_open(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) Session);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_app_features_input_LinuxGamepadNative_poll(JNIEnv* env, jobject, jlong handle, jint mask, jintArray out) {
    if (!handle || !out || env->GetArrayLength(out) < 64) return 0;
    std::array<int, 64> state{};
    int connected = 0;
    try { connected = reinterpret_cast<Session*>(handle)->reader.poll(mask, state.data()); }
    catch (...) { state.fill(0); } // Allocation failure must not unwind through JNI.
    env->SetIntArrayRegion(out, 0, 64, state.data());
    return connected;
}
// Upstream's NativePlayerBridge.configureGamepads: applied to each DualSense on the next poll.
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_configureGamepads(
    JNIEnv*, jobject, jboolean fullReports, jint lightMode, jint rgb, jboolean playerLight) {
    nuvio::gamepad::configure(fullReports == JNI_TRUE, static_cast<int>(lightMode),
                              static_cast<unsigned int>(rgb) & 0xFFFFFFu, playerLight == JNI_TRUE);
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_input_LinuxGamepadNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
#endif
