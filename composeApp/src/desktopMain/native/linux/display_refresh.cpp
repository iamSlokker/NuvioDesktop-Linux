#include "display_refresh.h"
#include "dbus_connection.h"

#include <X11/Xlib.h>
#include <X11/extensions/Xrandr.h>
#include <dlfcn.h>
#include <gio/gio.h>
#include <glib.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <mutex>
#include <sstream>
#include <type_traits>

namespace {
using nuvio::refresh::Mode;

constexpr const char *mutterName = "org.gnome.Mutter.DisplayConfig";
constexpr const char *mutterPath = "/org/gnome/Mutter/DisplayConfig";
constexpr int callTimeoutMs = 3000;
// ApplyMonitorsConfig methods: 0 only checks the configuration, 1 applies it without saving it.
constexpr guint32 mutterVerify = 0;
constexpr guint32 mutterTemporary = 1;

void log(const std::string &line) { std::fprintf(stderr, "Linux display refresh: %s\n", line.c_str()); }

std::string takeMessage(GError *error) {
    std::string message = error && error->message ? error->message : "unknown error";
    if (error) g_error_free(error);
    return message;
}

// What a switch changed, so it can be put back: by this process, or by a run that ended without
// restoring it (a crash). Kept on disk with the boot it belongs to.
struct Record {
    std::string backend; // "mutter" or "xrandr"
    std::string target;  // Mutter connector or XRandR output name
    std::string original;
    std::string switched;
    std::string boot;
};

std::string bootId() {
    std::ifstream in("/proc/sys/kernel/random/boot_id");
    std::string id;
    std::getline(in, id);
    return id;
}

std::string recordPath() { return std::string(g_get_user_cache_dir()) + "/nuviohtpc-display-refresh"; }

void saveRecord(const Record &record) {
    g_mkdir_with_parents(g_get_user_cache_dir(), 0700);
    std::ofstream out(recordPath(), std::ios::trunc);
    out << record.backend << '\n' << record.target << '\n' << record.original << '\n'
        << record.switched << '\n' << record.boot << '\n';
}

std::optional<Record> loadRecord() {
    std::ifstream in(recordPath());
    Record record;
    if (!std::getline(in, record.backend) || !std::getline(in, record.target) ||
        !std::getline(in, record.original) || !std::getline(in, record.switched) || !std::getline(in, record.boot))
        return std::nullopt;
    return record;
}

void dropRecord() { std::remove(recordPath().c_str()); }

// The centre of [window] in root coordinates (Xwayland's root is Mutter's logical layout).
std::optional<std::pair<int, int>> windowCentre(unsigned long window) {
    if (!window) return std::nullopt;
    Display *display = XOpenDisplay(nullptr);
    if (!display) return std::nullopt;
    std::optional<std::pair<int, int>> centre;
    XWindowAttributes attributes{};
    int x = 0, y = 0;
    Window child = None;
    if (XGetWindowAttributes(display, window, &attributes) &&
        XTranslateCoordinates(display, window, DefaultRootWindow(display), 0, 0, &x, &y, &child))
        centre = std::make_pair(x + attributes.width / 2, y + attributes.height / 2);
    XCloseDisplay(display);
    return centre;
}

// ---- Mutter (GNOME) ----

struct MutterMonitor {
    std::string connector;
    Mode current;
    std::vector<Mode> modes;
    std::optional<guint32> colorMode, rgbRange;
    std::optional<gboolean> underscanning;
};

struct MutterLogical {
    gint32 x = 0, y = 0;
    double scale = 1.0;
    guint32 transform = 0;
    gboolean primary = false;
    std::vector<std::string> connectors;
};

struct MutterState {
    guint32 serial = 0;
    std::vector<MutterMonitor> monitors;
    std::vector<MutterLogical> logical;
    std::optional<guint32> layoutMode; // Passed back only where it may be changed.

    const MutterMonitor *monitor(const std::string &connector) const {
        for (const auto &monitor : monitors) if (monitor.connector == connector) return &monitor;
        return nullptr;
    }
};

struct Bus {
    GDBusConnection *connection = nullptr;
    Bus() {
        GError *error = nullptr;
        connection = nuvio::dbus::connect(G_BUS_TYPE_SESSION, callTimeoutMs, &error);
        if (!connection) takeMessage(error);
    }
    ~Bus() {
        if (!connection) return;
        g_dbus_connection_close_sync(connection, nullptr, nullptr);
        g_object_unref(connection);
    }
};

std::optional<MutterState> mutterState(Bus &bus, bool quiet = false) {
    if (!bus.connection) return std::nullopt;
    GError *error = nullptr;
    GVariant *reply = g_dbus_connection_call_sync(bus.connection, mutterName, mutterPath, mutterName,
        "GetCurrentState", nullptr,
        G_VARIANT_TYPE("(ua((ssss)a(siiddada{sv})a{sv})a(iiduba(ssss)a{sv})a{sv})"),
        G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, &error);
    if (!reply) {
        // No Mutter (another desktop), or a sandbox that may not reach it.
        const std::string message = takeMessage(error);
        if (!quiet) log("Mutter display config unavailable: " + message);
        return std::nullopt;
    }
    MutterState state;
    GVariant *monitors = nullptr, *logical = nullptr, *properties = nullptr;
    g_variant_get(reply, "(u@a((ssss)a(siiddada{sv})a{sv})@a(iiduba(ssss)a{sv})@a{sv})",
                  &state.serial, &monitors, &logical, &properties);
    GVariantIter monitorIter;
    g_variant_iter_init(&monitorIter, monitors);
    GVariant *monitor = nullptr;
    while ((monitor = g_variant_iter_next_value(&monitorIter))) {
        MutterMonitor entry;
        GVariant *spec = nullptr, *modes = nullptr, *monitorProps = nullptr;
        g_variant_get(monitor, "(@(ssss)@a(siiddada{sv})@a{sv})", &spec, &modes, &monitorProps);
        const char *connector = nullptr;
        g_variant_get(spec, "(&s&s&s&s)", &connector, nullptr, nullptr, nullptr);
        entry.connector = connector;
        GVariantIter modeIter;
        g_variant_iter_init(&modeIter, modes);
        GVariant *mode = nullptr;
        bool haveCurrent = false;
        while ((mode = g_variant_iter_next_value(&modeIter))) {
            const char *id = nullptr;
            gint32 width = 0, height = 0;
            double hz = 0.0, preferredScale = 0.0;
            GVariant *scales = nullptr, *modeProps = nullptr;
            g_variant_get(mode, "(&siidd@ad@a{sv})", &id, &width, &height, &hz, &preferredScale, &scales, &modeProps);
            Mode parsed;
            parsed.id = id;
            parsed.width = width;
            parsed.height = height;
            parsed.hz = hz;
            const char *rateMode = nullptr;
            parsed.variable = g_variant_lookup(modeProps, "refresh-rate-mode", "&s", &rateMode) &&
                              std::string(rateMode) == "variable";
            gboolean flag = false;
            parsed.interlaced = g_variant_lookup(modeProps, "is-interlaced", "b", &flag) && flag;
            flag = false;
            if (g_variant_lookup(modeProps, "is-current", "b", &flag) && flag) {
                entry.current = parsed;
                haveCurrent = true;
            }
            entry.modes.push_back(parsed);
            g_variant_unref(scales);
            g_variant_unref(modeProps);
            g_variant_unref(mode);
        }
        guint32 value = 0;
        if (g_variant_lookup(monitorProps, "color-mode", "u", &value)) entry.colorMode = value;
        if (g_variant_lookup(monitorProps, "rgb-range", "u", &value)) entry.rgbRange = value;
        gboolean underscanning = false;
        if (g_variant_lookup(monitorProps, "is-underscanning", "b", &underscanning)) entry.underscanning = underscanning;
        if (haveCurrent) state.monitors.push_back(std::move(entry));
        g_variant_unref(spec);
        g_variant_unref(modes);
        g_variant_unref(monitorProps);
        g_variant_unref(monitor);
    }
    GVariantIter logicalIter;
    g_variant_iter_init(&logicalIter, logical);
    GVariant *item = nullptr;
    while ((item = g_variant_iter_next_value(&logicalIter))) {
        MutterLogical entry;
        GVariant *specs = nullptr, *logicalProps = nullptr;
        g_variant_get(item, "(iidub@a(ssss)@a{sv})", &entry.x, &entry.y, &entry.scale, &entry.transform,
                      &entry.primary, &specs, &logicalProps);
        GVariantIter specIter;
        g_variant_iter_init(&specIter, specs);
        const char *connector = nullptr;
        while (g_variant_iter_next(&specIter, "(&s&s&s&s)", &connector, nullptr, nullptr, nullptr))
            entry.connectors.emplace_back(connector);
        state.logical.push_back(std::move(entry));
        g_variant_unref(specs);
        g_variant_unref(logicalProps);
        g_variant_unref(item);
    }
    gboolean changeable = false;
    guint32 layout = 0;
    if (g_variant_lookup(properties, "supports-changing-layout-mode", "b", &changeable) && changeable &&
        g_variant_lookup(properties, "layout-mode", "u", &layout))
        state.layoutMode = layout;
    g_variant_unref(monitors);
    g_variant_unref(logical);
    g_variant_unref(properties);
    g_variant_unref(reply);
    return state;
}

// The connector of the logical monitor under the window's centre, else the primary one.
std::optional<std::string> mutterTarget(const MutterState &state, unsigned long window) {
    const auto centre = windowCentre(window);
    const MutterLogical *chosen = nullptr;
    for (const auto &logical : state.logical) {
        if (logical.connectors.empty()) continue;
        const auto *monitor = state.monitor(logical.connectors.front());
        if (!monitor) continue;
        double width = monitor->current.width, height = monitor->current.height;
        if (logical.transform % 2 == 1) std::swap(width, height); // 90 and 270 degrees, flipped or not
        // Layout mode 1 is logical: monitors are laid out at their size divided by their scale.
        if (state.layoutMode.value_or(1) == 1 && logical.scale > 0.0) { width /= logical.scale; height /= logical.scale; }
        if (centre && centre->first >= logical.x && centre->first < logical.x + width &&
            centre->second >= logical.y && centre->second < logical.y + height) {
            chosen = &logical;
            break;
        }
        if (logical.primary && !chosen) chosen = &logical;
    }
    if (!chosen) return std::nullopt;
    return chosen->connectors.front();
}

// The current configuration with [connector] on [modeId], applied with [method].
bool mutterApply(Bus &bus, const MutterState &state, const std::string &connector, const std::string &modeId,
                 guint32 method, std::string &failure) {
    GVariantBuilder logical;
    g_variant_builder_init(&logical, G_VARIANT_TYPE("a(iiduba(ssa{sv}))"));
    for (const auto &entry : state.logical) {
        g_variant_builder_open(&logical, G_VARIANT_TYPE("(iiduba(ssa{sv}))"));
        g_variant_builder_add(&logical, "i", entry.x);
        g_variant_builder_add(&logical, "i", entry.y);
        g_variant_builder_add(&logical, "d", entry.scale);
        g_variant_builder_add(&logical, "u", entry.transform);
        g_variant_builder_add(&logical, "b", entry.primary);
        g_variant_builder_open(&logical, G_VARIANT_TYPE("a(ssa{sv})"));
        for (const auto &name : entry.connectors) {
            const auto *monitor = state.monitor(name);
            if (!monitor) continue;
            g_variant_builder_open(&logical, G_VARIANT_TYPE("(ssa{sv})"));
            g_variant_builder_add(&logical, "s", name.c_str());
            g_variant_builder_add(&logical, "s", (name == connector ? modeId : monitor->current.id).c_str());
            // Carried over, so a switch never turns off HDR, a limited RGB range or underscanning.
            g_variant_builder_open(&logical, G_VARIANT_TYPE("a{sv}"));
            if (monitor->colorMode) g_variant_builder_add(&logical, "{sv}", "color-mode", g_variant_new_uint32(*monitor->colorMode));
            if (monitor->rgbRange) g_variant_builder_add(&logical, "{sv}", "rgb-range", g_variant_new_uint32(*monitor->rgbRange));
            if (monitor->underscanning) g_variant_builder_add(&logical, "{sv}", "underscanning", g_variant_new_boolean(*monitor->underscanning));
            g_variant_builder_close(&logical);
            g_variant_builder_close(&logical);
        }
        g_variant_builder_close(&logical);
        g_variant_builder_close(&logical);
    }
    GVariantBuilder properties;
    g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
    if (state.layoutMode) g_variant_builder_add(&properties, "{sv}", "layout-mode", g_variant_new_uint32(*state.layoutMode));
    GError *error = nullptr;
    GVariant *reply = g_dbus_connection_call_sync(bus.connection, mutterName, mutterPath, mutterName,
        "ApplyMonitorsConfig", g_variant_new("(uua(iiduba(ssa{sv}))a{sv})", state.serial, method, &logical, &properties),
        nullptr, G_DBUS_CALL_FLAGS_NONE, callTimeoutMs, nullptr, &error);
    if (!reply) {
        failure = takeMessage(error);
        return false;
    }
    g_variant_unref(reply);
    return true;
}

// ---- XRandR (a real X11 session) ----

struct Randr {
    void *library = nullptr;
    decltype(&XRRQueryExtension) queryExtension = nullptr;
    decltype(&XRRGetScreenResourcesCurrent) resources = nullptr;
    decltype(&XRRFreeScreenResources) freeResources = nullptr;
    decltype(&XRRGetCrtcInfo) crtcInfo = nullptr;
    decltype(&XRRFreeCrtcInfo) freeCrtcInfo = nullptr;
    decltype(&XRRGetOutputInfo) outputInfo = nullptr;
    decltype(&XRRFreeOutputInfo) freeOutputInfo = nullptr;
    decltype(&XRRSetCrtcConfig) setCrtcConfig = nullptr;

    // Loaded on first use: the packages depend on libXrandr but do not link it.
    static Randr *get() {
        static Randr randr = [] {
            Randr loaded;
            loaded.library = dlopen("libXrandr.so.2", RTLD_NOW | RTLD_LOCAL);
            if (!loaded.library) return loaded;
            auto bind = [&](auto &slot, const char *name) {
                slot = reinterpret_cast<std::remove_reference_t<decltype(slot)>>(dlsym(loaded.library, name));
                return slot != nullptr;
            };
            const bool ok = bind(loaded.queryExtension, "XRRQueryExtension") &&
                bind(loaded.resources, "XRRGetScreenResourcesCurrent") && bind(loaded.freeResources, "XRRFreeScreenResources") &&
                bind(loaded.crtcInfo, "XRRGetCrtcInfo") && bind(loaded.freeCrtcInfo, "XRRFreeCrtcInfo") &&
                bind(loaded.outputInfo, "XRRGetOutputInfo") && bind(loaded.freeOutputInfo, "XRRFreeOutputInfo") &&
                bind(loaded.setCrtcConfig, "XRRSetCrtcConfig");
            if (!ok) { dlclose(loaded.library); loaded = Randr{}; }
            return loaded;
        }();
        return randr.library ? &randr : nullptr;
    }
};

double modeHz(const XRRModeInfo &mode) {
    if (!mode.hTotal || !mode.vTotal) return 0.0;
    double lines = mode.vTotal;
    if (mode.modeFlags & RR_DoubleScan) lines *= 2;
    if (mode.modeFlags & RR_Interlace) lines /= 2;
    return static_cast<double>(mode.dotClock) / (static_cast<double>(mode.hTotal) * lines);
}

// One X connection and its screen resources, for a session XRandR may change.
struct RandrSession {
    Randr *randr = Randr::get();
    Display *display = nullptr;
    XRRScreenResources *resources = nullptr;

    RandrSession() {
        if (!randr) return;
        display = XOpenDisplay(nullptr);
        if (!display) return;
        int opcode = 0, event = 0, error = 0, base = 0, errorBase = 0;
        // Xwayland's RandR only emulates modes: its refresh rate is the compositor's to change.
        if (XQueryExtension(display, "XWAYLAND", &opcode, &event, &error) ||
            !randr->queryExtension(display, &base, &errorBase)) {
            XCloseDisplay(display);
            display = nullptr;
            return;
        }
        resources = randr->resources(display, DefaultRootWindow(display));
    }
    ~RandrSession() {
        if (resources) randr->freeResources(resources);
        if (display) XCloseDisplay(display);
    }
    bool ready() const { return display && resources; }

    const XRRModeInfo *mode(RRMode id) const {
        for (int i = 0; i < resources->nmode; ++i) if (resources->modes[i].id == id) return &resources->modes[i];
        return nullptr;
    }
    Mode describe(const XRRModeInfo &info) const {
        Mode mode;
        mode.id = std::to_string(info.id);
        mode.width = static_cast<int>(info.width);
        mode.height = static_cast<int>(info.height);
        mode.hz = modeHz(info);
        mode.interlaced = (info.modeFlags & RR_Interlace) != 0;
        return mode;
    }

    struct Target {
        RRCrtc crtc = 0;
        RROutput output = 0;
        std::string outputName;
        Mode current;
        std::vector<Mode> modes;
    };

    // The active CRTC under [centre] (else the first one) and its first output's modes.
    std::optional<Target> target(std::optional<std::pair<int, int>> centre, const std::string &outputName = {}) const {
        std::optional<Target> first, found;
        for (int i = 0; i < resources->ncrtc && !found; ++i) {
            XRRCrtcInfo *crtc = randr->crtcInfo(display, resources, resources->crtcs[i]);
            if (!crtc) continue;
            const XRRModeInfo *current = crtc->mode ? mode(crtc->mode) : nullptr;
            if (current && crtc->noutput > 0) {
                XRROutputInfo *output = randr->outputInfo(display, resources, crtc->outputs[0]);
                if (output) {
                    Target entry;
                    entry.crtc = resources->crtcs[i];
                    entry.output = crtc->outputs[0];
                    entry.outputName.assign(output->name, output->nameLen);
                    entry.current = describe(*current);
                    for (int m = 0; m < output->nmode; ++m)
                        if (const auto *info = mode(output->modes[m])) entry.modes.push_back(describe(*info));
                    const bool under = centre && centre->first >= crtc->x && centre->first < crtc->x + static_cast<int>(crtc->width) &&
                                       centre->second >= crtc->y && centre->second < crtc->y + static_cast<int>(crtc->height);
                    if (!outputName.empty() ? entry.outputName == outputName : under) found = entry;
                    else if (!first && outputName.empty()) first = entry;
                    randr->freeOutputInfo(output);
                }
            }
            randr->freeCrtcInfo(crtc);
        }
        return found ? found : first;
    }

    // Puts [crtc] on [modeId], keeping its position, rotation and outputs.
    bool apply(RRCrtc crtcId, const std::string &modeId) const {
        XRRCrtcInfo *crtc = randr->crtcInfo(display, resources, crtcId);
        if (!crtc) return false;
        const RRMode target = static_cast<RRMode>(std::strtoul(modeId.c_str(), nullptr, 10));
        const Status status = randr->setCrtcConfig(display, resources, crtcId, CurrentTime, crtc->x, crtc->y,
                                                   target, crtc->rotation, crtc->outputs, crtc->noutput);
        randr->freeCrtcInfo(crtc);
        XSync(display, False);
        return status == RRSetConfigSuccess;
    }
};

// ---- the one switch this process may hold ----

std::mutex switchLock;
std::optional<Record> switched; // Under switchLock.

// Puts [record] back when the monitor is still on the mode the switch chose (a mode the user
// picked since is left alone). True when nothing is left to restore.
bool restore(const Record &record) {
    std::string failure;
    if (record.backend == "mutter") {
        Bus bus;
        const auto state = mutterState(bus);
        const auto *monitor = state ? state->monitor(record.target) : nullptr;
        if (!monitor) return !state ? false : true;
        if (monitor->current.id != record.switched) return true;
        const bool ok = mutterApply(bus, *state, record.target, record.original, mutterTemporary, failure);
        log(std::string("restored ") + record.target + " to " + record.original + (ok ? "" : ": " + failure));
        return ok;
    }
    if (record.backend == "xrandr") {
        RandrSession session;
        if (!session.ready()) return false;
        const auto target = session.target(std::nullopt, record.target);
        if (!target || target->outputName != record.target) return true;
        if (target->current.id != record.switched) return true;
        const bool ok = session.apply(target->crtc, record.original);
        log(std::string("restored ") + record.target + " to mode " + record.original + (ok ? "" : ": FAILED"));
        return ok;
    }
    return true;
}

// A switch an earlier run of this boot left behind (it ended without restoring it).
void restoreLeftover() {
    if (switched) return;
    const auto record = loadRecord();
    if (!record) return;
    if (record->boot == bootId()) restore(*record);
    dropRecord();
}

bool restoreSwitched() {
    restoreLeftover();
    if (!switched) return true;
    const bool ok = restore(*switched);
    switched.reset();
    dropRecord();
    return ok;
}

void remember(const std::string &backend, const std::string &target, const std::string &original, const std::string &now) {
    // Switching again keeps the user's own mode as the one to go back to.
    if (switched && switched->backend == backend && switched->target == target) switched->switched = now;
    else switched = Record{backend, target, original, now, bootId()};
    saveRecord(*switched);
}

bool switchTo(unsigned long window, int hz);
}

namespace nuvio::refresh {

std::vector<int> rates(unsigned long window) {
    std::lock_guard<std::mutex> lock(switchLock);
    restoreLeftover();
    {
        Bus bus;
        if (const auto state = mutterState(bus, true)) {
            const auto connector = mutterTarget(*state, window);
            const auto *monitor = connector ? state->monitor(*connector) : nullptr;
            return monitor ? offeredRates(monitor->current, monitor->modes) : std::vector<int>{};
        }
    }
    RandrSession session;
    if (!session.ready()) return {};
    const auto target = session.target(windowCentre(window));
    return target ? offeredRates(target->current, target->modes) : std::vector<int>{};
}

#ifdef NUVIO_DISPLAY_REFRESH_TESTING
void forgetForTesting() {
    std::lock_guard<std::mutex> lock(switchLock);
    switched.reset();
}
#endif

bool set(unsigned long window, int hz) {
    std::lock_guard<std::mutex> lock(switchLock);
    if (hz <= 0) return restoreSwitched();
    restoreLeftover();
    return switchTo(window, hz);
}

}

namespace {
// Under switchLock.
bool switchTo(unsigned long window, int hz) {
    using nuvio::refresh::listedHz;
    using nuvio::refresh::modeFor;
    {
        Bus bus;
        if (const auto state = mutterState(bus, true)) {
            const auto connector = mutterTarget(*state, window);
            const auto *monitor = connector ? state->monitor(*connector) : nullptr;
            if (!monitor) return false;
            // Moving to another monitor: hand the first one back before switching this one.
            if (switched && (switched->backend != "mutter" || switched->target != *connector)) {
                restoreSwitched();
                return switchTo(window, hz);
            }
            const auto mode = modeFor(monitor->current, monitor->modes, hz);
            if (!mode) return false;
            if (mode->id == monitor->current.id) return true;
            std::string failure;
            if (!mutterApply(bus, *state, *connector, mode->id, mutterVerify, failure) ||
                !mutterApply(bus, *state, *connector, mode->id, mutterTemporary, failure)) {
                log(*connector + " -> " + mode->id + " refused: " + failure);
                return false;
            }
            log(*connector + " " + monitor->current.id + " -> " + mode->id);
            remember("mutter", *connector, monitor->current.id, mode->id);
            return true;
        }
    }
    RandrSession session;
    if (!session.ready()) return false;
    const auto target = session.target(windowCentre(window));
    if (!target) return false;
    if (switched && (switched->backend != "xrandr" || switched->target != target->outputName)) {
        restoreSwitched();
        return switchTo(window, hz);
    }
    const auto mode = modeFor(target->current, target->modes, hz);
    if (!mode) return false;
    if (mode->id == target->current.id) return true;
    if (!session.apply(target->crtc, mode->id)) {
        log(target->outputName + " -> " + std::to_string(hz) + " Hz refused");
        return false;
    }
    log(target->outputName + " " + std::to_string(listedHz(target->current.hz)) + " -> " + std::to_string(hz) + " Hz");
    remember("xrandr", target->outputName, target->current.id, mode->id);
    return true;
}

}
