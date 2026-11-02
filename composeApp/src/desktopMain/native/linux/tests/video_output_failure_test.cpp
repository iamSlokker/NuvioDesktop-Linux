// A video output that cannot start, with real libmpv: asks for the embedded player's gpu-next on x11vk
// with no display and no Vulkan driver, and checks mpv's verdict reaches the existing failure callback
// once, with playback stopped. Display variables and the driver list are cleared by the test itself,
// so nothing can open a window. Negative control: a null output never matches.
#include "video_output_failure.h"
#include <unistd.h>
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const std::string &operation) {
    require(result >= 0, operation + ": " + mpv_error_string(result));
}

struct Media {
    std::filesystem::path directory = std::filesystem::temp_directory_path() /
        ("nuvio-video-output-" + std::to_string(::getpid()));
    std::string video() const { return (directory / "video.y4m").string(); }
    Media() {
        std::filesystem::create_directories(directory);
        std::ofstream file(directory / "video.y4m", std::ios::binary);
        file << "YUV4MPEG2 W2 H2 F25:1 Ip A1:1 C420jpeg\n";
        for (int frame = 0; frame < 25; ++frame) file << "FRAME\n" << std::string(6, '\x40');
    }
    ~Media() { std::error_code ignored; std::filesystem::remove_all(directory, ignored); }
};

struct Outcome {
    std::vector<std::string> failures;
    bool loaded = false;
};

Outcome run(const std::string &video, const std::vector<std::pair<const char *, const char *>> &output) {
    std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)> player(mpv_create(), mpv_terminate_destroy);
    mpv_handle *mpv = player.get();
    require(mpv != nullptr, "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"ao", "null"}, {"terminal", "no"},
             {"pause", "no"}, {"idle", "yes"}, {"keep-open", "yes"}, {"hwdec", "no"}})
        check(mpv_set_option_string(mpv, name, value), name);
    for (const auto &[name, value] : output) check(mpv_set_option_string(mpv, name, value), name);
    check(mpv_initialize(mpv), "initialize");
    check(mpv_request_log_messages(mpv, "warn"), "log messages");
    const char *load[] = {"loadfile", video.c_str(), nullptr};
    check(mpv_command(mpv, load), "loadfile");

    LinuxHttpRecoveryEvents recovery;
    LinuxVideoOutputFailures failures;
    Outcome outcome;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < deadline) {
        mpv_event *event = mpv_wait_event(mpv, 0.1);
        auto result = recovery.accept(*event, LinuxHttpRecoveryEvents::Clock::now());
        failures.filter(*event, recovery, result);
        if (result.loaded) outcome.loaded = true;
        if (result.failure) {
            outcome.failures.push_back(result.failure->event);
            if (result.failure->stop) {
                const char *stop[] = {"stop", nullptr};
                check(mpv_command(mpv, stop), "stop");
            }
        }
    }
    return outcome;
}

// Both orders libmpv can deliver, with a failure that is not about the output as the control.
void ordering() {
    mpv_event_log_message log{};
    log.prefix = "cplayer";
    log.level = "fatal";
    log.log_level = MPV_LOG_LEVEL_FATAL;
    log.text = "Error opening/initializing the selected video_out (--vo) device.\n";
    mpv_event verdict{};
    verdict.event_id = MPV_EVENT_LOG_MESSAGE;
    verdict.data = &log;
    mpv_event start{};
    start.event_id = MPV_EVENT_START_FILE;
    mpv_event_end_file end{};
    end.reason = MPV_END_FILE_REASON_ERROR;
    end.error = MPV_ERROR_NOTHING_TO_PLAY;
    mpv_event ended{};
    ended.event_id = MPV_EVENT_END_FILE;
    ended.data = &end;
    mpv_event none{};
    const auto now = LinuxHttpRecoveryEvents::Clock::now();
    const auto feed = [&](LinuxHttpRecoveryEvents &recovery, LinuxVideoOutputFailures &failures, const mpv_event &event) {
        auto result = recovery.accept(event, now);
        failures.filter(event, recovery, result);
        return result.failure ? result.failure->event : std::string();
    };
    {
        LinuxHttpRecoveryEvents recovery;
        LinuxVideoOutputFailures failures;
        feed(recovery, failures, start);
        require(feed(recovery, failures, ended).empty(), "END_FILE first: held");
        const auto reported = feed(recovery, failures, verdict);
        require(reported.find(kLinuxVideoOutputFailure) != std::string::npos, "and replaced by the verdict: " + reported);
        require(feed(recovery, failures, none).empty(), "once");
    }
    {
        LinuxHttpRecoveryEvents recovery;
        LinuxVideoOutputFailures failures;
        feed(recovery, failures, start);
        const auto reported = feed(recovery, failures, verdict);
        require(reported.find(kLinuxVideoOutputFailure) != std::string::npos, "verdict first: " + reported);
        require(feed(recovery, failures, ended).empty(), "END_FILE after it is not a second failure");
    }
    {
        LinuxHttpRecoveryEvents recovery;
        LinuxVideoOutputFailures failures;
        feed(recovery, failures, start);
        require(feed(recovery, failures, ended).empty(), "held");
        const auto reported = feed(recovery, failures, none);
        require(reported.find("no audio or video data played") != std::string::npos, "an empty queue: mpv's own: " + reported);
    }
}

void rules() {
    mpv_event_log_message log{};
    log.prefix = "cplayer";
    log.level = "fatal";
    log.log_level = MPV_LOG_LEVEL_FATAL;
    log.text = "Error opening/initializing the selected video_out (--vo) device.\n";
    mpv_event event{};
    event.event_id = MPV_EVENT_LOG_MESSAGE;
    event.data = &log;
    require(linuxVideoOutputInitFailed(event), "mpv's verdict");
    log.prefix = "vo/gpu-next";
    require(!linuxVideoOutputInitFailed(event), "only the player's verdict, not a VO's passing complaint");
    log.prefix = "cplayer";
    log.log_level = MPV_LOG_LEVEL_INFO;
    require(!linuxVideoOutputInitFailed(event), "not below error");
    log.log_level = MPV_LOG_LEVEL_FATAL;
    log.text = "Failed to recognize file format.\n";
    require(!linuxVideoOutputInitFailed(event), "not a media failure");
    event.event_id = MPV_EVENT_IDLE;
    require(!linuxVideoOutputInitFailed(event), "only log events");
}
}

int main() {
    try {
        // Headless by construction, whatever launched this.
        unsetenv("DISPLAY");
        unsetenv("WAYLAND_DISPLAY");
        setenv("VK_ICD_FILENAMES", "/nonexistent/no-driver.json", 1);
        setenv("VK_DRIVER_FILES", "/nonexistent/no-driver.json", 1);
        rules();
        std::cout << "PASS the rule matches mpv's verdict only\n";
        ordering();
        std::cout << "PASS either delivery order gives one failure\n";
        Media media;
        const auto failed = run(media.video(), {{"vo", "gpu-next"}, {"gpu-api", "vulkan"}, {"gpu-context", "x11vk"}});
        require(failed.failures.size() == 1, "one failure, not " + std::to_string(failed.failures.size()));
        require(failed.failures[0].find(kLinuxVideoOutputFailure) != std::string::npos, "the clear message: " + failed.failures[0]);
        std::cout << "PASS real libmpv with no output: one failure, naming Vulkan\n";
        const auto fine = run(media.video(), {{"vo", "null"}});
        require(fine.loaded && fine.failures.empty(), "negative control: a working output never reports this");
        std::cout << "PASS a working output reports nothing\n";
        std::cout << "VIDEO_OUTPUT_FAILURE_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
