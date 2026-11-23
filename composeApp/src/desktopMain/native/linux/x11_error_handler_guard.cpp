#include "x11_error_handler_guard.h"

#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>

// mpv's X11 code calls XSetErrorHandler(NULL) whenever its video output closes, and that is not only
// when the player does: an output that fails to start, on a machine with no Vulkan device, leaves
// Xlib's default in place with the app still running. That default exits the process on the first X
// error, and AWT makes harmless ones (RenderFreePicture on a canvas whose window has gone) that its own
// handler ignores; the HUD's GTK makes them too (GLXBadWindow from X_GLXDestroyWindow, which ended
// 1.46.0 on a cold start in a VM without Vulkan, the Home trailer's output having just failed).
// Restoring afterwards cannot close that gap, so the bridge answers mpv's call itself.
//
// Exporting XSetErrorHandler from the bridge is not enough for libmpv. mpv binds the call lazily, the
// first time its output opens, and by then AWT has opened libawt_xawt with RTLD_GLOBAL (after the
// bridge: it loads before AWT starts), which puts libX11 in the global scope that every lookup searches
// first. LD_DEBUG on the Flatpak shows it: GTK and cairo bound to the bridge at its load, libmpv to
// libX11 at its first output. So libmpv's own import slots point here as the bridge loads, whatever
// order the libraries come in later.
namespace {
std::atomic<XErrorHandler> replacement{nullptr};
std::atomic<int> boundImports{0};

XErrorHandler guardedSetErrorHandler(XErrorHandler handler) {
    using Set = XErrorHandler (*)(XErrorHandler);
    static const Set real = reinterpret_cast<Set>(dlsym(RTLD_NEXT, "XSetErrorHandler"));
    return real(handler ? handler : replacement.load());
}

#if defined(__x86_64__)
constexpr uint32_t kJumpSlot = R_X86_64_JUMP_SLOT;
constexpr uint32_t kGlobalData = R_X86_64_GLOB_DAT;
#elif defined(__aarch64__)
constexpr uint32_t kJumpSlot = R_AARCH64_JUMP_SLOT;
constexpr uint32_t kGlobalData = R_AARCH64_GLOB_DAT;
#else
#error "x11_error_handler_guard.cpp binds RELA import slots; add this architecture's relocation types."
#endif
static_assert(sizeof(void *) == 8, "64-bit ELF only");

// glibc rewrites .dynamic's addresses to run-time ones where the section is writable (x86_64, aarch64)
// and leaves link-time ones where it is not; a link-time address is below the load address.
uintptr_t runtimeAddress(const dl_phdr_info *info, uintptr_t address) {
    return address < info->dlpi_addr ? info->dlpi_addr + address : address;
}

// A slot in PT_GNU_RELRO is read-only once the library is relocated (a -z now build).
bool writeSlot(const dl_phdr_info *info, uintptr_t slot, void *value) {
    bool readOnly = false;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &segment = info->dlpi_phdr[i];
        const uintptr_t start = info->dlpi_addr + segment.p_vaddr;
        if (segment.p_type == PT_GNU_RELRO && slot >= start && slot < start + segment.p_memsz) readOnly = true;
    }
    const uintptr_t pageSize = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    void *page = reinterpret_cast<void *>(slot & ~(pageSize - 1));
    if (readOnly && mprotect(page, pageSize, PROT_READ | PROT_WRITE) != 0) return false;
    __atomic_store_n(reinterpret_cast<void **>(slot), value, __ATOMIC_RELEASE);
    if (readOnly) mprotect(page, pageSize, PROT_READ);
    return true;
}

int bindLibmpvImports(dl_phdr_info *info, size_t, void *) {
    const char *path = info->dlpi_name;
    const char *name = path ? std::strrchr(path, '/') : nullptr;
    name = name ? name + 1 : path;
    if (!name || std::strncmp(name, "libmpv.so", 9) != 0) return 0;

    const ElfW(Dyn) *dynamic = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC)
            dynamic = reinterpret_cast<const ElfW(Dyn) *>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
    if (!dynamic) return 1;

    uintptr_t symbols = 0, strings = 0, pltRelocations = 0, relocations = 0;
    size_t pltSize = 0, size = 0;
    ElfW(Sxword) pltKind = 0;
    for (const ElfW(Dyn) *entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
        case DT_SYMTAB: symbols = runtimeAddress(info, entry->d_un.d_ptr); break;
        case DT_STRTAB: strings = runtimeAddress(info, entry->d_un.d_ptr); break;
        case DT_JMPREL: pltRelocations = runtimeAddress(info, entry->d_un.d_ptr); break;
        case DT_PLTRELSZ: pltSize = entry->d_un.d_val; break;
        case DT_PLTREL: pltKind = entry->d_un.d_val; break;
        case DT_RELA: relocations = runtimeAddress(info, entry->d_un.d_ptr); break;
        case DT_RELASZ: size = entry->d_un.d_val; break;
        default: break;
        }
    }
    if (!symbols || !strings) return 1;

    // JUMP_SLOT for a PLT call, GLOB_DAT for a -fno-plt one: either way, the address mpv calls through.
    auto bind = [&](uintptr_t table, size_t bytes) {
        const auto *first = reinterpret_cast<const ElfW(Rela) *>(table);
        for (size_t i = 0; i < bytes / sizeof(ElfW(Rela)); ++i) {
            const ElfW(Rela) &relocation = first[i];
            const auto type = ELF64_R_TYPE(relocation.r_info);
            if (type != kJumpSlot && type != kGlobalData) continue;
            const auto &symbol = reinterpret_cast<const ElfW(Sym) *>(symbols)[ELF64_R_SYM(relocation.r_info)];
            if (std::strcmp(reinterpret_cast<const char *>(strings) + symbol.st_name, "XSetErrorHandler") != 0) continue;
            if (writeSlot(info, info->dlpi_addr + relocation.r_offset, reinterpret_cast<void *>(&guardedSetErrorHandler)))
                boundImports.fetch_add(1);
        }
    };
    if (pltRelocations && pltKind == DT_RELA) bind(pltRelocations, pltSize);
    if (relocations) bind(relocations, size);
    return 1;
}

// libmpv is the bridge's dependency, so it is mapped (and its constructors have run) before this one.
__attribute__((constructor)) void bindLibmpvToGuard() {
    dl_iterate_phdr(bindLibmpvImports, nullptr);
    if (boundImports.load() == 0)
        std::fprintf(stderr, "Linux controls: libmpv's XSetErrorHandler import was not found; an X error "
                             "after a failed video output can still end the app\n");
}
}

void linuxReplaceFatalX11Default(XErrorHandler handler) { replacement.store(handler); }

int linuxX11ErrorGuardBoundImports() { return boundImports.load(); }

// For the libraries that do find the bridge first (GTK, cairo: they bind as the bridge loads).
extern "C" __attribute__((visibility("default"))) XErrorHandler XSetErrorHandler(XErrorHandler handler) {
    return guardedSetErrorHandler(handler);
}
