#pragma once
// The memory overlay's process figures on Linux (MemoryBreakdownSampler), read from /proc. Pure
// parsing, so tests/memory_breakdown_test.cpp can feed it fixed text; memory_breakdown.cpp does
// the reading. Sizes in bytes, -1 = unknown, in the order the Windows bridge fills them.
#include <cstdint>
#include <cstdlib>
#include <sstream>
#include <string>
#include <string_view>

struct LinuxProcessMemory {
    int64_t privateCommit = -1; // Private pages, in RAM or swapped out.
    int64_t workingSet = -1;    // Resident: shared pages included.
    int64_t privateWorkingSet = -1; // Resident private pages: GNOME System Monitor's Memory.
    int64_t driverMapped = -1;  // Resident graphics-driver mappings (/dev/dri, /dev/nvidia*).
    int64_t otherPrivate = -1;  // Private anonymous memory, swapped out included.
    int64_t images = -1;        // Resident shared libraries and the executable.
    int64_t mappedFiles = -1;   // Resident other file mappings (fonts, caches, shared memory).
    int64_t videoDedicated = -1;
    int64_t videoShared = -1;
};

namespace linux_memory {

// "Rss:  1234 kB" -> 1234 * 1024; -1 when [line] is not [key].
inline int64_t kbField(std::string_view line, std::string_view key) {
    if (line.size() <= key.size() || line.substr(0, key.size()) != key || line[key.size()] != ':') return -1;
    const std::string rest(line.substr(key.size() + 1));
    char *end = nullptr;
    const long long value = std::strtoll(rest.c_str(), &end, 10);
    if (end == rest.c_str()) return -1;
    return static_cast<int64_t>(value) * 1024;
}

// Private_Clean + Private_Dirty from /proc/<pid>/smaps_rollup: the process's resident private memory.
inline int64_t privateResident(const std::string &smapsRollup) {
    std::istringstream in(smapsRollup);
    std::string line;
    int64_t total = -1;
    while (std::getline(in, line)) {
        for (const char *key : {"Private_Clean", "Private_Dirty"}) {
            const int64_t value = kbField(line, key);
            if (value >= 0) total = (total < 0 ? 0 : total) + value;
        }
    }
    return total;
}

inline bool isSharedObject(std::string_view path) {
    const auto slash = path.rfind('/');
    const std::string_view name = slash == std::string_view::npos ? path : path.substr(slash + 1);
    const auto so = name.find(".so");
    return so != std::string_view::npos && (so + 3 == name.size() || name[so + 3] == '.');
}

inline bool isDriverMapping(std::string_view path) {
    return path.substr(0, 9) == "/dev/dri/" || path.substr(0, 11) == "/dev/nvidia";
}

// Fills everything but the video memory from /proc/self/smaps, whose mapping headers are
// "start-end perms offset dev inode [path]" followed by "Key: n kB" lines. [executable] is
// /proc/self/exe's target, counted with the shared libraries.
inline LinuxProcessMemory fromSmaps(const std::string &smaps, const std::string &executable) {
    LinuxProcessMemory memory;
    int64_t rss = 0, privateResident = 0, swap = 0, driver = 0, anonymous = 0, images = 0, mapped = 0;
    bool any = false;
    std::string path;
    enum class Kind { Anonymous, Driver, Image, File } kind = Kind::Anonymous;
    std::istringstream in(smaps);
    std::string line;
    while (std::getline(in, line)) {
        const auto colon = line.find(':');
        const auto space = line.find(' ');
        if (space != std::string::npos && (colon == std::string::npos || space < colon)) {
            // A mapping header: the path starts after the fifth field.
            std::istringstream header(line);
            std::string range, perms, offset, device, inode;
            header >> range >> perms >> offset >> device >> inode;
            path.clear(); // getline leaves it alone on an anonymous mapping, which has no path.
            std::getline(header >> std::ws, path);
            any = true;
            if (path.empty() || path[0] == '[') kind = Kind::Anonymous;
            else if (isDriverMapping(path)) kind = Kind::Driver;
            else if (isSharedObject(path) || path == executable) kind = Kind::Image;
            else kind = Kind::File;
            continue;
        }
        if (int64_t value = kbField(line, "Rss"); value >= 0) {
            rss += value;
            if (kind == Kind::Driver) driver += value;
            else if (kind == Kind::Image) images += value;
            else if (kind == Kind::File) mapped += value;
        } else if (int64_t clean = kbField(line, "Private_Clean"); clean >= 0) {
            privateResident += clean;
        } else if (int64_t dirty = kbField(line, "Private_Dirty"); dirty >= 0) {
            privateResident += dirty;
        } else if (int64_t anon = kbField(line, "Anonymous"); anon >= 0) {
            if (kind != Kind::Driver) anonymous += anon;
        } else if (int64_t swapped = kbField(line, "Swap"); swapped >= 0) {
            swap += swapped;
            if (kind != Kind::Driver) anonymous += swapped;
        }
    }
    if (!any) return memory;
    memory.privateCommit = privateResident + swap;
    memory.workingSet = rss;
    memory.privateWorkingSet = privateResident;
    memory.driverMapped = driver;
    memory.otherPrivate = anonymous;
    memory.images = images;
    memory.mappedFiles = mapped;
    return memory;
}

// One DRM client's memory from its /proc/self/fdinfo entry (drm-usage-stats). Clients are counted
// once each by drm-client-id: a process often holds several fds to one. Dedicated is VRAM (local),
// shared is GTT/system memory the GPU maps. Returns false for an fd that is not a DRM client.
struct DrmClientMemory {
    std::string clientId;
    int64_t dedicated = -1;
    int64_t shared = -1;
};

// "drm-memory-vram:  1234 KiB" (or MiB/GiB, or bytes when no unit).
inline int64_t drmSize(const std::string &value) {
    char *end = nullptr;
    const long long amount = std::strtoll(value.c_str(), &end, 10);
    if (end == value.c_str()) return -1;
    std::string unit(end);
    unit.erase(0, unit.find_first_not_of(' '));
    unit.erase(unit.find_last_not_of(" \r") + 1);
    if (unit == "KiB") return amount * 1024LL;
    if (unit == "MiB") return amount * 1024LL * 1024;
    if (unit == "GiB") return amount * 1024LL * 1024 * 1024;
    return amount;
}

inline bool drmClient(const std::string &fdinfo, DrmClientMemory &client) {
    std::istringstream in(fdinfo);
    std::string line;
    bool isDrm = false;
    // The drm-total-* keys (xe, nouveau, newer amdgpu) when there are any, else the older
    // drm-memory-* ones (amdgpu, i915): a driver that prints both would otherwise count twice.
    int64_t totalLocal = -1, totalSystem = -1, legacyLocal = -1, legacySystem = -1;
    auto add = [](int64_t &total, int64_t value) { if (value >= 0) total = (total < 0 ? 0 : total) + value; };
    while (std::getline(in, line)) {
        const auto colon = line.find(':');
        if (colon == std::string::npos) continue;
        const std::string key = line.substr(0, colon);
        std::string value = line.substr(colon + 1);
        value.erase(0, value.find_first_not_of(" \t"));
        if (key == "drm-client-id") { isDrm = true; client.clientId = value; continue; }
        if (key == "drm-total-vram0" || key == "drm-total-vram" || key == "drm-total-local0") add(totalLocal, drmSize(value));
        else if (key == "drm-total-gtt" || key == "drm-total-system0" || key == "drm-total-system") add(totalSystem, drmSize(value));
        else if (key == "drm-memory-vram") add(legacyLocal, drmSize(value));
        else if (key == "drm-memory-gtt") add(legacySystem, drmSize(value));
    }
    const bool total = totalLocal >= 0 || totalSystem >= 0;
    client.dedicated = total ? totalLocal : legacyLocal;
    client.shared = total ? totalSystem : legacySystem;
    return isDrm;
}

} // namespace linux_memory
