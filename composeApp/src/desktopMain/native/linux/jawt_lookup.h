#pragma once

#include <jni.h>
#include <jawt.h>
#include <dlfcn.h>
#include <link.h>
#include <atomic>
#include <cstring>
#include <string>

// JAWT_GetAWT, with libjawt.so opened on first use rather than linked. libjawt.so needs libawt.so and
// libawt_xawt.so, which the native image only loads (from its own directory) when AWT starts, and
// the bridge now loads before that (xlib_threads.cpp). JAWT is only ever called with AWT up, so by
// then the packaged lib/runtime/lib copy (the bridge's RUNPATH) resolves, and on a JDK the copy
// beside the loaded libawt.so does.
inline jboolean linuxJawtGetAwt(JNIEnv *env, JAWT *awt) {
    using GetAwt = jboolean(JNICALL *)(JNIEnv *, JAWT *);
    static std::atomic<GetAwt> cached{nullptr};
    GetAwt getAwt = cached.load();
    if (!getAwt) {
        void *symbol = dlsym(RTLD_DEFAULT, "JAWT_GetAWT");
        if (!symbol) {
            void *library = dlopen("libjawt.so", RTLD_NOW | RTLD_GLOBAL);
            if (!library) {
                std::string awtDirectory;
                dl_iterate_phdr([](dl_phdr_info *info, size_t, void *data) {
                    const char *slash = info->dlpi_name ? std::strrchr(info->dlpi_name, '/') : nullptr;
                    if (!slash || std::strcmp(slash + 1, "libawt.so") != 0) return 0;
                    static_cast<std::string *>(data)->assign(info->dlpi_name, slash - info->dlpi_name);
                    return 1;
                }, &awtDirectory);
                if (!awtDirectory.empty())
                    library = dlopen((awtDirectory + "/libjawt.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
            }
            if (library) symbol = dlsym(library, "JAWT_GetAWT");
        }
        getAwt = reinterpret_cast<GetAwt>(symbol);
        if (getAwt) cached.store(getAwt);
    }
    return getAwt ? getAwt(env, awt) : JNI_FALSE;
}
