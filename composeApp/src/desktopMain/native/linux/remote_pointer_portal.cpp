// org.freedesktop.portal.RemoteDesktop for the phone remote's Pointer and the controller cursor
// (remote_pointer_portal.h). The handshake (CreateSession, SelectDevices, Start) runs on the caller's
// thread with a private main context, as file_chooser_portal.cpp does; the session then lives on its
// own connection, watched by one thread for the portal closing it, and input is sent without replies.
#include "remote_pointer_portal.h"
#include <gio/gio.h>
#include <atomic>
#include <mutex>
#include <thread>
#include "dbus_connection.h"

namespace {
constexpr const char *desktopName = "org.freedesktop.portal.Desktop";
constexpr const char *desktopPath = "/org/freedesktop/portal/desktop";
constexpr const char *remoteInterface = "org.freedesktop.portal.RemoteDesktop";
constexpr const char *requestInterface = "org.freedesktop.portal.Request";
constexpr const char *sessionInterface = "org.freedesktop.portal.Session";
constexpr int callTimeoutMs = 30000;  // The first call may have to activate the portal.
constexpr guint32 keyboardDevice = 1, pointerDevice = 2;
constexpr guint32 persistentUntilRevoked = 2;
constexpr guint32 persistenceVersion = 2;  // restore_token and persist_mode arrived in version 2.
using nuvio::remotepointer::Outcome;

struct Wait {
    bool finished = false;
    bool portalGone = false;
    guint32 response = Outcome::Failed;
    GVariant *results = nullptr;
};

struct Session {
    std::mutex lifecycle;            // Held through start/stop, which may wait on the permission dialog.
    std::mutex lock;                 // Only for reading or publishing bus and handle.
    GDBusConnection *bus = nullptr;  // Guarded by lock; written under lifecycle.
    std::string handle;              // Guarded by lock; written under lifecycle.
    std::atomic<bool> live{false};
    GMainContext *watchContext = nullptr;
    GMainLoop *watchLoop = nullptr;
    std::thread watcher;
};

Session &session() {
    static Session instance;
    return instance;
}

std::string takeMessage(GError *error) {
    if (!error) return {};
    g_dbus_error_strip_remote_error(error);
    std::string text = error->message ? error->message : "";
    g_error_free(error);
    return text;
}

void onResponse(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
                GVariant *parameters, gpointer data) {
    auto &wait = *static_cast<Wait *>(data);
    g_variant_get(parameters, "(u@a{sv})", &wait.response, &wait.results);
    wait.finished = true;
}

void onOwnerChanged(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
                    GVariant *parameters, gpointer data) {
    const char *name = nullptr, *before = nullptr, *after = nullptr;
    g_variant_get(parameters, "(&s&s&s)", &name, &before, &after);
    if (!after || !*after) static_cast<Wait *>(data)->portalGone = true;
}

// A portal-side object path for our token: /org/freedesktop/portal/desktop/<kind>/<sender>/<token>.
std::string predictedPath(GDBusConnection *bus, const char *kind, const std::string &token) {
    std::string sender = g_dbus_connection_get_unique_name(bus);
    if (!sender.empty() && sender[0] == ':') sender.erase(0, 1);
    for (auto &character : sender) if (character == '.') character = '_';
    return std::string(desktopPath) + "/" + kind + "/" + sender + "/" + token;
}

GVariant *property(GDBusConnection *bus, const char *name, GError **failure) {
    GVariant *reply = g_dbus_connection_call_sync(bus, desktopName, desktopPath, "org.freedesktop.DBus.Properties",
        "Get", g_variant_new("(ss)", remoteInterface, name), G_VARIANT_TYPE("(v)"),
        G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, failure);
    if (!reply) return nullptr;
    GVariant *value = nullptr;
    g_variant_get(reply, "(v)", &value);
    g_variant_unref(reply);
    return value;
}

guint32 uintProperty(GDBusConnection *bus, const char *name, GError **failure) {
    GVariant *value = property(bus, name, failure);
    if (!value) return 0;
    const guint32 number = g_variant_is_of_type(value, G_VARIANT_TYPE_UINT32) ? g_variant_get_uint32(value) : 0;
    g_variant_unref(value);
    return number;
}

// One counter for every request: a static inside the template below would be one per lambda type,
// handing CreateSession and SelectDevices the same token (and so the same Request path).
std::atomic<unsigned> requestCounter{0};

struct Step {
    int status = Outcome::Failed;
    GVariant *results = nullptr;  // Owned by the caller when set.
    std::string message;
};

// One portal method that answers through a Request: its parameters are built with our handle_token, the
// Response is awaited on [context] until [deadline].
template<class Build>
Step request(GDBusConnection *bus, GMainContext *context, const char *method, const char *what, Build build,
             gint64 deadline) {
    Step step;
    Wait wait;
    const std::string token = "nuvio_pointer_" + std::to_string(++requestCounter);
    std::string handle = predictedPath(bus, "request", token);
    guint owner = g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus",
        "NameOwnerChanged", "/org/freedesktop/DBus", desktopName, G_DBUS_SIGNAL_FLAGS_NONE, onOwnerChanged, &wait, nullptr);
    guint subscription = g_dbus_connection_signal_subscribe(bus, desktopName, requestInterface, "Response",
        handle.c_str(), nullptr, G_DBUS_SIGNAL_FLAGS_NONE, onResponse, &wait, nullptr);
    GError *failure = nullptr;
    GVariant *reply = g_dbus_connection_call_sync(bus, desktopName, desktopPath, remoteInterface, method,
        build(token), G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, &failure);
    if (!reply) {
        step.message = std::string("the remote desktop portal refused ") + what + ": " + takeMessage(failure);
    } else {
        const char *path = nullptr;
        g_variant_get(reply, "(&o)", &path);
        if (handle != path) {
            // Portals before 0.9 pick their own path.
            handle = path;
            g_dbus_connection_signal_unsubscribe(bus, subscription);
            subscription = g_dbus_connection_signal_subscribe(bus, desktopName, requestInterface, "Response",
                handle.c_str(), nullptr, G_DBUS_SIGNAL_FLAGS_NONE, onResponse, &wait, nullptr);
        }
        g_variant_unref(reply);
        GSource *tick = g_timeout_source_new(500);
        g_source_set_callback(tick, +[](gpointer) -> gboolean { return G_SOURCE_CONTINUE; }, nullptr, nullptr);
        g_source_attach(tick, context);
        while (!wait.finished && !wait.portalGone && g_get_monotonic_time() < deadline) {
            g_main_context_iteration(context, TRUE);
        }
        g_source_destroy(tick);
        g_source_unref(tick);
        if (wait.finished) {
            step.status = static_cast<int>(wait.response);
            step.results = wait.results;
            wait.results = nullptr;
            if (wait.response == Outcome::Cancelled) step.message = "remote control was not allowed";
            else if (wait.response != Outcome::Started) {
                step.status = Outcome::Failed;
                step.message = std::string("the remote desktop portal reported an error on ") + what;
            }
        } else if (wait.portalGone) {
            step.status = Outcome::Unavailable;
            step.message = "the remote desktop portal stopped running";
        } else {
            step.status = Outcome::TimedOut;
            step.message = "the remote control permission was left unanswered";
            GVariant *closed = g_dbus_connection_call_sync(bus, desktopName, handle.c_str(), requestInterface, "Close",
                nullptr, nullptr, G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, nullptr);
            if (closed) g_variant_unref(closed);
        }
    }
    if (wait.results) g_variant_unref(wait.results);
    g_dbus_connection_signal_unsubscribe(bus, subscription);
    g_dbus_connection_signal_unsubscribe(bus, owner);
    return step;
}

std::string lookupString(GVariant *results, const char *key) {
    if (!results) return {};
    GVariant *value = g_variant_lookup_value(results, key, nullptr);
    if (!value) return {};
    std::string text;
    // session_handle is a string in older portals and an object path in newer ones.
    if (g_variant_is_of_type(value, G_VARIANT_TYPE_STRING) || g_variant_is_of_type(value, G_VARIANT_TYPE_OBJECT_PATH)) {
        text = g_variant_get_string(value, nullptr);
    }
    g_variant_unref(value);
    return text;
}

void closeSession(GDBusConnection *bus, const std::string &handle) {
    if (handle.empty()) return;
    GVariant *closed = g_dbus_connection_call_sync(bus, desktopName, handle.c_str(), sessionInterface, "Close",
        nullptr, nullptr, G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, nullptr);
    if (closed) g_variant_unref(closed);
}

void onSessionClosed(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *, GVariant *, gpointer) {
    session().live = false;
    g_main_loop_quit(session().watchLoop);
}

void onPortalOwner(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
                   GVariant *parameters, gpointer) {
    const char *name = nullptr, *before = nullptr, *after = nullptr;
    g_variant_get(parameters, "(&s&s&s)", &name, &before, &after);
    if (after && *after) return;
    session().live = false;
    g_main_loop_quit(session().watchLoop);
}

// Ends the watcher and drops the connection; the caller holds the lifecycle lock.
void releaseLocked(Session &state) {
    state.live = false;
    GDBusConnection *bus = nullptr;
    std::string handle;
    {
        std::lock_guard<std::mutex> guard(state.lock);
        std::swap(bus, state.bus);
        std::swap(handle, state.handle);
    }
    if (state.watchLoop) {
        g_main_loop_quit(state.watchLoop);
        if (state.watcher.joinable()) state.watcher.join();
        g_main_loop_unref(state.watchLoop);
        g_main_context_unref(state.watchContext);
        state.watchLoop = nullptr;
        state.watchContext = nullptr;
    }
    if (bus) {
        closeSession(bus, handle);
        g_dbus_connection_close_sync(bus, nullptr, nullptr);
        g_object_unref(bus);
    }
}

void watch(GDBusConnection *bus, std::string handle) {
    auto &state = session();
    g_main_context_push_thread_default(state.watchContext);
    guint closed = g_dbus_connection_signal_subscribe(bus, desktopName, sessionInterface, "Closed", handle.c_str(),
        nullptr, G_DBUS_SIGNAL_FLAGS_NONE, onSessionClosed, nullptr, nullptr);
    guint owner = g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus",
        "NameOwnerChanged", "/org/freedesktop/DBus", desktopName, G_DBUS_SIGNAL_FLAGS_NONE, onPortalOwner, nullptr, nullptr);
    g_main_loop_run(state.watchLoop);
    g_dbus_connection_signal_unsubscribe(bus, closed);
    g_dbus_connection_signal_unsubscribe(bus, owner);
    while (g_main_context_iteration(state.watchContext, FALSE)) {}
    g_main_context_pop_thread_default(state.watchContext);
}

nuvio::remotepointer::StartResult handshake(GDBusConnection *bus, GMainContext *context, const std::string &restoreToken,
                                            int timeoutMs, std::string &sessionHandle) {
    nuvio::remotepointer::StartResult result;
    GError *failure = nullptr;
    const guint32 version = uintProperty(bus, "version", &failure);
    if (failure) {
        result.message = "the remote desktop portal is unavailable: " + takeMessage(failure);
        return result;
    }
    const guint32 available = uintProperty(bus, "AvailableDeviceTypes", &failure);
    if (failure) takeMessage(failure);
    if (!(available & pointerDevice)) {
        result.status = Outcome::Unsupported;
        result.message = "the remote desktop portal offers no pointer";
        return result;
    }
    const gint64 deadline = g_get_monotonic_time() + static_cast<gint64>(timeoutMs) * 1000;
    std::string sessionToken;
    Step created = request(bus, context, "CreateSession", "the session", [&](const std::string &token) {
        sessionToken = token + "_session";
        GVariantBuilder options;
        g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
        g_variant_builder_add(&options, "{sv}", "handle_token", g_variant_new_string(token.c_str()));
        g_variant_builder_add(&options, "{sv}", "session_handle_token", g_variant_new_string(sessionToken.c_str()));
        return g_variant_new("(@a{sv})", g_variant_builder_end(&options));
    }, deadline);
    sessionHandle = lookupString(created.results, "session_handle");
    if (created.results) g_variant_unref(created.results);
    if (created.status != Outcome::Started) {
        result.status = created.status;
        result.message = created.message;
        return result;
    }
    if (sessionHandle.empty()) sessionHandle = predictedPath(bus, "session", sessionToken);
    Step selected = request(bus, context, "SelectDevices", "the devices", [&](const std::string &token) {
        GVariantBuilder options;
        g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
        g_variant_builder_add(&options, "{sv}", "handle_token", g_variant_new_string(token.c_str()));
        // The keyboard only carries Shift for sideways scrolling, as upstream's Robot does.
        g_variant_builder_add(&options, "{sv}", "types", g_variant_new_uint32((pointerDevice | keyboardDevice) & available));
        if (version >= persistenceVersion) {
            g_variant_builder_add(&options, "{sv}", "persist_mode", g_variant_new_uint32(persistentUntilRevoked));
            if (!restoreToken.empty()) {
                g_variant_builder_add(&options, "{sv}", "restore_token", g_variant_new_string(restoreToken.c_str()));
            }
        }
        return g_variant_new("(o@a{sv})", sessionHandle.c_str(), g_variant_builder_end(&options));
    }, deadline);
    if (selected.results) g_variant_unref(selected.results);
    if (selected.status != Outcome::Started) {
        result.status = selected.status;
        result.message = selected.message;
        return result;
    }
    Step started = request(bus, context, "Start", "remote control", [&](const std::string &token) {
        GVariantBuilder options;
        g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
        g_variant_builder_add(&options, "{sv}", "handle_token", g_variant_new_string(token.c_str()));
        return g_variant_new("(os@a{sv})", sessionHandle.c_str(), "", g_variant_builder_end(&options));
    }, deadline);
    result.status = started.status;
    result.message = started.message;
    result.restoreToken = lookupString(started.results, "restore_token");
    if (started.status == Outcome::Started && started.results) {
        guint32 devices = 0;
        if (g_variant_lookup(started.results, "devices", "u", &devices) && !(devices & pointerDevice)) {
            result.status = Outcome::Unsupported;
            result.message = "the pointer was not shared";
        }
    }
    if (started.results) g_variant_unref(started.results);
    return result;
}

// Input on the running session, without waiting for (or asking) a reply.
void notify(const char *method, GVariant *parameters) {
    auto &state = session();
    GDBusConnection *bus = nullptr;
    {
        std::lock_guard<std::mutex> guard(state.lock);
        if (state.live && state.bus) bus = static_cast<GDBusConnection *>(g_object_ref(state.bus));
    }
    if (!bus) {
        g_variant_unref(g_variant_ref_sink(parameters));
        return;
    }
    g_dbus_connection_call(bus, desktopName, desktopPath, remoteInterface, method, parameters, nullptr,
        G_DBUS_CALL_FLAGS_NONE, -1, nullptr, nullptr, nullptr);
    g_object_unref(bus);
}

std::string sessionHandle() {
    auto &state = session();
    std::lock_guard<std::mutex> guard(state.lock);
    return state.handle;
}
}

nuvio::remotepointer::StartResult nuvio::remotepointer::start(const std::string &restoreToken, int timeoutMs) {
    auto &state = session();
    std::lock_guard<std::mutex> guard(state.lifecycle);
    if (state.live) {
        StartResult running;
        running.status = Outcome::Started;
        return running;
    }
    releaseLocked(state);
    StartResult result;
    GMainContext *context = g_main_context_new();
    g_main_context_push_thread_default(context);
    GError *failure = nullptr;
    GDBusConnection *bus = nuvio::dbus::connect(G_BUS_TYPE_SESSION, callTimeoutMs, &failure);
    std::string handle;
    if (!bus) {
        result.message = "session bus unavailable: " + takeMessage(failure);
    } else {
        result = handshake(bus, context, restoreToken, timeoutMs, handle);
        if (result.status == Outcome::Started) {
            state.watchContext = g_main_context_new();
            state.watchLoop = g_main_loop_new(state.watchContext, FALSE);
            {
                std::lock_guard<std::mutex> published(state.lock);
                state.bus = bus;
                state.handle = handle;
            }
            state.live = true;
            state.watcher = std::thread(watch, bus, handle);
        } else {
            closeSession(bus, handle);
            g_dbus_connection_close_sync(bus, nullptr, nullptr);
            g_object_unref(bus);
        }
    }
    while (g_main_context_iteration(context, FALSE)) {}
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
    return result;
}

bool nuvio::remotepointer::active() {
    return session().live;
}

void nuvio::remotepointer::motion(double dx, double dy) {
    const std::string handle = sessionHandle();
    if (handle.empty()) return;
    notify("NotifyPointerMotion", g_variant_new("(oa{sv}dd)", handle.c_str(), nullptr, dx, dy));
}

void nuvio::remotepointer::button(int evdevCode, bool pressed) {
    const std::string handle = sessionHandle();
    if (handle.empty()) return;
    notify("NotifyPointerButton", g_variant_new("(oa{sv}iu)", handle.c_str(), nullptr, evdevCode, pressed ? 1u : 0u));
}

void nuvio::remotepointer::axis(int axis, int steps) {
    const std::string handle = sessionHandle();
    if (handle.empty() || steps == 0) return;
    notify("NotifyPointerAxisDiscrete", g_variant_new("(oa{sv}ui)", handle.c_str(), nullptr, static_cast<guint32>(axis), steps));
}

void nuvio::remotepointer::key(int evdevCode, bool pressed) {
    const std::string handle = sessionHandle();
    if (handle.empty()) return;
    notify("NotifyKeyboardKeycode", g_variant_new("(oa{sv}iu)", handle.c_str(), nullptr, evdevCode, pressed ? 1u : 0u));
}

void nuvio::remotepointer::stop() {
    auto &state = session();
    std::lock_guard<std::mutex> guard(state.lifecycle);
    releaseLocked(state);
}
