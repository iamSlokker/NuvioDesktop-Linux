// JNI entry for LinuxBotGuardPageNative (fork:linux): YouTube's BotGuard page in an offscreen WebKitGTK
// view, for PO tokens where no Chromium can be started (the Flatpak's sandbox, or none installed).
// Runs on the controls overlay's GTK thread; every call blocks its JNI caller until the GTK side answers
// or the timeout passes. The page is given to create() whole: it loads no URL and makes no request.
#include <jni.h>
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <future>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>

#include "window_chrome.h"

namespace {
struct Page {
    GtkWidget *window = nullptr; // GTK thread only, like the other fields but dead.
    WebKitWebView *view = nullptr;
    WebKitWebContext *context = nullptr;
    std::atomic<bool> dead{false};
    std::shared_ptr<std::promise<void>> loaded = std::make_shared<std::promise<void>>();
    bool loadSettled = false;

    void settleLoad(const char *error) {
        if (loadSettled) return;
        loadSettled = true;
        if (error) loaded->set_exception(std::make_exception_ptr(std::runtime_error(error)));
        else loaded->set_value();
    }

    void destroy() { // GTK thread only.
        dead = true;
        if (view) g_signal_handlers_disconnect_by_data(view, this);
        if (window) { gtk_widget_destroy(window); g_object_unref(window); window = nullptr; }
        if (view) { g_object_unref(view); view = nullptr; }
        if (context) { g_object_unref(context); context = nullptr; }
    }
};

using PageHandle = std::shared_ptr<Page>;

// Handles are ids, not pointers: Kotlin may close a page while another coroutine is still minting on it.
std::mutex pagesLock;
std::unordered_map<jlong, PageHandle> pages;
jlong nextPage = 1;

PageHandle pageOf(jlong id) {
    std::lock_guard<std::mutex> lock(pagesLock);
    auto found = pages.find(id);
    return found == pages.end() ? nullptr : found->second;
}

std::string toStd(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("out of memory");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwJava(JNIEnv *env, const std::string &message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) {
        env->ThrowNew(type, message.c_str());
        env->DeleteLocalRef(type);
    }
}

template <typename T>
T await(std::future<T> &future, jint timeoutMs, const char *what) {
    if (future.wait_for(std::chrono::milliseconds(timeoutMs)) != std::future_status::ready)
        throw std::runtime_error(std::string(what) + " timed out");
    return future.get();
}

void build(Page &page, const std::string &html, const std::string &baseUri) { // GTK thread only.
    // Throwaway: no cookies, cache or storage outlive the page.
    page.context = webkit_web_context_new_ephemeral();
    page.view = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW, "web-context", page.context, nullptr));
    g_object_ref_sink(page.view);
    WebKitSettings *settings = webkit_web_view_get_settings(page.view);
    webkit_settings_set_hardware_acceleration_policy(settings, WEBKIT_HARDWARE_ACCELERATION_POLICY_NEVER);
    webkit_settings_set_javascript_can_open_windows_automatically(settings, FALSE);
    webkit_settings_set_enable_media(settings, FALSE);
    webkit_settings_set_enable_webaudio(settings, FALSE);
    webkit_settings_set_enable_webgl(settings, FALSE);
    g_signal_connect(page.view, "load-changed", G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent event, gpointer data) {
        if (event == WEBKIT_LOAD_FINISHED) static_cast<Page *>(data)->settleLoad(nullptr);
    }), &page);
    g_signal_connect(page.view, "load-failed", G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent, const gchar *,
        GError *error, gpointer data) -> gboolean {
        static_cast<Page *>(data)->settleLoad(error ? error->message : "the page failed to load");
        return FALSE;
    }), &page);
    g_signal_connect(page.view, "web-process-terminated", G_CALLBACK(+[](WebKitWebView *,
        WebKitWebProcessTerminationReason reason, gpointer data) {
        std::fprintf(stderr, "Linux BotGuard page: WebProcess terminated (reason %d)\n", static_cast<int>(reason));
        auto *page = static_cast<Page *>(data);
        page->dead = true;
        page->settleLoad("the WebProcess ended");
    }), &page);
    // Never mapped on screen; an offscreen toplevel still runs the page's timers.
    page.window = gtk_offscreen_window_new();
    g_object_ref_sink(page.window);
    gtk_container_add(GTK_CONTAINER(page.window), GTK_WIDGET(page.view));
    gtk_widget_show_all(page.window);
    webkit_web_view_load_html(page.view, html.c_str(), baseUri.c_str());
}

void dispose(PageHandle page) {
    postOnLinuxGtkThread([page] { page->destroy(); });
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_music_potoken_LinuxBotGuardPageNative_create(JNIEnv *env, jobject, jstring html,
                                                                          jstring baseUri, jint timeoutMs) {
    try {
        auto page = std::make_shared<Page>();
        auto loaded = page->loaded->get_future();
        auto built = std::make_shared<std::promise<void>>();
        auto ready = built->get_future();
        postOnLinuxGtkThreadStarting([page, built, html = toStd(env, html), baseUri = toStd(env, baseUri)] {
            try { build(*page, html, baseUri); built->set_value(); }
            catch (...) { built->set_exception(std::current_exception()); }
        });
        try {
            await(ready, timeoutMs, "creating the page");
            await(loaded, timeoutMs, "loading the page");
        } catch (...) {
            dispose(page);
            throw;
        }
        std::lock_guard<std::mutex> lock(pagesLock);
        pages[nextPage] = page;
        return nextPage++;
    } catch (const std::exception &error) {
        throwJava(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_music_potoken_LinuxBotGuardPageNative_evaluate(JNIEnv *env, jobject, jlong handle,
                                                                            jstring expression, jint timeoutMs) {
    try {
        PageHandle page = pageOf(handle);
        if (!page || page->dead) throw std::runtime_error("the page is gone");
        // Promise resolution and the string conversion happen on the GTK side.
        auto answer = std::make_shared<std::promise<std::string>>();
        auto result = answer->get_future();
        std::string body = "return (" + toStd(env, expression) + ");";
        postOnLinuxGtkThread([page, answer, body] {
            if (!page->view) { answer->set_exception(std::make_exception_ptr(std::runtime_error("the page is gone"))); return; }
            struct Call { PageHandle page; std::shared_ptr<std::promise<std::string>> answer; };
            webkit_web_view_call_async_javascript_function(page->view, body.c_str(), body.size(), nullptr, nullptr,
                nullptr, nullptr, [](GObject *object, GAsyncResult *asyncResult, gpointer data) {
                    std::unique_ptr<Call> call(static_cast<Call *>(data));
                    GError *error = nullptr;
                    JSCValue *value = webkit_web_view_call_async_javascript_function_finish(
                        WEBKIT_WEB_VIEW(object), asyncResult, &error);
                    if (!value) {
                        std::string message = error ? error->message : "script error";
                        if (error) g_error_free(error);
                        call->answer->set_exception(std::make_exception_ptr(std::runtime_error("The page failed: " + message)));
                        return;
                    }
                    if (jsc_value_is_undefined(value) || jsc_value_is_null(value)) {
                        g_object_unref(value);
                        call->answer->set_exception(std::make_exception_ptr(std::runtime_error("The page returned no value")));
                        return;
                    }
                    gchar *text = jsc_value_to_string(value);
                    call->answer->set_value(text ? text : "");
                    g_free(text);
                    g_object_unref(value);
                }, new Call{page, answer});
        });
        std::string text = await(result, timeoutMs, "the page");
        return env->NewStringUTF(text.c_str());
    } catch (const std::exception &error) {
        throwJava(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_music_potoken_LinuxBotGuardPageNative_isDead(JNIEnv *, jobject, jlong handle) {
    PageHandle page = pageOf(handle);
    return !page || page->dead ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_music_potoken_LinuxBotGuardPageNative_close(JNIEnv *, jobject, jlong handle) {
    PageHandle page;
    {
        std::lock_guard<std::mutex> lock(pagesLock);
        auto found = pages.find(handle);
        if (found == pages.end()) return;
        page = found->second;
        pages.erase(found);
    }
    page->dead = true;
    dispose(page);
}
