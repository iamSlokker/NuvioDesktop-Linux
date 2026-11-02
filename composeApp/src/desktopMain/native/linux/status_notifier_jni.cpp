#include "status_notifier.h"
#include <jni.h>
#include <glib.h>

namespace {
// GetStringUTFChars is modified UTF-8, which D-Bus refuses for anything outside the BMP.
std::string utf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    auto *chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    auto *converted = g_utf16_to_utf8(reinterpret_cast<const gunichar2 *>(chars), env->GetStringLength(value), nullptr, nullptr, nullptr);
    env->ReleaseStringChars(value, chars);
    std::string result = converted ? converted : "";
    g_free(converted);
    return result;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_core_ui_LinuxTrayNative_available(JNIEnv *, jobject) {
    return nuvio::tray::available();
}

// [icons] holds each square icon of [sizes] one after the other, as network-order ARGB32.
extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_core_ui_LinuxTrayNative_open(JNIEnv *env, jobject, jstring title, jstring openLabel,
        jstring info, jstring exitLabel, jintArray sizes, jbyteArray icons) {
    nuvio::tray::Config config{utf8(env, title), utf8(env, openLabel), utf8(env, info), utf8(env, exitLabel), {}};
    const auto count = env->GetArrayLength(sizes);
    const auto total = env->GetArrayLength(icons);
    std::vector<jint> sides(static_cast<size_t>(count));
    env->GetIntArrayRegion(sizes, 0, count, sides.data());
    jsize offset = 0;
    for (const jint side : sides) {
        const jlong length = static_cast<jlong>(side) * side * 4;
        if (side <= 0 || offset + length > total) return 0;
        nuvio::tray::Pixmap pixmap{side, side, std::vector<uint8_t>(static_cast<size_t>(length))};
        env->GetByteArrayRegion(icons, offset, static_cast<jsize>(length), reinterpret_cast<jbyte *>(pixmap.argb.data()));
        config.icons.push_back(std::move(pixmap));
        offset += static_cast<jsize>(length);
    }
    return reinterpret_cast<jlong>(nuvio::tray::open(config));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_app_core_ui_LinuxTrayNative_next(JNIEnv *, jobject, jlong handle, jint timeoutMs) {
    return handle ? nuvio::tray::next(reinterpret_cast<nuvio::tray::Item *>(handle), timeoutMs) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_core_ui_LinuxTrayNative_close(JNIEnv *, jobject, jlong handle) {
    nuvio::tray::close(reinterpret_cast<nuvio::tray::Item *>(handle));
}
