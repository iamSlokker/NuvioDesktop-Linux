// StatusNotifierItem and com.canonical.dbusmenu for the desktops AWT's XEmbed tray cannot reach:
// GNOME with the AppIndicator extension, KDE Plasma on Wayland, and the others with a watcher.
#include "status_notifier.h"
#include "dbus_connection.h"
#include <gio/gio.h>
#include <unistd.h>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

namespace {
constexpr auto watcherName = "org.kde.StatusNotifierWatcher";
constexpr auto watcherPath = "/StatusNotifierWatcher";
constexpr auto itemPath = "/StatusNotifierItem";
constexpr auto menuPath = "/StatusNotifierMenu";
constexpr auto itemInterface = "org.kde.StatusNotifierItem";
constexpr auto xml = R"xml(<node>
<interface name="org.kde.StatusNotifierItem">
 <method name="Activate"><arg type="i" direction="in"/><arg type="i" direction="in"/></method>
 <method name="SecondaryActivate"><arg type="i" direction="in"/><arg type="i" direction="in"/></method>
 <method name="ContextMenu"><arg type="i" direction="in"/><arg type="i" direction="in"/></method>
 <method name="Scroll"><arg type="i" direction="in"/><arg type="s" direction="in"/></method>
 <signal name="NewTitle"/><signal name="NewIcon"/><signal name="NewToolTip"/>
 <signal name="NewStatus"><arg type="s"/></signal>
 <property name="Category" type="s" access="read"/>
 <property name="Id" type="s" access="read"/>
 <property name="Title" type="s" access="read"/>
 <property name="Status" type="s" access="read"/>
 <property name="WindowId" type="i" access="read"/>
 <property name="IconThemePath" type="s" access="read"/>
 <property name="IconName" type="s" access="read"/>
 <property name="IconPixmap" type="a(iiay)" access="read"/>
 <property name="OverlayIconName" type="s" access="read"/>
 <property name="OverlayIconPixmap" type="a(iiay)" access="read"/>
 <property name="AttentionIconName" type="s" access="read"/>
 <property name="AttentionIconPixmap" type="a(iiay)" access="read"/>
 <property name="AttentionMovieName" type="s" access="read"/>
 <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
 <property name="ItemIsMenu" type="b" access="read"/>
 <property name="Menu" type="o" access="read"/>
</interface>
<interface name="com.canonical.dbusmenu">
 <method name="GetLayout">
  <arg type="i" direction="in"/><arg type="i" direction="in"/><arg type="as" direction="in"/>
  <arg type="u" direction="out"/><arg type="(ia{sv}av)" direction="out"/>
 </method>
 <method name="GetGroupProperties">
  <arg type="ai" direction="in"/><arg type="as" direction="in"/><arg type="a(ia{sv})" direction="out"/>
 </method>
 <method name="GetProperty">
  <arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/>
 </method>
 <method name="Event">
  <arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="in"/><arg type="u" direction="in"/>
 </method>
 <method name="EventGroup"><arg type="a(isvu)" direction="in"/><arg type="ai" direction="out"/></method>
 <method name="AboutToShow"><arg type="i" direction="in"/><arg type="b" direction="out"/></method>
 <method name="AboutToShowGroup">
  <arg type="ai" direction="in"/><arg type="ai" direction="out"/><arg type="ai" direction="out"/>
 </method>
 <signal name="ItemsPropertiesUpdated"><arg type="a(ia{sv})"/><arg type="a(ias)"/></signal>
 <signal name="LayoutUpdated"><arg type="u"/><arg type="i"/></signal>
 <property name="Version" type="u" access="read"/>
 <property name="TextDirection" type="s" access="read"/>
 <property name="Status" type="s" access="read"/>
 <property name="IconThemePath" type="as" access="read"/>
</interface></node>)xml";

// The menu is fixed: Open / version / Exit, the same entries the XEmbed tray draws itself.
enum MenuId { RootId = 0, OpenId = 1, FirstSeparatorId = 2, InfoId = 3, SecondSeparatorId = 4, ExitId = 5 };
constexpr int menuIds[] = {OpenId, FirstSeparatorId, InfoId, SecondSeparatorId, ExitId};

bool watcherHasHost(GDBusConnection *bus) {
    GError *error = nullptr;
    auto *owner = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
        "org.freedesktop.DBus", "NameHasOwner", g_variant_new("(s)", watcherName), G_VARIANT_TYPE("(b)"),
        G_DBUS_CALL_FLAGS_NONE, 500, nullptr, &error);
    g_clear_error(&error);
    if (!owner) return false;
    gboolean present = false;
    g_variant_get(owner, "(b)", &present);
    g_variant_unref(owner);
    if (!present) return false;
    // A watcher with no host would accept the icon and show it nowhere, and the window would be lost.
    auto *host = g_dbus_connection_call_sync(bus, watcherName, watcherPath, "org.freedesktop.DBus.Properties",
        "Get", g_variant_new("(ss)", watcherName, "IsStatusNotifierHostRegistered"), G_VARIANT_TYPE("(v)"),
        G_DBUS_CALL_FLAGS_NO_AUTO_START, 500, nullptr, &error);
    g_clear_error(&error);
    if (!host) return false;
    GVariant *value = nullptr;
    g_variant_get(host, "(v)", &value);
    const bool registered = g_variant_is_of_type(value, G_VARIANT_TYPE_BOOLEAN) && g_variant_get_boolean(value);
    g_variant_unref(value);
    g_variant_unref(host);
    return registered;
}

GVariant *emptyPixmaps() { return g_variant_new_array(G_VARIANT_TYPE("(iiay)"), nullptr, 0); }
}

namespace nuvio::tray {

class Item {
public:
    Config config;
    GMainContext *context = g_main_context_new();
    GMainLoop *loop = g_main_loop_new(context, false);
    GDBusConnection *bus = nullptr;
    GDBusNodeInfo *info = nullptr;
    guint itemRegistration = 0, menuRegistration = 0, watch = 0;
    std::string name;
    std::thread worker;
    std::mutex mutex;
    std::condition_variable wake;
    std::deque<int> events;
    bool hosted = true, seenWatcher = false;

    void post(int event) {
        { std::lock_guard<std::mutex> lock(mutex); events.push_back(event); }
        wake.notify_all();
    }

    bool registerWithWatcher() {
        GError *error = nullptr;
        auto *reply = g_dbus_connection_call_sync(bus, watcherName, watcherPath, watcherName,
            "RegisterStatusNotifierItem", g_variant_new("(s)", name.c_str()), nullptr,
            G_DBUS_CALL_FLAGS_NO_AUTO_START, 1000, nullptr, &error);
        g_clear_error(&error);
        if (reply) g_variant_unref(reply);
        return reply != nullptr;
    }

    GVariant *pixmaps() const {
        GVariantBuilder builder;
        g_variant_builder_init(&builder, G_VARIANT_TYPE("a(iiay)"));
        for (const auto &icon : config.icons) {
            auto *bytes = g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, icon.argb.data(), icon.argb.size(), 1);
            g_variant_builder_add(&builder, "(ii@ay)", icon.width, icon.height, bytes);
        }
        return g_variant_builder_end(&builder);
    }

    GVariant *itemProperty(const std::string &n) const {
        if (n == "Category") return g_variant_new_string("ApplicationStatus");
        if (n == "Id") return g_variant_new_string("nuvio");
        if (n == "Title") return g_variant_new_string(config.title.c_str());
        if (n == "Status") return g_variant_new_string("Active");
        if (n == "WindowId") return g_variant_new_int32(0);
        if (n == "ItemIsMenu") return g_variant_new_boolean(false);
        if (n == "Menu") return g_variant_new_object_path(menuPath);
        if (n == "IconPixmap") return pixmaps();
        if (n == "OverlayIconPixmap" || n == "AttentionIconPixmap") return emptyPixmaps();
        if (n == "ToolTip") return g_variant_new("(s@a(iiay)ss)", "", emptyPixmaps(), config.title.c_str(), "");
        return g_variant_new_string(""); // The icon names and paths: the pixmap needs no theme.
    }

    GVariant *menuProperty(const std::string &n) const {
        if (n == "Version") return g_variant_new_uint32(3);
        if (n == "TextDirection") return g_variant_new_string("ltr");
        if (n == "Status") return g_variant_new_string("normal");
        return g_variant_new_strv(nullptr, 0); // IconThemePath
    }

    GVariant *itemProperties(int id) const {
        GVariantBuilder builder;
        g_variant_builder_init(&builder, G_VARIANT_TYPE_VARDICT);
        const auto add = [&](const char *key, GVariant *value) { g_variant_builder_add(&builder, "{sv}", key, value); };
        switch (id) {
            case RootId: add("children-display", g_variant_new_string("submenu")); break;
            case OpenId: add("label", g_variant_new_string(config.openLabel.c_str())); break;
            case InfoId:
                add("label", g_variant_new_string(config.infoLabel.c_str()));
                add("enabled", g_variant_new_boolean(false));
                break;
            case ExitId: add("label", g_variant_new_string(config.exitLabel.c_str())); break;
            case FirstSeparatorId: case SecondSeparatorId: add("type", g_variant_new_string("separator")); break;
            default: break;
        }
        return g_variant_builder_end(&builder);
    }

    static bool knownMenuId(int id) {
        return id == RootId || id == OpenId || id == FirstSeparatorId || id == InfoId || id == SecondSeparatorId || id == ExitId;
    }

    GVariant *layout(int id, int depth) const {
        GVariantBuilder children;
        g_variant_builder_init(&children, G_VARIANT_TYPE("av"));
        if (id == RootId && depth != 0) {
            for (const int child : menuIds) g_variant_builder_add(&children, "v", layout(child, 0));
        }
        return g_variant_new("(i@a{sv}@av)", id, itemProperties(id), g_variant_builder_end(&children));
    }

    void menuEvent(int id, const char *event) {
        if (std::string(event) != "clicked") return;
        if (id == OpenId) post(Open);
        else if (id == ExitId) post(Exit);
    }

    void call(const std::string &iface, const std::string &method, GVariant *args, GDBusMethodInvocation *invocation) {
        if (iface == itemInterface) {
            if (method == "Activate" || method == "SecondaryActivate") post(Open);
            // Hosts that call ContextMenu have read Menu and show it themselves. Scroll means nothing here.
            g_dbus_method_invocation_return_value(invocation, nullptr);
        } else if (method == "GetLayout") {
            gint32 parent = 0, depth = 0;
            g_variant_get_child(args, 0, "i", &parent);
            g_variant_get_child(args, 1, "i", &depth);
            if (!knownMenuId(parent)) {
                g_dbus_method_invocation_return_dbus_error(invocation, "com.canonical.dbusmenu.Error", "Unknown menu item");
                return;
            }
            g_dbus_method_invocation_return_value(invocation, g_variant_new("(u@(ia{sv}av))", 1u, layout(parent, depth)));
        } else if (method == "GetGroupProperties") {
            GVariantBuilder builder;
            g_variant_builder_init(&builder, G_VARIANT_TYPE("a(ia{sv})"));
            GVariant *ids = g_variant_get_child_value(args, 0);
            GVariantIter iter;
            g_variant_iter_init(&iter, ids);
            gint32 id = 0;
            while (g_variant_iter_next(&iter, "i", &id)) {
                if (knownMenuId(id)) g_variant_builder_add(&builder, "(i@a{sv})", id, itemProperties(id));
            }
            g_variant_unref(ids);
            g_dbus_method_invocation_return_value(invocation, g_variant_new("(@a(ia{sv}))", g_variant_builder_end(&builder)));
        } else if (method == "GetProperty") {
            gint32 id = 0;
            const char *name = nullptr;
            g_variant_get(args, "(i&s)", &id, &name);
            auto *values = g_variant_ref_sink(itemProperties(knownMenuId(id) ? id : RootId));
            auto *value = g_variant_lookup_value(values, name, nullptr);
            g_variant_unref(values);
            if (!value) {
                g_dbus_method_invocation_return_dbus_error(invocation, "com.canonical.dbusmenu.Error", "Unknown property");
                return;
            }
            g_dbus_method_invocation_return_value(invocation, g_variant_new("(v)", value));
            g_variant_unref(value);
        } else if (method == "Event") {
            gint32 id = 0;
            const char *event = nullptr;
            GVariant *data = nullptr;
            guint32 timestamp = 0;
            g_variant_get(args, "(i&svu)", &id, &event, &data, &timestamp);
            g_variant_unref(data);
            g_dbus_method_invocation_return_value(invocation, nullptr);
            menuEvent(id, event);
        } else if (method == "EventGroup") {
            g_dbus_method_invocation_return_value(invocation, g_variant_new("(@ai)", g_variant_new_array(G_VARIANT_TYPE_INT32, nullptr, 0)));
            GVariant *list = g_variant_get_child_value(args, 0);
            GVariantIter iter;
            g_variant_iter_init(&iter, list);
            gint32 id = 0;
            const char *event = nullptr;
            GVariant *data = nullptr;
            guint32 timestamp = 0;
            while (g_variant_iter_next(&iter, "(i&svu)", &id, &event, &data, &timestamp)) {
                menuEvent(id, event);
                g_variant_unref(data);
            }
            g_variant_unref(list);
        } else if (method == "AboutToShow") {
            g_dbus_method_invocation_return_value(invocation, g_variant_new("(b)", FALSE));
        } else { // AboutToShowGroup
            g_dbus_method_invocation_return_value(invocation, g_variant_new("(@ai@ai)",
                g_variant_new_array(G_VARIANT_TYPE_INT32, nullptr, 0), g_variant_new_array(G_VARIANT_TYPE_INT32, nullptr, 0)));
        }
    }

    ~Item() {
        if (worker.joinable()) {
            auto *stop = g_idle_source_new();
            g_source_set_callback(stop, +[](gpointer p) -> gboolean {
                g_main_loop_quit(static_cast<Item *>(p)->loop);
                return G_SOURCE_REMOVE;
            }, this, nullptr);
            g_source_attach(stop, context);
            g_source_unref(stop);
            worker.join();
        }
        if (watch) g_bus_unwatch_name(watch);
        if (bus) {
            if (itemRegistration) g_dbus_connection_unregister_object(bus, itemRegistration);
            if (menuRegistration) g_dbus_connection_unregister_object(bus, menuRegistration);
            g_dbus_connection_flush_sync(bus, nullptr, nullptr); // The reply to the menu's Exit click.
            g_dbus_connection_close_sync(bus, nullptr, nullptr); // Releases the name; the watcher drops the icon.
            g_object_unref(bus);
        }
        if (info) g_dbus_node_info_unref(info);
        g_main_loop_unref(loop);
        g_main_context_unref(context);
    }
};

static const GDBusInterfaceVTable vtable = {
    +[](GDBusConnection *, const gchar *, const gchar *, const gchar *iface, const gchar *method,
        GVariant *args, GDBusMethodInvocation *invocation, gpointer data) {
        static_cast<Item *>(data)->call(iface, method, args, invocation);
    },
    +[](GDBusConnection *, const gchar *, const gchar *, const gchar *iface, const gchar *name,
        GError **, gpointer data) -> GVariant * {
        auto *item = static_cast<Item *>(data);
        return std::string(iface) == itemInterface ? item->itemProperty(name) : item->menuProperty(name);
    },
    nullptr, {}
};

bool available() {
    if (!g_getenv("DBUS_SESSION_BUS_ADDRESS")) return false; // Never auto-launch a bus.
    GError *error = nullptr;
    auto *bus = nuvio::dbus::connect(G_BUS_TYPE_SESSION, 1500, &error);
    g_clear_error(&error);
    if (!bus) return false;
    const bool result = watcherHasHost(bus);
    g_dbus_connection_close_sync(bus, nullptr, nullptr);
    g_object_unref(bus);
    return result;
}

Item *open(const Config &config) {
    if (!g_getenv("DBUS_SESSION_BUS_ADDRESS")) return nullptr;
    auto *item = new Item();
    item->config = config;
    GError *error = nullptr;
    g_main_context_push_thread_default(item->context);
    bool ok = false;
    item->bus = nuvio::dbus::connect(G_BUS_TYPE_SESSION, 1500, &error);
    if (item->bus && watcherHasHost(item->bus)) {
        item->info = g_dbus_node_info_new_for_xml(xml, &error);
        if (item->info) {
            item->itemRegistration = g_dbus_connection_register_object(item->bus, itemPath, item->info->interfaces[0], &vtable, item, nullptr, &error);
            if (!error) item->menuRegistration = g_dbus_connection_register_object(item->bus, menuPath, item->info->interfaces[1], &vtable, item, nullptr, &error);
        }
        if (!error) {
            item->name = "org.kde.StatusNotifierItem-" + std::to_string(getpid()) + "-1";
            auto *reply = g_dbus_connection_call_sync(item->bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus", "RequestName", g_variant_new("(su)", item->name.c_str(), 4u),
                G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, 1000, nullptr, &error);
            guint result = 0;
            if (reply) { g_variant_get(reply, "(u)", &result); g_variant_unref(reply); }
            ok = result == 1 && item->registerWithWatcher();
        }
    }
    if (ok) {
        // A shell restart drops the watcher: the window must come back rather than stay hidden
        // behind an icon nobody draws, and the icon has to register again when the shell returns.
        item->watch = g_bus_watch_name_on_connection(item->bus, watcherName, G_BUS_NAME_WATCHER_FLAGS_NONE,
            +[](GDBusConnection *, const gchar *, const gchar *, gpointer data) {
                auto *self = static_cast<Item *>(data);
                const bool returning = self->seenWatcher && !self->hosted;
                self->seenWatcher = true;
                if (returning && self->registerWithWatcher()) {
                    self->hosted = true;
                    self->post(HostBack);
                }
            },
            +[](GDBusConnection *, const gchar *, gpointer data) {
                auto *self = static_cast<Item *>(data);
                self->seenWatcher = true;
                if (self->hosted) {
                    self->hosted = false;
                    self->post(HostLost);
                }
            }, item, nullptr);
    }
    g_main_context_pop_thread_default(item->context);
    g_clear_error(&error);
    if (!ok) {
        delete item;
        return nullptr;
    }
    item->worker = std::thread([item] {
        g_main_context_push_thread_default(item->context);
        g_main_loop_run(item->loop);
        g_main_context_pop_thread_default(item->context);
    });
    return item;
}

int next(Item *item, int timeoutMs) {
    std::unique_lock<std::mutex> lock(item->mutex);
    item->wake.wait_for(lock, std::chrono::milliseconds(timeoutMs), [item] { return !item->events.empty(); });
    if (item->events.empty()) return None;
    const int event = item->events.front();
    item->events.pop_front();
    return event;
}

void close(Item *item) { delete item; }

}
