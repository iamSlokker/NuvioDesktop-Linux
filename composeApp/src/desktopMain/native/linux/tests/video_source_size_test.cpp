// The size event's value and when it is sent (video_source_size.h), against real libmpv's video-params
// with a null output. No display, GTK or JNI.
#include "video_source_size.h"
#include <mpv/client.h>
#include <unistd.h>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const char *operation) {
    require(result >= 0, std::string(operation) + ": " + mpv_error_string(result));
}
void rules() {
    require(linuxPackedVideoSize(3840, 2160) == 3840 * 65536 + 2160, "packing");
    require(linuxPackedVideoSize(0, 1080) == 0 && linuxPackedVideoSize(1920, 0) == 0, "half a size is none");
    require(linuxPackedVideoSize(70000, 1080) == 0, "beyond what the value can carry");
    LinuxVideoSizeReport report;
    require(!report.changed(0), "unknown is not sent");
    require(report.changed(linuxPackedVideoSize(1920, 1080)), "first size");
    require(!report.changed(linuxPackedVideoSize(1920, 1080)), "same size");
    require(!report.changed(0), "a reconfiguration gap is not sent");
    require(!report.changed(linuxPackedVideoSize(1920, 1080)), "nor the same size after it");
    require(report.changed(linuxPackedVideoSize(1280, 720)), "an adaptive switch is");
}
void realLibmpv() {
    const auto directory = std::filesystem::temp_directory_path() / ("nuvio-video-size-" + std::to_string(::getpid()));
    std::filesystem::create_directories(directory);
    const auto video = (directory / "video.y4m").string();
    {
        std::ofstream file(video, std::ios::binary);
        file << "YUV4MPEG2 W64 H36 F25:1 Ip A1:1 C420jpeg\n";
        for (int frame = 0; frame < 25; ++frame) file << "FRAME\n" << std::string(64 * 36 * 3 / 2, '\x40');
    }
    std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)> player(mpv_create(), mpv_terminate_destroy);
    mpv_handle *mpv = player.get();
    require(mpv != nullptr, "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"vo", "null"}, {"ao", "null"},
             {"terminal", "no"}, {"pause", "yes"}, {"idle", "yes"}, {"keep-open", "yes"}})
        check(mpv_set_option_string(mpv, name, value), name);
    check(mpv_initialize(mpv), "initialize");
    check(mpv_observe_property(mpv, 1, "video-params", MPV_FORMAT_NODE), "observe");
    const char *load[] = {"loadfile", video.c_str(), nullptr};
    check(mpv_command(mpv, load), "loadfile");
    LinuxVideoSizeReport report;
    int64_t sent = 0;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!sent && std::chrono::steady_clock::now() < deadline) {
        const auto *event = mpv_wait_event(mpv, 0.1);
        if (event->event_id != MPV_EVENT_PROPERTY_CHANGE || event->reply_userdata != 1) continue;
        const auto *property = static_cast<const mpv_event_property *>(event->data);
        if (property->format != MPV_FORMAT_NODE) continue;
        const auto *params = static_cast<const mpv_node *>(property->data);
        if (params->format != MPV_FORMAT_NODE_MAP || !params->u.list) continue;
        int64_t width = 0, height = 0;
        for (int i = 0; i < params->u.list->num; ++i) {
            const std::string key = params->u.list->keys[i];
            const auto &value = params->u.list->values[i];
            if (key == "w" && value.format == MPV_FORMAT_INT64) width = value.u.int64;
            if (key == "h" && value.format == MPV_FORMAT_INT64) height = value.u.int64;
        }
        const int64_t packed = linuxPackedVideoSize(width, height);
        if (report.changed(packed)) sent = packed;
    }
    std::error_code ignored;
    std::filesystem::remove_all(directory, ignored);
    require(sent == linuxPackedVideoSize(64, 36), "mpv's decoded size reaches the event: " + std::to_string(sent));
}
}

int main() {
    try {
        rules();
        std::cout << "PASS value and timing of the size event\n";
        realLibmpv();
        std::cout << "PASS real libmpv: the decoded size reaches the event\n";
        std::cout << "VIDEO_SOURCE_SIZE_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
