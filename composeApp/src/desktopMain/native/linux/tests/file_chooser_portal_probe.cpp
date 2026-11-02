// Drives nuvio::filechooser against whatever session bus the environment names.
// Usage: linux_file_chooser_portal_probe open|folder|save <timeout-ms> [current-folder] [current-name]
//        linux_file_chooser_portal_probe retrieve <timeout-ms> <key>
//        linux_file_chooser_portal_probe ancestor   (needs an X server; checks managedAncestor)
#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "../file_chooser_portal.h"

namespace {
int ancestor() {
    Display *display = XOpenDisplay(nullptr);
    if (!display) return 2;
    const Window root = DefaultRootWindow(display);
    const auto make = [&](Window parent) { return XCreateSimpleWindow(display, parent, 0, 0, 10, 10, 0, 0, 0); };
    const Window frame = make(root), client = make(frame), content = make(client), loose = make(root);
    const Atom state = XInternAtom(display, "WM_STATE", False);
    const long value[2] = {1, 0};
    XChangeProperty(display, client, state, state, 32, PropModeReplace, reinterpret_cast<const unsigned char *>(value), 2);
    XSync(display, False);
    std::printf("ancestor content=%d client=%d loose=%d\n",
                nuvio::filechooser::managedAncestor(content) == client,
                nuvio::filechooser::managedAncestor(client) == client,
                nuvio::filechooser::managedAncestor(loose) == 0);
    XCloseDisplay(display);
    return 0;
}
}

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "";
    if (!std::strcmp(mode, "ancestor")) return ancestor();
    const int timeout = argc > 2 ? std::atoi(argv[2]) : 2000;
    if (!std::strcmp(mode, "retrieve")) {
        const auto result = nuvio::filechooser::retrieveFiles(argc > 3 ? argv[3] : "", timeout);
        std::printf("retrieved %d|%s", result.ok, result.message.c_str());
        for (const auto &file : result.files) std::printf("|%s", file.c_str());
        std::printf("\n");
        return 0;
    }
    nuvio::filechooser::Request request;
    request.save = !std::strcmp(mode, "save");
    request.directory = !std::strcmp(mode, "folder");
    request.parentWindow = "x11:2a00004";
    request.title = "Probe título";
    if (argc > 3) request.currentFolder = argv[3];
    if (argc > 4) request.currentName = argv[4];
    if (!request.directory) {
        request.filterName = "Backups";
        request.filterPatterns = {"*.zip", "*.ZIP"};
    }
    const auto result = nuvio::filechooser::choose(request, timeout);
    std::printf("result %d|%s|%s\n", result.status, result.uri.c_str(), result.message.c_str());
    return 0;
}
