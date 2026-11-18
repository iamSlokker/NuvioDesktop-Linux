// Match display refresh rate on Linux (display_refresh.h): the Windows-style listing, and the
// Mutter path against a fake org.gnome.Mutter.DisplayConfig on a private session bus. Run under
// dbus-run-session; with no X server the window lookup falls back to the primary monitor.
#include "display_refresh.h"

#include <gio/gio.h>
#include <glib.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
using nuvio::refresh::Mode;

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

const char *introspection = R"xml(<node><interface name="org.gnome.Mutter.DisplayConfig">
  <method name="GetCurrentState"><arg name="serial" direction="out" type="u"/>
    <arg name="monitors" direction="out" type="a((ssss)a(siiddada{sv})a{sv})"/>
    <arg name="logical_monitors" direction="out" type="a(iiduba(ssss)a{sv})"/>
    <arg name="properties" direction="out" type="a{sv}"/></method>
  <method name="ApplyMonitorsConfig"><arg name="serial" direction="in" type="u"/>
    <arg name="method" direction="in" type="u"/>
    <arg name="logical_monitors" direction="in" type="a(iiduba(ssa{sv}))"/>
    <arg name="properties" direction="in" type="a{sv}"/></method>
</interface></node>)xml";

struct Applied {
    guint32 method;
    std::string connector, mode;
    guint32 colorMode = 99, layoutMode = 99;
};

// One built-in 1920x1080 panel: 144 and 60 Hz, each also as VRR; HDR on.
struct FakeMutter {
    std::mutex lock;
    std::string current = "1920x1080@144.002";
    guint32 serial = 7;
    std::vector<Applied> applied;

    GVariant *state() {
        std::lock_guard<std::mutex> guard(lock);
        GVariantBuilder modes;
        g_variant_builder_init(&modes, G_VARIANT_TYPE("a(siiddada{sv})"));
        struct Row { const char *id; double hz; bool vrr; };
        const Row rows[] = {{"1920x1080@144.002", 144.00242614746094, false}, {"1920x1080@144.002+vrr", 144.00242614746094, true},
                            {"1920x1080@60.001", 60.000663757324219, false}, {"1920x1080@60.001+vrr", 60.000663757324219, true},
                            {"1920x1080@59.940", 59.94, false}, {"1280x720@50.000", 50.0, false}};
        for (const auto &row : rows) {
            GVariantBuilder scales, props;
            g_variant_builder_init(&scales, G_VARIANT_TYPE("ad"));
            g_variant_builder_add(&scales, "d", 1.0);
            g_variant_builder_init(&props, G_VARIANT_TYPE("a{sv}"));
            if (current == row.id) g_variant_builder_add(&props, "{sv}", "is-current", g_variant_new_boolean(true));
            if (row.vrr) g_variant_builder_add(&props, "{sv}", "refresh-rate-mode", g_variant_new_string("variable"));
            const int width = std::string(row.id).rfind("1280", 0) == 0 ? 1280 : 1920;
            g_variant_builder_add(&modes, "(siidd@ad@a{sv})", row.id, width, width == 1280 ? 720 : 1080, row.hz, 1.0,
                                  g_variant_builder_end(&scales), g_variant_builder_end(&props));
        }
        GVariantBuilder monitorProps;
        g_variant_builder_init(&monitorProps, G_VARIANT_TYPE("a{sv}"));
        g_variant_builder_add(&monitorProps, "{sv}", "color-mode", g_variant_new_uint32(1));
        g_variant_builder_add(&monitorProps, "{sv}", "rgb-range", g_variant_new_uint32(1));
        GVariantBuilder monitors;
        g_variant_builder_init(&monitors, G_VARIANT_TYPE("a((ssss)a(siiddada{sv})a{sv})"));
        g_variant_builder_add(&monitors, "((ssss)@a(siiddada{sv})@a{sv})", "eDP-1", "BOE", "0x0c29", "0x1",
                              g_variant_builder_end(&modes), g_variant_builder_end(&monitorProps));
        GVariantBuilder specs, logicalProps, logical, props;
        g_variant_builder_init(&specs, G_VARIANT_TYPE("a(ssss)"));
        g_variant_builder_add(&specs, "(ssss)", "eDP-1", "BOE", "0x0c29", "0x1");
        g_variant_builder_init(&logicalProps, G_VARIANT_TYPE("a{sv}"));
        g_variant_builder_init(&logical, G_VARIANT_TYPE("a(iiduba(ssss)a{sv})"));
        g_variant_builder_add(&logical, "(iidub@a(ssss)@a{sv})", 0, 0, 1.0, 0u, true,
                              g_variant_builder_end(&specs), g_variant_builder_end(&logicalProps));
        g_variant_builder_init(&props, G_VARIANT_TYPE("a{sv}"));
        g_variant_builder_add(&props, "{sv}", "layout-mode", g_variant_new_uint32(1));
        g_variant_builder_add(&props, "{sv}", "supports-changing-layout-mode", g_variant_new_boolean(true));
        return g_variant_new("(u@a((ssss)a(siiddada{sv})a{sv})@a(iiduba(ssss)a{sv})@a{sv})", serial,
                             g_variant_builder_end(&monitors), g_variant_builder_end(&logical), g_variant_builder_end(&props));
    }

    void apply(GVariant *parameters, GDBusMethodInvocation *invocation) {
        guint32 callSerial = 0, method = 0;
        GVariant *logical = nullptr, *props = nullptr;
        g_variant_get(parameters, "(uu@a(iiduba(ssa{sv}))@a{sv})", &callSerial, &method, &logical, &props);
        Applied entry{method, "", ""};
        g_variant_lookup(props, "layout-mode", "u", &entry.layoutMode);
        GVariant *first = g_variant_get_child_value(logical, 0);
        GVariant *monitors = g_variant_get_child_value(first, 5);
        GVariant *monitor = g_variant_get_child_value(monitors, 0);
        const char *connector = nullptr, *mode = nullptr;
        GVariant *monitorProps = nullptr;
        g_variant_get(monitor, "(&s&s@a{sv})", &connector, &mode, &monitorProps);
        entry.connector = connector;
        entry.mode = mode;
        g_variant_lookup(monitorProps, "color-mode", "u", &entry.colorMode);
        bool stale = false;
        {
            std::lock_guard<std::mutex> guard(lock);
            stale = callSerial != serial;
            if (!stale) {
                applied.push_back(entry);
                if (method != 0) { current = entry.mode; ++serial; }
            }
        }
        for (GVariant *value : {monitorProps, monitor, monitors, first, logical, props}) g_variant_unref(value);
        if (stale) g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.DBus.Error.AccessDenied", "stale serial");
        else g_dbus_method_invocation_return_value(invocation, nullptr);
    }
} fake;

void onMethod(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *method,
              GVariant *parameters, GDBusMethodInvocation *invocation, gpointer) {
    if (std::string(method) == "GetCurrentState") g_dbus_method_invocation_return_value(invocation, fake.state());
    else fake.apply(parameters, invocation);
}

// The service on its own connection and main loop, owned before the tests start.
void serve(GMainLoop *&loop, std::thread &thread) {
    std::mutex ready;
    ready.lock();
    thread = std::thread([&] {
        GMainContext *context = g_main_context_new();
        g_main_context_push_thread_default(context);
        loop = g_main_loop_new(context, false);
        GDBusConnection *bus = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
        GDBusNodeInfo *node = g_dbus_node_info_new_for_xml(introspection, nullptr);
        static const GDBusInterfaceVTable vtable{onMethod, nullptr, nullptr, {}};
        g_dbus_connection_register_object(bus, "/org/gnome/Mutter/DisplayConfig", node->interfaces[0], &vtable,
                                          nullptr, nullptr, nullptr);
        GVariant *owned = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
            "org.freedesktop.DBus", "RequestName", g_variant_new("(su)", "org.gnome.Mutter.DisplayConfig", 4u),
            nullptr, G_DBUS_CALL_FLAGS_NONE, 3000, nullptr, nullptr);
        if (owned) g_variant_unref(owned);
        ready.unlock();
        g_main_loop_run(loop);
        g_dbus_node_info_unref(node);
        g_object_unref(bus);
        g_main_context_pop_thread_default(context);
        g_main_context_unref(context);
    });
    ready.lock();
    ready.unlock();
}

std::vector<Applied> takeApplied() {
    std::lock_guard<std::mutex> guard(fake.lock);
    auto taken = fake.applied;
    fake.applied.clear();
    return taken;
}

std::string currentMode() {
    std::lock_guard<std::mutex> guard(fake.lock);
    return fake.current;
}
}

int main() {
    try {
        using namespace nuvio::refresh;
        require(listedHz(144.00242614746094) == 144 && listedHz(60.000663757324219) == 60, "whole rates");
        require(listedHz(59.94) == 59 && listedHz(23.976) == 23 && listedHz(119.88) == 119, "NTSC rates listed one lower");
        require(listedHz(24.0) == 24 && listedHz(50.0) == 50 && listedHz(0.0) == 0, "24, 50 and unknown");
        const Mode current{"a", 1920, 1080, 144.0, false, false};
        const std::vector<Mode> modes{current, {"b", 1920, 1080, 60.0, false, false}, {"c", 1920, 1080, 60.0, true, false},
                                      {"d", 1920, 1080, 50.0, false, true}, {"e", 1280, 720, 24.0, false, false}};
        const auto rates = offeredRates(current, modes);
        require(rates == std::vector<int>({144, 144, 60}), "same shape, fixed, progressive only");
        require(modeFor(current, modes, 60)->id == "b", "60 Hz is the fixed mode");
        require(!modeFor(current, modes, 50) && !modeFor(current, modes, 24), "no interlaced or other size");
        std::cout << "PASS the rates are listed as on Windows\n";

        const std::string cache = g_dir_make_tmp("nuvio-refresh-XXXXXX", nullptr);
        g_setenv("XDG_CACHE_HOME", cache.c_str(), true);
        g_unsetenv("DISPLAY");
        GMainLoop *loop = nullptr;
        std::thread service;
        serve(loop, service);

        require(nuvio::refresh::rates(0) == std::vector<int>({144, 144, 60, 59}), "Mutter rates for the panel");
        require(set(0, 60), "switch to 60 Hz");
        auto calls = takeApplied();
        require(calls.size() == 2 && calls[0].method == 0 && calls[1].method == 1, "verified, then applied temporarily");
        require(calls[1].connector == "eDP-1" && calls[1].mode == "1920x1080@60.001", "the fixed 60 Hz mode");
        require(calls[1].colorMode == 1 && calls[1].layoutMode == 1, "HDR and the layout mode carried over");
        require(set(0, 60) && takeApplied().empty(), "already there: nothing sent");
        require(set(0, 0), "restore");
        calls = takeApplied();
        require(calls.size() == 1 && calls[0].method == 1 && calls[0].mode == "1920x1080@144.002", "back to 144 Hz");
        require(set(0, 0) && takeApplied().empty(), "nothing left to restore");
        std::cout << "PASS a switch is temporary, keeps HDR and is undone\n";

        // A run that ended without restoring: its record is put back by the next run's lookup.
        require(set(0, 60), "switch again");
        takeApplied();
        std::ifstream recorded(cache + "/nuviohtpc-display-refresh");
        std::string record((std::istreambuf_iterator<char>(recorded)), std::istreambuf_iterator<char>());
        require(record.find("mutter\neDP-1\n1920x1080@144.002\n1920x1080@60.001\n") == 0, "switch recorded on disk");
        forgetForTesting();
        require(nuvio::refresh::rates(0).size() == 4, "rates after the restart");
        calls = takeApplied();
        require(calls.size() == 1 && calls[0].mode == "1920x1080@144.002", "the next run puts it back");
        require(!g_file_test((cache + "/nuviohtpc-display-refresh").c_str(), G_FILE_TEST_EXISTS), "record dropped");
        require(set(0, 60), "switch once more");
        takeApplied();
        forgetForTesting();
        { std::lock_guard<std::mutex> guard(fake.lock); fake.current = "1920x1080@59.940"; ++fake.serial; }
        nuvio::refresh::rates(0);
        require(takeApplied().empty() && currentMode() == "1920x1080@59.940", "a mode picked since is left alone");
        std::cout << "PASS a switch left by an earlier run is undone, unless the mode changed since\n";

        g_main_loop_quit(loop);
        service.join();
        std::cout << "DISPLAY_REFRESH_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
