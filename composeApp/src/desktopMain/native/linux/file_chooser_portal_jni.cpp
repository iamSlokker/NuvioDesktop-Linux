// JNI entries for LinuxFileChooserPortalNative; the portal logic lives in file_chooser_portal.cpp.
#include <glib.h>
#include <jni.h>
#include <string>
#include <vector>
#include "file_chooser_portal.h"

namespace {
// JNI's "UTF" is modified UTF-8, which GVariant rejects for anything outside the BMP (an emoji in a
// suggested file name would otherwise abort the process). Convert through UTF-16 on both sides.
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

jobjectArray toJavaArray(JNIEnv *env, const std::vector<std::string> &values) {
    auto array = env->NewObjectArray(static_cast<jsize>(values.size()), env->FindClass("java/lang/String"), nullptr);
    if (!array) return nullptr;
    for (jsize index = 0; index < static_cast<jsize>(values.size()); ++index) {
        auto value = toJava(env, values[static_cast<size_t>(index)]);
        env->SetObjectArrayElement(array, index, value);
        env->DeleteLocalRef(value);
    }
    return array;
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_nuvio_app_core_portal_LinuxFileChooserPortalNative_choose(JNIEnv *env, jobject, jboolean save, jboolean directory,
        jlong parentDrawable, jstring title, jstring currentFolder, jstring currentName, jstring filterName,
        jobjectArray filterPatterns, jint timeoutMs) {
    nuvio::filechooser::Request request;
    request.save = save;
    request.directory = directory;
    if (parentDrawable) {
        const unsigned long managed = nuvio::filechooser::managedAncestor(static_cast<unsigned long>(parentDrawable));
        if (managed) {
            gchar *handle = g_strdup_printf("x11:%lx", managed);
            request.parentWindow = handle;
            g_free(handle);
        }
    }
    request.title = fromJava(env, title);
    request.currentFolder = fromJava(env, currentFolder);
    request.currentName = fromJava(env, currentName);
    request.filterName = fromJava(env, filterName);
    const jsize patterns = filterPatterns ? env->GetArrayLength(filterPatterns) : 0;
    for (jsize index = 0; index < patterns; ++index) {
        auto pattern = static_cast<jstring>(env->GetObjectArrayElement(filterPatterns, index));
        request.filterPatterns.push_back(fromJava(env, pattern));
        env->DeleteLocalRef(pattern);
    }
    const auto result = nuvio::filechooser::choose(request, timeoutMs);
    return toJavaArray(env, {std::to_string(result.status), result.uri, result.message});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_nuvio_app_core_portal_LinuxFileChooserPortalNative_retrieveFiles(JNIEnv *env, jobject, jstring key, jint timeoutMs) {
    const auto result = nuvio::filechooser::retrieveFiles(fromJava(env, key), timeoutMs);
    std::vector<std::string> values{result.ok ? "1" : "0", result.message};
    values.insert(values.end(), result.files.begin(), result.files.end());
    return toJavaArray(env, values);
}
