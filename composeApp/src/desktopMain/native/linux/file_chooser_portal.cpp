// Portal file chooser for the sealed Flatpak: org.freedesktop.portal.FileChooser plus
// org.freedesktop.portal.FileTransfer. One blocking call on the caller's thread with a private main
// context, as in flatpak_update.cpp. The portal runs the dialog outside the sandbox and exports what
// the user chose through the document portal, so no filesystem permission is involved.
#include "file_chooser_portal.h"
#include <gio/gio.h>
#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <atomic>
#include "dbus_connection.h"

namespace {
constexpr const char *desktopName = "org.freedesktop.portal.Desktop";
constexpr const char *desktopPath = "/org/freedesktop/portal/desktop";
constexpr const char *chooserInterface = "org.freedesktop.portal.FileChooser";
constexpr const char *requestInterface = "org.freedesktop.portal.Request";
constexpr const char *documentsName = "org.freedesktop.portal.Documents";
constexpr const char *documentsPath = "/org/freedesktop/portal/documents";
constexpr const char *transferInterface = "org.freedesktop.portal.FileTransfer";
constexpr int callTimeoutMs = 30000;  // The first call may have to activate the portal.
constexpr guint32 directoryVersion = 3;  // The `directory` option arrived in FileChooser version 3.
using nuvio::filechooser::Outcome;

struct Wait {
    bool finished = false;
    bool portalGone = false;
    guint32 response = Outcome::Failed;
    std::string uri;
};

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
    guint32 response = Outcome::Failed;
    GVariant *results = nullptr;
    g_variant_get(parameters, "(u@a{sv})", &response, &results);
    const char **uris = nullptr;
    if (g_variant_lookup(results, "uris", "^a&s", &uris)) {
        if (uris && uris[0]) wait.uri = uris[0];
        g_free(uris);
    }
    g_variant_unref(results);
    wait.response = response;
    wait.finished = true;
}

void onOwnerChanged(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
                    GVariant *parameters, gpointer data) {
    const char *name = nullptr, *before = nullptr, *after = nullptr;
    g_variant_get(parameters, "(&s&s&s)", &name, &before, &after);
    if (!after || !*after) static_cast<Wait *>(data)->portalGone = true;
}

// False when the portal cannot be reached. A version of an unexpected type reads as 0.
bool chooserVersion(GDBusConnection *bus, guint32 &version, GError **failure) {
    GVariant *reply = g_dbus_connection_call_sync(bus, desktopName, desktopPath, "org.freedesktop.DBus.Properties",
        "Get", g_variant_new("(ss)", chooserInterface, "version"), G_VARIANT_TYPE("(v)"),
        G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, failure);
    if (!reply) return false;
    GVariant *value = nullptr;
    g_variant_get(reply, "(v)", &value);
    version = g_variant_is_of_type(value, G_VARIANT_TYPE_UINT32) ? g_variant_get_uint32(value) : 0;
    g_variant_unref(value);
    g_variant_unref(reply);
    return true;
}

GVariant *buildOptions(const nuvio::filechooser::Request &request, const std::string &token) {
    GVariantBuilder options;
    g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&options, "{sv}", "handle_token", g_variant_new_string(token.c_str()));
    g_variant_builder_add(&options, "{sv}", "modal", g_variant_new_boolean(TRUE));
    if (request.directory) g_variant_builder_add(&options, "{sv}", "directory", g_variant_new_boolean(TRUE));
    if (!request.currentFolder.empty()) {
        // The portal turns a /run/user/*/doc path back into the host folder itself.
        g_variant_builder_add(&options, "{sv}", "current_folder", g_variant_new_bytestring(request.currentFolder.c_str()));
    }
    if (request.save && !request.currentName.empty()) {
        g_variant_builder_add(&options, "{sv}", "current_name", g_variant_new_string(request.currentName.c_str()));
    }
    if (!request.filterPatterns.empty()) {
        GVariantBuilder patterns;
        g_variant_builder_init(&patterns, G_VARIANT_TYPE("a(us)"));
        // 0 is a glob; the other kind, 1, is a MIME type.
        for (const auto &pattern : request.filterPatterns) g_variant_builder_add(&patterns, "(us)", 0, pattern.c_str());
        GVariantBuilder filters;
        g_variant_builder_init(&filters, G_VARIANT_TYPE("a(sa(us))"));
        g_variant_builder_add(&filters, "(s@a(us))", request.filterName.c_str(), g_variant_builder_end(&patterns));
        g_variant_builder_add(&options, "{sv}", "filters", g_variant_builder_end(&filters));
    }
    return g_variant_builder_end(&options);
}

// The Request object's path is ours to predict, so nothing can answer before the subscription exists.
std::string requestPath(GDBusConnection *bus, const std::string &token) {
    std::string sender = g_dbus_connection_get_unique_name(bus);
    if (!sender.empty() && sender[0] == ':') sender.erase(0, 1);
    for (auto &character : sender) if (character == '.') character = '_';
    return std::string(desktopPath) + "/request/" + sender + "/" + token;
}

nuvio::filechooser::Result runChooser(GDBusConnection *bus, GMainContext *context,
                                      const nuvio::filechooser::Request &request, int timeoutMs) {
    static std::atomic<unsigned> counter{0};
    nuvio::filechooser::Result result;
    GError *failure = nullptr;
    guint32 version = 0;
    if (!chooserVersion(bus, version, &failure)) {
        result.message = "the file chooser portal is unavailable: " + takeMessage(failure);
        return result;
    }
    if (request.directory && version < directoryVersion) {
        result.status = Outcome::Unsupported;
        result.message = "this xdg-desktop-portal cannot choose folders (FileChooser version " + std::to_string(version) + ")";
        return result;
    }
    Wait wait;
    const std::string token = "nuvio_chooser_" + std::to_string(++counter);
    std::string handle = requestPath(bus, token);
    guint owner = g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus",
        "NameOwnerChanged", "/org/freedesktop/DBus", desktopName, G_DBUS_SIGNAL_FLAGS_NONE, onOwnerChanged, &wait, nullptr);
    guint subscription = g_dbus_connection_signal_subscribe(bus, desktopName, requestInterface, "Response",
        handle.c_str(), nullptr, G_DBUS_SIGNAL_FLAGS_NONE, onResponse, &wait, nullptr);
    GVariant *reply = g_dbus_connection_call_sync(bus, desktopName, desktopPath, chooserInterface,
        request.save ? "SaveFile" : "OpenFile",
        g_variant_new("(ss@a{sv})", request.parentWindow.c_str(), request.title.c_str(), buildOptions(request, token)),
        G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, &failure);
    if (!reply) {
        result.status = Outcome::Failed;
        result.message = "the file chooser portal refused the request: " + takeMessage(failure);
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
        const gint64 deadline = g_get_monotonic_time() + static_cast<gint64>(timeoutMs) * 1000;
        GSource *tick = g_timeout_source_new(500);
        g_source_set_callback(tick, +[](gpointer) -> gboolean { return G_SOURCE_CONTINUE; }, nullptr, nullptr);
        g_source_attach(tick, context);
        while (!wait.finished && !wait.portalGone && g_get_monotonic_time() < deadline) {
            g_main_context_iteration(context, TRUE);
        }
        g_source_destroy(tick);
        g_source_unref(tick);
        if (wait.finished) {
            result.status = static_cast<int>(wait.response);
            result.uri = wait.uri;
            if (wait.response == Outcome::Picked && wait.uri.empty()) {
                result.status = Outcome::NothingShared;
                result.message = "the portal could not share that location with Nuvio";
            } else if (wait.response != Outcome::Picked && wait.response != Outcome::Cancelled) {
                result.status = Outcome::Failed;
                result.message = "the file chooser portal reported an error";
            }
        } else if (wait.portalGone) {
            result.status = Outcome::Unavailable;
            result.message = "the file chooser portal stopped running";
        } else {
            result.status = Outcome::TimedOut;
            result.message = "the file chooser was left open too long";
            GVariant *closed = g_dbus_connection_call_sync(bus, desktopName, handle.c_str(), requestInterface, "Close",
                nullptr, nullptr, G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, nullptr);
            if (closed) g_variant_unref(closed);
        }
    }
    g_dbus_connection_signal_unsubscribe(bus, subscription);
    g_dbus_connection_signal_unsubscribe(bus, owner);
    return result;
}
}

nuvio::filechooser::Result nuvio::filechooser::choose(const Request &request, int timeoutMs) {
    Result result;
    GMainContext *context = g_main_context_new();
    g_main_context_push_thread_default(context);
    GError *failure = nullptr;
    GDBusConnection *bus = nuvio::dbus::connect(G_BUS_TYPE_SESSION, callTimeoutMs, &failure);
    if (!bus) {
        result.message = "session bus unavailable: " + takeMessage(failure);
    } else {
        result = runChooser(bus, context, request, timeoutMs);
        g_dbus_connection_close_sync(bus, nullptr, nullptr);
        g_object_unref(bus);
    }
    while (g_main_context_iteration(context, FALSE)) {}
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
    return result;
}

nuvio::filechooser::TransferResult nuvio::filechooser::retrieveFiles(const std::string &key, int timeoutMs) {
    TransferResult result;
    GMainContext *context = g_main_context_new();
    g_main_context_push_thread_default(context);
    GError *failure = nullptr;
    GDBusConnection *bus = nuvio::dbus::connect(G_BUS_TYPE_SESSION, timeoutMs, &failure);
    if (!bus) {
        result.message = "session bus unavailable: " + takeMessage(failure);
    } else {
        GVariant *reply = g_dbus_connection_call_sync(bus, documentsName, documentsPath, transferInterface, "RetrieveFiles",
            g_variant_new("(s@a{sv})", key.c_str(), g_variant_new_array(G_VARIANT_TYPE("{sv}"), nullptr, 0)),
            G_VARIANT_TYPE("(as)"), G_DBUS_CALL_FLAGS_NONE, timeoutMs, nullptr, &failure);
        if (!reply) {
            result.message = "the file transfer portal refused the drop: " + takeMessage(failure);
        } else {
            const char **files = nullptr;
            g_variant_get(reply, "(^a&s)", &files);
            for (auto entry = files; entry && *entry; ++entry) result.files.emplace_back(*entry);
            g_free(files);
            g_variant_unref(reply);
            result.ok = true;
        }
        g_dbus_connection_close_sync(bus, nullptr, nullptr);
        g_object_unref(bus);
    }
    while (g_main_context_iteration(context, FALSE)) {}
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
    return result;
}

unsigned long nuvio::filechooser::managedAncestor(unsigned long source) {
    Display *display = XOpenDisplay(nullptr);
    if (!display) return 0;
    const Atom state = XInternAtom(display, "WM_STATE", True);
    unsigned long found = 0;
    Window window = source;
    // Bounded: an X11 window tree is shallow, and reparenting window managers add a level or two.
    for (int depth = 0; state != None && window != None && depth < 16; ++depth) {
        Atom type = None;
        int format = 0;
        unsigned long count = 0, remaining = 0;
        unsigned char *data = nullptr;
        const int status = XGetWindowProperty(display, window, state, 0, 2, False, state,
                                              &type, &format, &count, &remaining, &data);
        if (data) XFree(data);
        if (status == Success && type == state) {
            found = window;
            break;
        }
        Window root = None, parent = None, *children = nullptr;
        unsigned int childCount = 0;
        const bool valid = XQueryTree(display, window, &root, &parent, &children, &childCount);
        if (children) XFree(children);
        if (!valid || parent == root) break;
        window = parent;
    }
    XCloseDisplay(display);
    return found;
}
