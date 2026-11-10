// The memory overlay's /proc parsing (memory_breakdown.h) against fixed smaps, smaps_rollup and
// DRM fdinfo text. Pure strings: no process, window or device is touched.
#include "memory_breakdown.h"
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

constexpr int64_t KiB = 1024;

void testSmaps() {
    const std::string smaps =
        "55d0a0000000-55d0a0100000 r-xp 00000000 fd:01 100 /opt/nuvio-desktop/lib/nuvio\n"
        "Rss:                 400 kB\n"
        "Private_Clean:       100 kB\n"
        "Private_Dirty:         0 kB\n"
        "Anonymous:             0 kB\n"
        "Swap:                  0 kB\n"
        "SwapPss:               0 kB\n"
        "VmFlags: rd ex mr mw me dw\n"
        "7f0000000000-7f0000200000 rw-p 00000000 00:00 0 \n"
        "Rss:                1000 kB\n"
        "Private_Clean:         0 kB\n"
        "Private_Dirty:      1000 kB\n"
        "Anonymous:          1000 kB\n"
        "Swap:                 50 kB\n"
        "7f0000300000-7f0000400000 r-xp 00000000 fd:01 200 /opt/nuvio-desktop/lib/native/libmpv.so.2.5.0\n"
        "Rss:                 300 kB\n"
        "Private_Clean:         0 kB\n"
        "Private_Dirty:        20 kB\n"
        "Anonymous:            20 kB\n"
        "Swap:                  0 kB\n"
        "7f0000500000-7f0000600000 rw-s 10000000 00:06 300 /dev/nvidia0\n"
        "Rss:                 256 kB\n"
        "Private_Clean:         0 kB\n"
        "Private_Dirty:         0 kB\n"
        "Anonymous:             0 kB\n"
        "Swap:                  0 kB\n"
        "7f0000700000-7f0000800000 r--s 00000000 fd:01 400 /usr/share/fonts/DejaVuSans.ttf\n"
        "Rss:                  80 kB\n"
        "Private_Clean:         0 kB\n"
        "Private_Dirty:         0 kB\n"
        "Anonymous:             0 kB\n"
        "Swap:                  0 kB\n"
        "7ffd00000000-7ffd00021000 rw-p 00000000 00:00 0 [stack]\n"
        "Rss:                  12 kB\n"
        "Private_Clean:         0 kB\n"
        "Private_Dirty:        12 kB\n"
        "Anonymous:            12 kB\n"
        "Swap:                  0 kB\n";
    const auto memory = linux_memory::fromSmaps(smaps, "/opt/nuvio-desktop/lib/nuvio");
    require(memory.workingSet == (400 + 1000 + 300 + 256 + 80 + 12) * KiB, "working set");
    require(memory.privateWorkingSet == (100 + 1000 + 20 + 12) * KiB, "private working set");
    require(memory.privateCommit == (100 + 1000 + 20 + 12 + 50) * KiB, "private commit includes swap");
    require(memory.driverMapped == 256 * KiB, "driver mappings");
    require(memory.otherPrivate == (1000 + 50 + 20 + 12) * KiB, "anonymous private");
    require(memory.images == (400 + 300) * KiB, "executable and shared libraries");
    require(memory.mappedFiles == 80 * KiB, "other file mappings");
    require(memory.videoDedicated == -1 && memory.videoShared == -1, "video memory is not from smaps");

    const auto empty = linux_memory::fromSmaps("", "/x");
    require(empty.workingSet == -1 && empty.privateWorkingSet == -1, "unreadable smaps is unknown");
}

void testRollup() {
    const std::string rollup =
        "55d0a0000000-7ffd00021000 ---p 00000000 00:00 0 [rollup]\n"
        "Rss:                2048 kB\n"
        "Pss:                1500 kB\n"
        "Private_Clean:       100 kB\n"
        "Private_Dirty:       900 kB\n"
        "Swap:                 10 kB\n";
    require(linux_memory::privateResident(rollup) == 1000 * KiB, "rollup private");
    require(linux_memory::privateResident("") == -1, "unreadable rollup");
}

void testNames() {
    require(linux_memory::isSharedObject("/a/libmpv.so.2"), "versioned library");
    require(linux_memory::isSharedObject("/a/libquickjs.so"), "plain library");
    require(!linux_memory::isSharedObject("/a/libfoo.so (deleted)"), "deleted library");
    require(!linux_memory::isSharedObject("/a/data.something"), "not a library");
}

void testDrm() {
    linux_memory::DrmClientMemory amd;
    require(linux_memory::drmClient(
        "pos:\t0\nflags:\t02100002\ndrm-driver:\tamdgpu\ndrm-client-id:\t42\n"
        "drm-memory-vram:\t1024 KiB\ndrm-memory-gtt:\t2048 KiB\n"
        "drm-total-vram:\t3 MiB\ndrm-total-gtt:\t4 MiB\n", amd), "amdgpu fd is a client");
    require(amd.clientId == "42", "client id");
    require(amd.dedicated == 3 * KiB * KiB && amd.shared == 4 * KiB * KiB, "total keys win over legacy ones");

    linux_memory::DrmClientMemory intel;
    require(linux_memory::drmClient("drm-client-id:\t7\ndrm-memory-vram:\t512 KiB\n", intel), "legacy client");
    require(intel.dedicated == 512 * KiB && intel.shared == -1, "legacy keys alone");

    linux_memory::DrmClientMemory none;
    require(!linux_memory::drmClient("pos:\t0\nflags:\t0100002\n", none), "not a DRM fd");
}

} // namespace

int main() {
    try {
        testSmaps();
        testRollup();
        testNames();
        testDrm();
    } catch (const std::exception &error) {
        std::cerr << "memory_breakdown_test FAILED: " << error.what() << '\n';
        return 1;
    }
    std::cout << "MEMORY_BREAKDOWN_PASS\n";
    return 0;
}
