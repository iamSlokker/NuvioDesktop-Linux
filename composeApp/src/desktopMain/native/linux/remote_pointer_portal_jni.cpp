// JNI entries for LinuxRemotePointerNative; the portal logic lives in remote_pointer_portal.cpp.
#include <glib.h>
#include <jni.h>
#include <string>
#include "remote_pointer_portal.h"

namespace {
// The restore token is ASCII in practice; go through UTF-16 anyway, as file_chooser_portal_jni.cpp does.
std::string fromJava(JNIEnv *env, jstring value) {
    if (!value) return {};
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    gchar *utf8 = g_utf16_to_utf8(reinterpret_cast<const gunichar2 *>(chars), length, nullptr, nullptr, nullptr);
    env->ReleaseStringChars(value, chars);
    std::string text = utf8 ? utf8 : "";
    g_free(utf8);
    return text;
}

jstring toJava(JNIEnv *env, const std::string &value) {
    gchar *valid = g_utf8_make_valid(value.c_str(), static_cast<gssize>(value.size()));
    glong length = 0;
    gunichar2 *utf16 = g_utf8_to_utf16(valid, -1, nullptr, &length, nullptr);
    jstring text = utf16 ? env->NewString(reinterpret_cast<const jchar *>(utf16), static_cast<jsize>(length)) : env->NewStringUTF("");
    g_free(utf16);
    g_free(valid);
    return text;
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_nuvio_app_features_remote_LinuxRemotePointerNative_start(JNIEnv *env, jobject, jstring restoreToken, jint timeoutMs) {
    const auto result = nuvio::remotepointer::start(fromJava(env, restoreToken), timeoutMs);
    const std::string values[] = {std::to_string(result.status), result.restoreToken, result.message};
    auto array = env->NewObjectArray(3, env->FindClass("java/lang/String"), nullptr);
    if (!array) return nullptr;
    for (jsize index = 0; index < 3; ++index) {
        auto value = toJava(env, values[index]);
        env->SetObjectArrayElement(array, index, value);
        env->DeleteLocalRef(value);
    }
    return array;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_remote_LinuxRemotePointerNative_isActive(JNIEnv *, jobject) {
    return nuvio::remotepointer::active() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_remote_LinuxRemotePointerNative_motion(JNIEnv *, jobject, jdouble dx, jdouble dy) {
    nuvio::remotepointer::motion(dx, dy);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_remote_LinuxRemotePointerNative_button(JNIEnv *, jobject, jint code, jboolean pressed) {
    nuvio::remotepointer::button(code, pressed == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_remote_LinuxRemotePointerNative_axis(JNIEnv *, jobject, jint axis, jint steps) {
    nuvio::remotepointer::axis(axis, steps);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_remote_LinuxRemotePointerNative_key(JNIEnv *, jobject, jint code, jboolean pressed) {
    nuvio::remotepointer::key(code, pressed == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_remote_LinuxRemotePointerNative_stop(JNIEnv *, jobject) {
    nuvio::remotepointer::stop();
}
