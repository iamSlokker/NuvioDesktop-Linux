// The memory overlay's process figures on Linux (memory_breakdown.h), as the Windows bridge's
// processMemoryBreakdown, processPrivateWorkingSet and loadedModuleNames give them there.
#include "memory_breakdown.h"
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>
#include <climits>
#include <fstream>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace {

std::string readFile(const std::string &path) {
    std::ifstream in(path);
    if (!in) return {};
    std::ostringstream text;
    text << in.rdbuf();
    return text.str();
}

std::string linkTarget(const std::string &path) {
    char buffer[PATH_MAX];
    const ssize_t length = readlink(path.c_str(), buffer, sizeof(buffer) - 1);
    return length > 0 ? std::string(buffer, static_cast<size_t>(length)) : std::string();
}

// Dedicated and shared GPU memory of this process's DRM clients (amdgpu, i915, xe, nouveau).
void drmVideoMemory(int64_t &dedicated, int64_t &shared) {
    DIR *fds = opendir("/proc/self/fd");
    if (!fds) return;
    std::set<std::string> seen;
    auto add = [](int64_t &total, int64_t value) { if (value >= 0) total = (total < 0 ? 0 : total) + value; };
    while (dirent *entry = readdir(fds)) {
        if (entry->d_name[0] == '.') continue;
        const std::string fd = entry->d_name;
        if (linkTarget("/proc/self/fd/" + fd).rfind("/dev/dri/", 0) != 0) continue;
        linux_memory::DrmClientMemory client;
        if (!linux_memory::drmClient(readFile("/proc/self/fdinfo/" + fd), client)) continue;
        if (!seen.insert(client.clientId).second) continue;
        add(dedicated, client.dedicated);
        add(shared, client.shared);
    }
    closedir(fds);
}

// The NVIDIA driver keeps no DRM usage stats; NVML lists the video memory each process uses.
// Process ids are the host's, so inside the Flatpak sandbox this process is not found and the
// figure stays unknown.
struct Nvml {
    struct ProcessInfo {
        unsigned int pid;
        unsigned long long usedGpuMemory;
        unsigned int gpuInstanceId;
        unsigned int computeInstanceId;
    };
    using Device = void *;
    int (*getCount)(unsigned int *) = nullptr;
    int (*getHandle)(unsigned int, Device *) = nullptr;
    int (*graphicsProcesses)(Device, unsigned int *, ProcessInfo *) = nullptr;

    static Nvml *instance() {
        static Nvml *nvml = [] () -> Nvml * {
            void *library = dlopen("libnvidia-ml.so.1", RTLD_NOW | RTLD_LOCAL);
            if (!library) return nullptr;
            auto init = reinterpret_cast<int (*)()>(dlsym(library, "nvmlInit_v2"));
            auto *result = new Nvml();
            result->getCount = reinterpret_cast<int (*)(unsigned int *)>(dlsym(library, "nvmlDeviceGetCount_v2"));
            result->getHandle = reinterpret_cast<int (*)(unsigned int, Device *)>(dlsym(library, "nvmlDeviceGetHandleByIndex_v2"));
            result->graphicsProcesses = reinterpret_cast<int (*)(Device, unsigned int *, ProcessInfo *)>(
                dlsym(library, "nvmlDeviceGetGraphicsRunningProcesses_v3"));
            if (!init || !result->getCount || !result->getHandle || !result->graphicsProcesses || init() != 0) {
                delete result;
                dlclose(library);
                return nullptr;
            }
            return result;
        }();
        return nvml;
    }

    // -1 when no NVIDIA GPU lists this process.
    int64_t usedBy(unsigned int pid) const {
        constexpr unsigned long long notAvailable = ~0ULL;
        int64_t used = -1;
        unsigned int devices = 0;
        if (getCount(&devices) != 0) return -1;
        for (unsigned int index = 0; index < devices; ++index) {
            Device device = nullptr;
            if (getHandle(index, &device) != 0) continue;
            std::vector<ProcessInfo> processes(64);
            unsigned int count = static_cast<unsigned int>(processes.size());
            int status = graphicsProcesses(device, &count, processes.data());
            if (status == 7 /* NVML_ERROR_INSUFFICIENT_SIZE */) {
                processes.resize(count + 16);
                count = static_cast<unsigned int>(processes.size());
                status = graphicsProcesses(device, &count, processes.data());
            }
            if (status != 0) continue;
            for (unsigned int i = 0; i < count && i < processes.size(); ++i) {
                if (processes[i].pid != pid || processes[i].usedGpuMemory == notAvailable) continue;
                used = (used < 0 ? 0 : used) + static_cast<int64_t>(processes[i].usedGpuMemory);
            }
        }
        return used;
    }
};

std::mutex nvmlLock; // NVML is thread-safe; this only keeps two samplers from overlapping.

} // namespace

// Fills out[0..8] like the Windows bridge (NativePlayerBridge.processMemoryBreakdown): private
// commit, working set, private working set, graphics-driver mappings, other private, images,
// mapped files, then dedicated and shared video memory. Unknown values are -1.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_processMemoryBreakdown(JNIEnv *env, jobject, jlongArray out) {
    if (!out || env->GetArrayLength(out) < 9) return JNI_FALSE;
    LinuxProcessMemory memory = linux_memory::fromSmaps(readFile("/proc/self/smaps"), linkTarget("/proc/self/exe"));
    drmVideoMemory(memory.videoDedicated, memory.videoShared);
    {
        std::lock_guard<std::mutex> lock(nvmlLock);
        if (const Nvml *nvml = Nvml::instance()) {
            const int64_t used = nvml->usedBy(static_cast<unsigned int>(getpid()));
            if (used >= 0) memory.videoDedicated = (memory.videoDedicated < 0 ? 0 : memory.videoDedicated) + used;
        }
    }
    const jlong values[9] = {memory.privateCommit, memory.workingSet, memory.privateWorkingSet,
        memory.driverMapped, memory.otherPrivate, memory.images, memory.mappedFiles,
        memory.videoDedicated, memory.videoShared};
    env->SetLongArrayRegion(out, 0, 9, values);
    return JNI_TRUE;
}

// Another process's resident private memory (a child: WebKit's web and network processes, the
// torrent server); -1 when it cannot be read.
extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_processPrivateWorkingSet(JNIEnv *, jobject, jlong pid) {
    if (pid <= 0) return -1;
    return linux_memory::privateResident(readFile("/proc/" + std::to_string(pid) + "/smaps_rollup"));
}

// File names of the shared libraries mapped in this process, each also without its version, ';'-separated.
extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_loadedModuleNames(JNIEnv *env, jobject) {
    std::istringstream maps(readFile("/proc/self/maps"));
    std::set<std::string> names;
    std::string line;
    while (std::getline(maps, line)) {
        const auto slash = line.find('/');
        if (slash == std::string::npos) continue;
        const std::string path = line.substr(slash);
        if (!linux_memory::isSharedObject(path)) continue;
        const std::string name = path.substr(path.rfind('/') + 1);
        names.insert(name);
        // Mapped under the real file name (libmpv.so.2.5.0); the unversioned one is what the
        // engine list (NotableNativeEngines) names.
        names.insert(name.substr(0, name.find(".so") + 3));
    }
    std::string joined;
    for (const auto &name : names) {
        if (!joined.empty()) joined += ';';
        joined += name;
    }
    return env->NewStringUTF(joined.c_str());
}
