// An X error after a video output failed to start, with real libmpv and a real X server. The driver
// list must offer no usable device, so mpv reaches its X11 setup and gives up after it: the builder's
// software Vulkan is one, since libplacebo refuses CPU devices. Negative control: without the guard,
// mpv must leave Xlib's fatal default behind and the same error must end the process.
#include <dlfcn.h>
#include <sys/wait.h>
#include <unistd.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

namespace {
int inChild(const std::string &probe, const std::string &video, int guarded) {
    const pid_t child = ::fork();
    if (child == 0) {
        // RTLD_LOCAL, as System.load does it.
        void *library = ::dlopen(probe.c_str(), RTLD_LAZY | RTLD_LOCAL);
        if (!library) { std::cerr << ::dlerror() << '\n'; ::_exit(3); }
        const auto run = reinterpret_cast<int (*)(const char *, int)>(::dlsym(library, "x11GuardProbe"));
        ::_exit(run ? run(video.c_str(), guarded) : 3);
    }
    int status = 0;
    ::waitpid(child, &status, 0);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128;
}
}

int main(int, char **argv) {
    const auto self = std::filesystem::canonical(argv[0]).parent_path();
    const auto directory = std::filesystem::temp_directory_path() / ("nuvio-x11-guard-" + std::to_string(::getpid()));
    std::filesystem::create_directories(directory);
    const auto video = (directory / "video.y4m").string();
    {
        std::ofstream file(video, std::ios::binary);
        file << "YUV4MPEG2 W2 H2 F25:1 Ip A1:1 C420jpeg\n";
        for (int frame = 0; frame < 25; ++frame) file << "FRAME\n" << std::string(6, '\x40');
    }
    const auto probe = (self / "liblinux_x11_error_guard_probe.so").string();
    const int bare = inChild(probe, video, 0);
    const int guarded = inChild(probe, video, 1);
    std::filesystem::remove_all(directory);
    std::cout << "without guard exit=" << bare << ", with guard exit=" << guarded << '\n';
    if (bare != 1) { std::cerr << "FAIL: the X error did not reach Xlib's default without the guard\n"; return 1; }
    if (guarded != 0) { std::cerr << "FAIL: the guard did not keep the toolkit handler\n"; return 1; }
    std::cout << "X11_ERROR_GUARD_PASS\n";
    return 0;
}
