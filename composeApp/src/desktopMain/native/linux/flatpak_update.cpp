// Flatpak self-update through org.freedesktop.portal.Flatpak (UpdateMonitor.Update).
// One blocking call on the caller's thread with a private main context. The portal itself asks the
// user for permission the first time and verifies the update against the installation's remote
// (GPG), so nothing here downloads or trusts a file.
#include "flatpak_update.h"
#include <gio/gio.h>
#include "dbus_connection.h"

namespace {
constexpr const char *portalName = "org.freedesktop.portal.Flatpak";
constexpr const char *portalPath = "/org/freedesktop/portal/Flatpak";
constexpr const char *monitorInterface = "org.freedesktop.portal.Flatpak.UpdateMonitor";
constexpr int callTimeoutMs = 10000;
using nuvio::flatpak::Status;

struct Run {
    const std::function<void(int, int, int)> *onProgress = nullptr;
    bool finished = false;
    bool sawOperations = false;
    guint32 status = 0;
    std::string error;
    std::string message;
    gint64 lastEvent = g_get_monotonic_time();
};

std::string takeMessage(GError *error) {
    if (!error) return {};
    g_dbus_error_strip_remote_error(error);
    std::string text = error->message ? error->message : "";
    g_error_free(error);
    return text;
}

void onProgressSignal(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
                      GVariant *parameters, gpointer data) {
    auto &run = *static_cast<Run *>(data);
    run.lastEvent = g_get_monotonic_time();
    GVariant *info = nullptr;
    g_variant_get(parameters, "(@a{sv})", &info);
    guint32 status = Status::Running, op = 0, ops = 0, progress = 0;
    const char *error = nullptr, *message = nullptr;
    g_variant_lookup(info, "status", "u", &status);
    g_variant_lookup(info, "op", "u", &op);
    g_variant_lookup(info, "n_ops", "u", &ops);
    g_variant_lookup(info, "progress", "u", &progress);
    g_variant_lookup(info, "error", "&s", &error);
    g_variant_lookup(info, "error_message", "&s", &message);
    if (status == Status::Running) {
        if (ops > 0) run.sawOperations = true;
        (*run.onProgress)(static_cast<int>(op), static_cast<int>(ops), static_cast<int>(progress));
    } else {
        run.finished = true;
        // A transaction with nothing to do may finish without ever reporting an operation.
        run.status = status == Status::Done && !run.sawOperations ? static_cast<guint32>(Status::Empty) : status;
        run.error = error ? error : "";
        run.message = message ? message : "";
    }
    g_variant_unref(info);
}
}

nuvio::flatpak::UpdateResult nuvio::flatpak::update(const std::function<void(int, int, int)> &onProgress,
                                                    int idleTimeoutMs) {
    Run run;
    run.onProgress = &onProgress;
    UpdateResult result;
    GMainContext *context = g_main_context_new();
    g_main_context_push_thread_default(context);
    GError *failure = nullptr;
    GDBusConnection *bus = nuvio::dbus::connect(G_BUS_TYPE_SESSION, callTimeoutMs, &failure);
    std::string monitor;
    guint subscription = 0;
    if (!bus) {
        result.message = "session bus unavailable: " + takeMessage(failure);
    } else {
        GVariantBuilder options;
        g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
        g_variant_builder_add(&options, "{sv}", "handle_token", g_variant_new_string("nuvio_update"));
        // Activates the portal; the monitor object is private to this connection.
        GVariant *reply = g_dbus_connection_call_sync(bus, portalName, portalPath, portalName, "CreateUpdateMonitor",
            g_variant_new("(@a{sv})", g_variant_builder_end(&options)), G_VARIANT_TYPE("(o)"),
            G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, &failure);
        if (!reply) {
            result.message = "Flatpak portal unavailable: " + takeMessage(failure);
        } else {
            const char *path = nullptr;
            g_variant_get(reply, "(&o)", &path);
            monitor = path;
            g_variant_unref(reply);
            // Subscribe before Update: the portal may report progress right after replying.
            subscription = g_dbus_connection_signal_subscribe(bus, portalName, monitorInterface, "Progress",
                monitor.c_str(), nullptr, G_DBUS_SIGNAL_FLAGS_NONE, onProgressSignal, &run, nullptr);
            reply = g_dbus_connection_call_sync(bus, portalName, monitor.c_str(), monitorInterface, "Update",
                g_variant_new("(s@a{sv})", "", g_variant_new_array(G_VARIANT_TYPE("{sv}"), nullptr, 0)),
                nullptr, G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, &failure);
            if (!reply) {
                result.message = "Flatpak portal refused the update: " + takeMessage(failure);
            } else {
                g_variant_unref(reply);
                run.lastEvent = g_get_monotonic_time();
                // Idle-bounded, not total-bounded: the first update waits on the portal's
                // permission dialog, and a large download keeps sending progress.
                const gint64 idleLimit = static_cast<gint64>(idleTimeoutMs) * 1000;
                GSource *tick = g_timeout_source_new(500);
                g_source_set_callback(tick, +[](gpointer) -> gboolean { return G_SOURCE_CONTINUE; }, nullptr, nullptr);
                g_source_attach(tick, context);
                while (!run.finished && g_get_monotonic_time() - run.lastEvent < idleLimit) {
                    g_main_context_iteration(context, TRUE);
                }
                g_source_destroy(tick);
                g_source_unref(tick);
                if (run.finished) {
                    result.status = static_cast<int>(run.status);
                    result.error = run.error;
                    result.message = run.message;
                } else {
                    result.status = Status::TimedOut;
                    result.message = "the Flatpak portal stopped responding";
                }
            }
        }
        if (subscription) g_dbus_connection_signal_unsubscribe(bus, subscription);
        if (!monitor.empty()) {
            // Close cancels a still-running update and releases the portal's monitor.
            GVariant *closed = g_dbus_connection_call_sync(bus, portalName, monitor.c_str(), monitorInterface, "Close",
                nullptr, nullptr, G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, nullptr);
            if (closed) g_variant_unref(closed);
        }
        g_dbus_connection_close_sync(bus, nullptr, nullptr);
        g_object_unref(bus);
    }
    while (g_main_context_iteration(context, FALSE)) {}
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
    return result;
}
