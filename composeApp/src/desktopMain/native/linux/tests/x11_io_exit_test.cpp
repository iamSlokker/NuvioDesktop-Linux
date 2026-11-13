// x11_io_exit.h against a real X server: a private Xvfb is started, connected to, and killed. The
// connection gets its handler as libX11 makes it (linuxHookX11DisplayOpen, as the bridge does when it
// loads), with no install of its own. The lost connection must reach the handler once, Xlib must return
// instead of calling exit() (this test would end there, without printing PASS), and the display's lock
// must be free afterwards for another thread, as the GL drivers' exit handlers need it. No GTK, JNI,
// network or audio; the display is our own.
#include "x11_io_exit.h"
#include <chrono>
#include <cstdlib>
#include <atomic>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
} // namespace

int main() {
    pid_t server = -1;
    // As the app does (xlib_threads.cpp): the display gets a lock, which _XIOError takes and keeps, and
    // every display made from here on gets the exit handler.
    XInitThreads();
    try {
        require(linuxHookX11DisplayOpen(), "libX11 exports _XInitDisplayLock_fn");
        require(!linuxHookX11DisplayOpen(), "hooked once");
        const std::string name = ":" + std::to_string(90 + getpid() % 9);
        server = fork();
        if (server == 0) {
            execlp("Xvfb", "Xvfb", name.c_str(), "-screen", "0", "320x240x24", "-nolisten", "tcp", static_cast<char *>(nullptr));
            _exit(127);
        }
        require(server > 0, "fork Xvfb");
        Display *display = nullptr;
        for (int attempt = 0; attempt < 50 && !display; ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            display = XOpenDisplay(name.c_str());
        }
        require(display != nullptr, "connect to the private Xvfb " + name);

        // In the app the process-wide I/O error handler is set to one that returns, after GDK's (which would _exit)
        // and AWT's, so Xlib goes on to the per-display exit handler. Xlib's own default would exit(1).
        linuxInstallX11IoErrorHandler();
        int asked = 0;
        linuxX11IoExitAction() = [&asked] { ++asked; };
        // No install here: the connection got its handler as it was made.
        require(!linuxInstallX11IoExitHandler(nullptr), "no display, no handler");

        kill(server, SIGKILL);
        waitpid(server, nullptr, 0);
        server = -1;

        // The first call after the server died hits the I/O error; later ones must return at once.
        XNoOp(display);
        XSync(display, False);
        XPending(display);
        XSync(display, False);
        require(asked == 1, "the app is asked to quit once (" + std::to_string(asked) + ")");
        require(linuxX11IoExits().load() >= 1, "the handler ran");

        // What NVIDIA's EGL exit handler does from the exiting thread (XESetCloseDisplay locks the display).
        std::atomic<bool> locked{false};
        std::thread other([display, &locked] { XLockDisplay(display); XUnlockDisplay(display); locked = true; });
        for (int wait = 0; wait < 40 && !locked; ++wait) std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (!locked) {
            other.detach();
            throw std::runtime_error("another thread could not lock the lost display: its user lock was kept");
        }
        other.join();

        std::cout << "X11_IO_EXIT_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        if (server > 0) { kill(server, SIGKILL); waitpid(server, nullptr, 0); }
        std::cerr << "X11_IO_EXIT_FAIL: " << error.what() << "\n";
        return 1;
    }
}
