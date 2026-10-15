// JNI entry for LinuxFlatpakPortalNative.update; the portal logic lives in flatpak_update.cpp.
#include <jni.h>
#include <string>
#include "flatpak_update.h"

namespace {
jobjectArray toJava(JNIEnv *env, const nuvio::flatpak::UpdateResult &result) {
    auto array = env->NewObjectArray(3, env->FindClass("java/lang/String"), nullptr);
    if (!array) return nullptr;
    const std::string values[] = {std::to_string(result.status), result.error, result.message};
    for (int i = 0; i < 3; ++i) {
        auto value = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(array, i, value);
        env->DeleteLocalRef(value);
    }
    return array;
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_nuvio_app_features_updater_LinuxFlatpakPortalNative_update(JNIEnv *env, jobject, jobject callback,
                                                                     jint idleTimeoutMs) {
    auto onProgress = env->GetMethodID(env->GetObjectClass(callback), "onProgress", "(III)V");
    if (!onProgress) {
        env->ExceptionClear();
        nuvio::flatpak::UpdateResult missing;
        missing.message = "progress callback missing";
        return toJava(env, missing);
    }
    auto result = nuvio::flatpak::update([&](int op, int ops, int progress) {
        env->CallVoidMethod(callback, onProgress, op, ops, progress);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }, idleTimeoutMs);
    return toJava(env, result);
}
