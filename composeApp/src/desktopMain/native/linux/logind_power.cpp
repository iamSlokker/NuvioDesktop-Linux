// logind's PowerOff, for the idle shutdown. Separate from the idle probe: this is the system bus.
#include <jni.h>
#include <gio/gio.h>
#include <cstring>
#include "dbus_connection.h"

namespace {
constexpr auto service = "org.freedesktop.login1";
constexpr auto path = "/org/freedesktop/login1";
constexpr auto manager = "org.freedesktop.login1.Manager";

GDBusConnection *systemBus() {
    GError *error = nullptr;
    auto *bus = nuvio::dbus::connect(G_BUS_TYPE_SYSTEM, 1500, &error);
    g_clear_error(&error);
    return bus;
}

void disconnect(GDBusConnection *bus) {
    g_dbus_connection_close_sync(bus, nullptr, nullptr);
    g_object_unref(bus);
}
}

// Only "yes": "challenge" would need a password prompt nobody is in the room to answer, and the
// call below is deliberately not interactive.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_screensaver_LinuxPowerNative_canPowerOff(JNIEnv *, jobject) {
    auto *bus = systemBus();
    if (!bus) return false;
    GError *error = nullptr;
    auto *reply = g_dbus_connection_call_sync(bus, service, path, manager, "CanPowerOff", nullptr,
        G_VARIANT_TYPE("(s)"), G_DBUS_CALL_FLAGS_NONE, 1000, nullptr, &error);
    g_clear_error(&error);
    bool result = false;
    if (reply) {
        const char *answer = nullptr;
        g_variant_get(reply, "(&s)", &answer);
        result = std::strcmp(answer, "yes") == 0;
        g_variant_unref(reply);
    }
    disconnect(bus);
    return result;
}

// False when logind refused, for instance because something holds a shutdown inhibitor.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_screensaver_LinuxPowerNative_powerOff(JNIEnv *, jobject) {
    auto *bus = systemBus();
    if (!bus) return false;
    GError *error = nullptr;
    auto *reply = g_dbus_connection_call_sync(bus, service, path, manager, "PowerOff", g_variant_new("(b)", FALSE),
        nullptr, G_DBUS_CALL_FLAGS_NONE, 5000, nullptr, &error);
    g_clear_error(&error);
    const bool result = reply != nullptr;
    if (reply) g_variant_unref(reply);
    disconnect(bus);
    return result;
}
