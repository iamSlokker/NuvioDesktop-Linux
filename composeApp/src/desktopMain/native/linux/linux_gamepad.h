#pragma once

#include <linux/input.h>
#include <array>
#include <bitset>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace nuvio::gamepad {
constexpr int Slots = 8;
constexpr int Stride = 8;
constexpr int MaxEventsPerPoll = 256;
constexpr int MaxDiscoveryEntries = 512;
constexpr uint64_t DiscoveryIntervalMs = 2000;
using Sample = std::array<int, Stride>;

struct Capabilities {
    std::bitset<KEY_CNT> keys;
    std::bitset<ABS_CNT> axes;
    std::array<input_absinfo, ABS_CNT> ranges{};
    bool accelerometer = false;
    // EVIOCGID: bus and USB ids, which pick out the DualSense for its HID side (see Reader::applyHid).
    input_id id{};
};
struct State {
    std::bitset<KEY_CNT> keys;
    std::array<int, ABS_CNT> axes{};
};
struct Node {
    std::string path;
    // Node identity, not model identity: identical controllers remain separate.
    uint64_t device = 0;
    uint64_t inode = 0;
    bool operator==(const Node& other) const {
        return path == other.path && device == other.device && inode == other.inode;
    }
};
enum class ReadResult { Event, Empty, Interrupted, Failed };

/**
 * What the app wants of PlayStation pads themselves (NativePlayerBridge.configureGamepads, as on Windows):
 * [fullReports] while voice control is on, so the mic button is read; [lightMode] 0 never writes to the
 * pad, 1 turns its light bar off, 2 sets it to [rgb] (0xRRGGBB); [playerLight] lights the middle player light.
 */
struct PadConfig {
    bool fullReports = false;
    int lightMode = 0;
    unsigned int rgb = 0;
    bool playerLight = false;
    unsigned int generation = 0; // Bumped when a light setting changes, so each pad is written once per change.
};
void configure(bool fullReports, int lightMode, unsigned int rgb, bool playerLight);
PadConfig padConfig();

bool isDualSense(const input_id& id);
constexpr int DualSenseMicBit = 0x0800; // GamepadInput.BIT_MIC
constexpr size_t DualSenseUsbOutputSize = 63;
constexpr size_t DualSenseBluetoothOutputSize = 78;
// Sony's output report with the light bar and player light set, as hid-playstation lays it out; Bluetooth
// carries a sequence number, the "HID data" tag and a CRC-32 over 0xA2 and the report.
std::vector<unsigned char> dualSenseLightReport(bool bluetooth, int lightMode, unsigned int rgb, bool playerLight,
                                                bool setup, unsigned char sequence);
// The mic button from one hidraw input report: 1 held, 0 up, -1 a report that does not carry it.
int dualSenseMicButton(bool bluetooth, const unsigned char* report, size_t length);
uint32_t crc32(uint32_t crc, const unsigned char* data, size_t length);

// Only the device boundary is virtual; fixtures exercise the production reader and mapper.
class System {
public:
    virtual ~System() = default;
    virtual uint64_t nowMs() = 0;
    virtual bool enumerate(std::vector<Node>& nodes) = 0;
    virtual int open(const Node& node, Capabilities& caps, State& state) = 0;
    virtual ReadResult read(int fd, input_event& event) = 0;
    virtual bool snapshot(int fd, const Capabilities& caps, State& state) = 0;
    virtual void close(int fd) = 0;

    // The pad's HID side beside its evdev node. hid-playstation keeps the mic button to itself (it toggles
    // the pad's own mute) and exposes the lights only as root-owned LED files, so both go through hidraw,
    // which Steam's udev rules give the desktop user; the LED files are used first where they are writable.
    // The defaults say "not available", which is what a sealed Flatpak (no /dev/hidraw, read-only /sys) gets.
    virtual int openHid(const Node&) { return -1; }
    // > 0: one report of that length; 0: none queued; < 0: the node failed.
    virtual int readHid(int, unsigned char*, size_t) { return -1; }
    virtual bool writeHid(int, const std::vector<unsigned char>&) { return false; }
    virtual bool writeLeds(const Node&, int /*lightMode*/, unsigned int /*rgb*/, bool /*playerLight*/) { return false; }
    virtual void closeHid(int) {}
    virtual void log(const std::string&) {}
};

bool qualifies(const Capabilities& caps);
Sample normalize(const Capabilities& caps, const State& state);

class Reader {
public:
    explicit Reader(System& system) : system_(system) {}
    ~Reader();
    Reader(const Reader&) = delete;
    Reader& operator=(const Reader&) = delete;
    int poll(int slotMask, int* output);
private:
    struct Device {
        enum class Sync { Normal, AwaitReport, DiscardBacklog, VerifySnapshot };
        int fd = -1;
        Node node;
        Capabilities caps;
        State pending;
        Sample committed{};
        Sync sync = Sync::Normal;
        uint32_t packet = 0;
        // DualSense only: the hidraw side, the mic button it reports, and the light settings applied.
        int hid = -1;
        bool hidTried = false;
        bool micHeld = false;
        bool lightSetupDone = false;
        unsigned int appliedLightGeneration = 0;
        unsigned char outputSequence = 0;
    };
    System& system_;
    std::array<Device, Slots> devices_{};
    uint64_t nextDiscoveryMs_ = 0;
    void disconnect(Device& device);
    void discover();
    bool drain(Device& device);
    void applyHid(Device& device, const PadConfig& config);
};
} // namespace nuvio::gamepad
