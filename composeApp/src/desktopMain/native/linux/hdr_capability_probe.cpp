#include "hdr_capability_probe.h"
#include "color-management-v1-client-protocol.h"

#define VK_USE_PLATFORM_WAYLAND_KHR
#include <vulkan/vulkan.h>
#include <wayland-client.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <locale>
#include <memory>
#include <poll.h>
#include <sstream>
#include <unistd.h>

// Adapted from the reviewed tools/linux/hdr-prototype capability fixture. Differences:
// colour values are reported by protocol enum *name* (generated constants), never by
// hardcoded wire numbers, and the result is serialised for the application policy.

namespace {
using Clock = std::chrono::steady_clock;

std::string transferName(uint32_t value) {
    switch (value) {
        case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_BT1886: return "bt1886";
        case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_GAMMA22: return "gamma22";
        case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_GAMMA28: return "gamma28";
        case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_LINEAR: return "ext_linear";
        case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_SRGB: return "srgb";
        case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_SRGB: return "ext_srgb";
        case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ: return "st2084_pq";
        case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_HLG: return "hlg";
        default: return "other:" + std::to_string(value);
    }
}

std::string primariesName(uint32_t value) {
    switch (value) {
        case WP_COLOR_MANAGER_V1_PRIMARIES_SRGB: return "srgb";
        case WP_COLOR_MANAGER_V1_PRIMARIES_BT2020: return "bt2020";
        case WP_COLOR_MANAGER_V1_PRIMARIES_DCI_P3: return "dci_p3";
        case WP_COLOR_MANAGER_V1_PRIMARIES_DISPLAY_P3: return "display_p3";
        case WP_COLOR_MANAGER_V1_PRIMARIES_ADOBE_RGB: return "adobe_rgb";
        default: return "other:" + std::to_string(value);
    }
}

// All proxy owners outlive their listeners. Disconnect happens after destroying the
// Vulkan surface and all proxies; callbacks never reference stack temporaries.
struct Description {
    LinuxHdrImageDescription value;
    uint32_t identity = 0;
    wp_image_description_v1 *image = nullptr;
    wp_image_description_info_v1 *info = nullptr;
    void release() {
        if (info) wp_image_description_info_v1_destroy(info);
        if (image) wp_image_description_v1_destroy(image);
        info = nullptr;
        image = nullptr;
    }
    ~Description() { release(); }
};

void infoDone(void *data, wp_image_description_info_v1 *info) {
    auto &d = *static_cast<Description *>(data);
    d.value.ready = true;
    wp_image_description_info_v1_destroy(info);
    d.info = nullptr;
}
void infoIcc(void *, wp_image_description_info_v1 *, int32_t fd, uint32_t) {
    // Never retain or read user colour profiles. An ICC-only result stays unknown.
    close(fd);
}
void infoPrimaries(void *, wp_image_description_info_v1 *, int32_t, int32_t, int32_t, int32_t,
                   int32_t, int32_t, int32_t, int32_t) {}
void infoPrimariesNamed(void *data, wp_image_description_info_v1 *, uint32_t value) {
    static_cast<Description *>(data)->value.primaries = primariesName(value);
}
void infoTfPower(void *, wp_image_description_info_v1 *, uint32_t) {}
void infoTfNamed(void *data, wp_image_description_info_v1 *, uint32_t value) {
    static_cast<Description *>(data)->value.transfer = transferName(value);
}
void infoLuminances(void *data, wp_image_description_info_v1 *, uint32_t min, uint32_t max,
                    uint32_t reference) {
    auto &d = static_cast<Description *>(data)->value;
    d.minNits = min / 10000.0;
    d.maxNits = max;
    d.referenceNits = reference;
}
void infoTargetLuminance(void *data, wp_image_description_info_v1 *, uint32_t min, uint32_t max) {
    auto &d = static_cast<Description *>(data)->value;
    d.targetMinNits = min / 10000.0;
    d.targetMaxNits = max;
}
void infoLight(void *, wp_image_description_info_v1 *, uint32_t) {}
const wp_image_description_info_v1_listener infoListener = {
    infoDone, infoIcc, infoPrimaries, infoPrimariesNamed, infoTfPower, infoTfNamed,
    infoLuminances, infoPrimaries, infoTargetLuminance, infoLight, infoLight,
};

void imageFailed(void *data, wp_image_description_v1 *, uint32_t cause, const char *) {
    auto &d = static_cast<Description *>(data)->value;
    d.failed = true;
    d.failure = "compositor image-description failure " + std::to_string(cause);
}
void imageReady(void *data, wp_image_description_v1 *image, uint32_t identity) {
    auto &d = *static_cast<Description *>(data);
    d.identity = identity;
    d.info = wp_image_description_v1_get_information(image);
    wp_image_description_info_v1_add_listener(d.info, &infoListener, &d);
}
const wp_image_description_v1_listener imageListener = {imageFailed, imageReady};

struct Output {
    wl_output *proxy = nullptr;
    wp_color_management_output_v1 *color = nullptr;
    Description description;
    ~Output() {
        description.release();
        if (color) wp_color_management_output_v1_destroy(color);
        if (proxy) wl_output_destroy(proxy);
    }
};

struct Wayland {
    LinuxHdrCapabilities &result;
    wl_display *display = nullptr;
    wl_registry *registry = nullptr;
    wl_compositor *compositor = nullptr;
    wl_surface *surface = nullptr;
    wp_color_manager_v1 *manager = nullptr;
    wp_color_management_surface_feedback_v1 *feedback = nullptr;
    std::vector<std::unique_ptr<Output>> outputs;
    Description preferred;
    bool managerDone = false;
    bool descriptionChanged = false;
    uint32_t preferredIdentity = 0;
    explicit Wayland(LinuxHdrCapabilities &r) : result(r) {}
    ~Wayland() {
        // Description proxies must be released before disconnect.
        outputs.clear();
        preferred.release();
        if (feedback) wp_color_management_surface_feedback_v1_destroy(feedback);
        if (surface) wl_surface_destroy(surface);
        if (manager) wp_color_manager_v1_destroy(manager);
        if (compositor) wl_compositor_destroy(compositor);
        if (registry) wl_registry_destroy(registry);
        if (display) wl_display_disconnect(display);
    }
};

void supportedIntent(void *, wp_color_manager_v1 *, uint32_t) {}
void supportedFeature(void *, wp_color_manager_v1 *, uint32_t) {}
void supportedTransfer(void *data, wp_color_manager_v1 *, uint32_t value) {
    if (value == WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ)
        static_cast<Wayland *>(data)->result.pqAdvertised = true;
}
void supportedPrimaries(void *data, wp_color_manager_v1 *, uint32_t value) {
    if (value == WP_COLOR_MANAGER_V1_PRIMARIES_BT2020)
        static_cast<Wayland *>(data)->result.bt2020Advertised = true;
}
void managerDone(void *data, wp_color_manager_v1 *) { static_cast<Wayland *>(data)->managerDone = true; }
const wp_color_manager_v1_listener managerListener = {
    supportedIntent, supportedFeature, supportedTransfer, supportedPrimaries, managerDone,
};

void outputGeometry(void *, wl_output *, int32_t, int32_t, int32_t, int32_t, int32_t, const char *,
                    const char *, int32_t) {}
void outputMode(void *, wl_output *, uint32_t, int32_t, int32_t, int32_t) {}
// Bound at version 1: only geometry and mode can be delivered.
const wl_output_listener outputListener = {outputGeometry, outputMode, nullptr, nullptr, nullptr, nullptr};

void outputChanged(void *data, wp_color_management_output_v1 *) {
    // A change while get_information is in flight invalidates this bounded snapshot.
    auto &description = *static_cast<Description *>(data);
    description.value.failed = true;
    description.value.failure = "output description changed during probe";
}
const wp_color_management_output_v1_listener colorOutputListener = {outputChanged};

void preferredChanged(void *data, wp_color_management_surface_feedback_v1 *, uint32_t identity) {
    auto &w = *static_cast<Wayland *>(data);
    if (w.preferredIdentity && w.preferredIdentity != identity) w.descriptionChanged = true;
    w.preferredIdentity = identity;
}
const wp_color_management_surface_feedback_v1_listener feedbackListener = {preferredChanged};

void global(void *data, wl_registry *registry, uint32_t name, const char *interface, uint32_t version) {
    auto &w = *static_cast<Wayland *>(data);
    if (!std::strcmp(interface, "wl_compositor")) {
        if (w.compositor) return;
        w.compositor = static_cast<wl_compositor *>(wl_registry_bind(registry, name, &wl_compositor_interface, 1));
    } else if (!std::strcmp(interface, "wp_color_manager_v1")) {
        if (w.manager) return;
        w.result.colorManagerVersion = version;
        // Version 1 carries every queried field; never claim newer event versions.
        w.manager = static_cast<wp_color_manager_v1 *>(wl_registry_bind(registry, name, &wp_color_manager_v1_interface, 1));
        wp_color_manager_v1_add_listener(w.manager, &managerListener, &w);
    } else if (!std::strcmp(interface, "wl_output")) {
        if (w.outputs.size() >= 32) {
            w.descriptionChanged = true; // Bound memory without silently omitting outputs.
            return;
        }
        auto output = std::make_unique<Output>();
        output->proxy = static_cast<wl_output *>(wl_registry_bind(registry, name, &wl_output_interface, 1));
        wl_output_add_listener(output->proxy, &outputListener, nullptr);
        w.outputs.push_back(std::move(output));
    }
}
void globalRemove(void *data, wl_registry *, uint32_t) {
    // Hotplug invalidates the snapshot; a fresh probe can recover.
    static_cast<Wayland *>(data)->descriptionChanged = true;
}
const wl_registry_listener registryListener = {global, globalRemove};

struct Sync { bool done = false; wl_callback *callback = nullptr; };
void syncDone(void *data, wl_callback *callback, uint32_t) {
    auto &sync = *static_cast<Sync *>(data);
    sync.done = true;
    wl_callback_destroy(callback);
    sync.callback = nullptr;
}
const wl_callback_listener syncListener = {syncDone};

bool roundtrip(Wayland &w, Clock::time_point deadline) {
    Sync sync;
    sync.callback = wl_display_sync(w.display);
    wl_callback_add_listener(sync.callback, &syncListener, &sync);
    bool ok = true;
    while (!sync.done) {
        if (Clock::now() >= deadline) { ok = false; break; }
        if (wl_display_prepare_read(w.display) != 0) {
            if (wl_display_dispatch_pending(w.display) < 0) { ok = false; break; }
            continue;
        }
        short events = POLLIN;
        if (wl_display_flush(w.display) < 0) {
            if (errno == EAGAIN) events |= POLLOUT;
            else { wl_display_cancel_read(w.display); ok = false; break; }
        }
        pollfd fd{wl_display_get_fd(w.display), events, 0};
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - Clock::now()).count();
        const int polled = poll(&fd, 1, static_cast<int>(std::max<int64_t>(0, remaining)));
        if (polled > 0 && (fd.revents & POLLIN)) {
            if (wl_display_read_events(w.display) < 0) { ok = false; break; }
            if (wl_display_dispatch_pending(w.display) < 0) { ok = false; break; }
        } else {
            const int pollError = errno;
            wl_display_cancel_read(w.display);
            if (polled < 0 && pollError == EINTR) continue;
            if (polled <= 0 || (fd.revents & (POLLERR | POLLHUP | POLLNVAL))) { ok = false; break; }
        }
    }
    if (sync.callback) wl_callback_destroy(sync.callback);
    return ok;
}

template<class T, class Query> bool enumerate(std::vector<T> &values, Query query) {
    uint32_t count = 0;
    if (query(&count, nullptr) != VK_SUCCESS || count > 4096) return false;
    values.resize(count);
    if (count && query(&count, values.data()) != VK_SUCCESS) return false;
    values.resize(count);
    return true;
}
bool hasExtension(const std::vector<VkExtensionProperties> &values, const char *name) {
    return std::any_of(values.begin(), values.end(), [name](const auto &v) { return !std::strcmp(v.extensionName, name); });
}

struct Vulkan {
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    ~Vulkan() {
        if (surface) vkDestroySurfaceKHR(instance, surface, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
    }
};

bool queryVulkan(Wayland &w, LinuxHdrCapabilities &result) {
    std::vector<VkExtensionProperties> instanceExtensions;
    if (!enumerate(instanceExtensions, [](uint32_t *n, VkExtensionProperties *p) {
        return vkEnumerateInstanceExtensionProperties(nullptr, n, p);
    })) { result.reason = "Vulkan instance-extension query failed"; return false; }
    std::vector<const char *> enabled;
    for (const char *name : {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME}) {
        if (!hasExtension(instanceExtensions, name)) {
            result.reason = std::string("Vulkan extension unavailable: ") + name;
            return false;
        }
        enabled.push_back(name);
    }
    if (hasExtension(instanceExtensions, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME))
        enabled.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
    Vulkan vk;
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Nuvio read-only HDR capability probe";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo create{};
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &app;
    create.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
    create.ppEnabledExtensionNames = enabled.data();
    if (vkCreateInstance(&create, nullptr, &vk.instance) != VK_SUCCESS) {
        result.reason = "Vulkan instance creation failed";
        return false;
    }
    VkWaylandSurfaceCreateInfoKHR surface{};
    surface.sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR;
    surface.display = w.display;
    surface.surface = w.surface;
    if (vkCreateWaylandSurfaceKHR(vk.instance, &surface, nullptr, &vk.surface) != VK_SUCCESS) {
        result.reason = "Vulkan Wayland surface creation failed";
        return false;
    }
    std::vector<VkPhysicalDevice> devices;
    if (!enumerate(devices, [&vk](uint32_t *n, VkPhysicalDevice *p) {
        return vkEnumeratePhysicalDevices(vk.instance, n, p);
    }) || devices.empty()) {
        result.reason = "No queryable Vulkan devices";
        return false;
    }
    for (auto device : devices) {
        LinuxHdrVulkanDevice evidence;
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(device, &properties);
        evidence.name = properties.deviceName; // No UUID/serial identifiers.
        std::vector<VkExtensionProperties> extensions;
        std::vector<VkSurfaceFormatKHR> formats;
        if (!enumerate(extensions, [device](uint32_t *n, VkExtensionProperties *p) {
            return vkEnumerateDeviceExtensionProperties(device, nullptr, n, p);
        }) || !enumerate(formats, [device, &vk](uint32_t *n, VkSurfaceFormatKHR *p) {
            return vkGetPhysicalDeviceSurfaceFormatsKHR(device, vk.surface, n, p);
        })) {
            result.reason = "Vulkan device-extension/surface-format query failed";
            return false;
        }
        evidence.hdrMetadataExtension = hasExtension(extensions, VK_EXT_HDR_METADATA_EXTENSION_NAME);
        for (const auto &format : formats) {
            if (format.colorSpace != VK_COLOR_SPACE_HDR10_ST2084_EXT) continue;
            evidence.hdr10SurfaceFormat = true;
            evidence.hdr10TenBitSurfaceFormat |= format.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
                format.format == VK_FORMAT_A2R10G10B10_UNORM_PACK32;
        }
        uint32_t count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &count, nullptr);
        if (count > 4096) { result.reason = "Invalid Vulkan queue count"; return false; }
        std::vector<VkQueueFamilyProperties> queues(count);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &count, queues.data());
        for (uint32_t i = 0; i < count; ++i) {
            VkBool32 supported = VK_FALSE;
            if (vkGetPhysicalDeviceSurfaceSupportKHR(device, i, vk.surface, &supported) != VK_SUCCESS) {
                result.reason = "Vulkan surface-present-support query failed";
                return false;
            }
            if (queues[i].queueCount && (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && supported)
                evidence.waylandPresentQueue = true;
        }
        result.devices.push_back(std::move(evidence));
    }
    return true;
}

std::string jsonString(const std::string &value) {
    std::string out = "\"";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') { out += '\\'; out += static_cast<char>(c); }
        else if (c < 0x20 || c >= 0x7f) {
            char escaped[7];
            std::snprintf(escaped, sizeof(escaped), "\\u%04x", c < 0x20 ? c : 0xfffd);
            out += escaped;
        } else out += static_cast<char>(c);
    }
    return out + "\"";
}

void appendDescription(std::ostringstream &json, const LinuxHdrImageDescription &d) {
    auto number = [&](double value) {
        if (std::isfinite(value)) json << value; else json << -1;
    };
    json << "{\"ready\":" << (d.ready ? "true" : "false") << ",\"failed\":" << (d.failed ? "true" : "false")
         << ",\"transfer\":" << jsonString(d.transfer) << ",\"primaries\":" << jsonString(d.primaries)
         << ",\"min_nits\":";
    number(d.minNits);
    json << ",\"max_nits\":";
    number(d.maxNits);
    json << ",\"reference_nits\":";
    number(d.referenceNits);
    json << ",\"target_max_nits\":";
    number(d.targetMaxNits);
    json << '}';
}
} // namespace

LinuxHdrCapabilities probeLinuxHdrCapabilities(std::chrono::milliseconds timeout) {
    LinuxHdrCapabilities result;
    if (timeout.count() <= 0) {
        result.reason = "Capability probe requires a positive timeout";
        return result;
    }
    const char *socket = std::getenv("WAYLAND_DISPLAY");
    if (!socket || !*socket) {
        result.reason = "WAYLAND_DISPLAY is unset; no display connection attempted";
        return result;
    }
    Wayland w(result);
    const auto deadline = Clock::now() + std::min(timeout, std::chrono::milliseconds(30000));
    w.display = wl_display_connect(nullptr);
    if (!w.display) { result.reason = "Wayland connection unavailable"; return result; }
    w.registry = wl_display_get_registry(w.display);
    wl_registry_add_listener(w.registry, &registryListener, &w);
    if (!roundtrip(w, deadline) || !roundtrip(w, deadline)) {
        result.reason = "Wayland registry timeout/disconnect";
        return result;
    }
    if (!w.compositor || !w.manager || !w.managerDone) {
        result.reason = "Wayland color-management/compositor capability unavailable";
        return result;
    }
    w.surface = wl_compositor_create_surface(w.compositor);
    w.feedback = wp_color_manager_v1_get_surface_feedback(w.manager, w.surface);
    wp_color_management_surface_feedback_v1_add_listener(w.feedback, &feedbackListener, &w);
    w.preferred.image = wp_color_management_surface_feedback_v1_get_preferred(w.feedback);
    wp_image_description_v1_add_listener(w.preferred.image, &imageListener, &w.preferred);
    for (auto &output : w.outputs) {
        output->color = wp_color_manager_v1_get_output(w.manager, output->proxy);
        wp_color_management_output_v1_add_listener(output->color, &colorOutputListener, &output->description);
        output->description.image = wp_color_management_output_v1_get_image_description(output->color);
        wp_image_description_v1_add_listener(output->description.image, &imageListener, &output->description);
    }
    // Requests -> image ready -> get_information -> done, bounded by one deadline.
    for (int i = 0; i < 3; ++i) {
        if (!roundtrip(w, deadline)) {
            result.reason = "Wayland color-description timeout/disconnect";
            return result;
        }
    }
    result.preferred = w.preferred.value;
    for (const auto &output : w.outputs) result.outputs.push_back(output->description.value);
    if (!queryVulkan(w, result)) return result;
    if (!roundtrip(w, deadline)) {
        result.reason = "Wayland final capability snapshot timeout/disconnect";
        return result;
    }
    if (w.descriptionChanged || (w.preferredIdentity && w.preferredIdentity != w.preferred.identity) ||
        w.preferred.value.failed || !w.preferred.value.ready || w.outputs.empty() ||
        std::any_of(w.outputs.begin(), w.outputs.end(), [](const auto &o) {
            return o->description.value.failed || !o->description.value.ready;
        })) {
        result.reason = "Color descriptions unavailable or changed during probe";
        return result;
    }
    result.probeComplete = true;
    result.reason = "Capability snapshot complete; the application policy decides eligibility";
    return result;
}

std::string linuxHdrCapabilitiesJson(const LinuxHdrCapabilities &c) {
    std::ostringstream json;
    json.imbue(std::locale::classic());
    json << "{\"schema\":1,\"probe_complete\":" << (c.probeComplete ? "true" : "false")
         << ",\"reason\":" << jsonString(c.reason)
         << ",\"color_manager_version\":" << c.colorManagerVersion
         << ",\"pq_advertised\":" << (c.pqAdvertised ? "true" : "false")
         << ",\"bt2020_advertised\":" << (c.bt2020Advertised ? "true" : "false")
         << ",\"preferred\":";
    appendDescription(json, c.preferred);
    json << ",\"outputs\":[";
    for (size_t i = 0; i < c.outputs.size(); ++i) {
        if (i) json << ',';
        appendDescription(json, c.outputs[i]);
    }
    json << "],\"devices\":[";
    for (size_t i = 0; i < c.devices.size(); ++i) {
        const auto &d = c.devices[i];
        if (i) json << ',';
        json << "{\"name\":" << jsonString(d.name)
             << ",\"hdr_metadata\":" << (d.hdrMetadataExtension ? "true" : "false")
             << ",\"hdr10\":" << (d.hdr10SurfaceFormat ? "true" : "false")
             << ",\"hdr10_10bit\":" << (d.hdr10TenBitSurfaceFormat ? "true" : "false")
             << ",\"wayland_present\":" << (d.waylandPresentQueue ? "true" : "false") << '}';
    }
    json << "]}";
    return json.str();
}
