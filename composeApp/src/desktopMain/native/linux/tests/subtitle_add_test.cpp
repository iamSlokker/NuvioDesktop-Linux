// Addon subtitle adds (subtitle_add.h) with real libmpv and a slow loopback HTTP server: the add
// returns at once, the last choice wins, and an abandoned add never stays selected. Never creates
// a window, initializes GTK/JNI, opens an audio device or leaves 127.0.0.1.
#include "subtitle_add.h"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;

void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const char *operation) {
    require(result >= 0, std::string(operation) + ": " + mpv_error_string(result));
}
long long msSince(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

// Serves /slow-<ms>-<name>.srt after <ms> milliseconds, one thread per connection.
class SlowServer {
public:
    SlowServer() {
        listener_ = socket(AF_INET, SOCK_STREAM, 0);
        require(listener_ >= 0, "socket");
        int yes = 1;
        setsockopt(listener_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        require(bind(listener_, reinterpret_cast<sockaddr *>(&address), sizeof address) == 0, "bind");
        require(listen(listener_, 16) == 0, "listen");
        socklen_t length = sizeof address;
        getsockname(listener_, reinterpret_cast<sockaddr *>(&address), &length);
        port_ = ntohs(address.sin_port);
        acceptor_ = std::thread([this] { run(); });
    }
    ~SlowServer() {
        stopping_ = true;
        shutdown(listener_, SHUT_RDWR);
        close(listener_);
        acceptor_.join();
        for (auto &worker : workers_) worker.join();
    }
    std::string url(int delayMs, const std::string &name) const {
        return "http://127.0.0.1:" + std::to_string(port_) + "/slow-" + std::to_string(delayMs) + "-" + name + ".srt";
    }

private:
    void run() {
        while (!stopping_) {
            const int client = accept(listener_, nullptr, nullptr);
            if (client < 0) continue;
            workers_.emplace_back([client] { serve(client); });
        }
    }
    static void serve(int client) {
        char buffer[4096];
        const ssize_t read = recv(client, buffer, sizeof buffer - 1, 0);
        std::string request(buffer, read > 0 ? size_t(read) : 0);
        int delayMs = 0;
        std::string name = "sub";
        const auto slash = request.find("/slow-");
        if (slash != std::string::npos) {
            delayMs = std::atoi(request.c_str() + slash + 6);
            const auto dash = request.find('-', slash + 6);
            const auto dot = request.find(".srt", slash);
            if (dash != std::string::npos && dot != std::string::npos && dash < dot)
                name = request.substr(dash + 1, dot - dash - 1);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(delayMs));
        const std::string body = "1\n00:00:00,000 --> 00:00:09,000\n" + name + "\n\n";
        const std::string response = "HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip\r\nContent-Length: " +
            std::to_string(body.size()) + "\r\nConnection: close\r\n\r\n" + body;
        send(client, response.data(), response.size(), MSG_NOSIGNAL);
        close(client);
    }
    int listener_ = -1;
    int port_ = 0;
    std::atomic<bool> stopping_{false};
    std::thread acceptor_;
    std::vector<std::thread> workers_;
};

using Player = std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)>;

struct Media {
    std::filesystem::path directory;
    Media() {
        char pattern[] = "/tmp/nuvio-subtitle-add-XXXXXX";
        char *created = mkdtemp(pattern);
        require(created != nullptr, "mkdtemp");
        directory = created;
        std::ofstream video(directory / "video.y4m", std::ios::binary);
        video << "YUV4MPEG2 W2 H2 F25:1 Ip A1:1 C420jpeg\n";
        for (int i = 0; i < 250; ++i) video << "FRAME\n" << std::string(6, char(128));
        require(video.good(), "write media fixture");
    }
    ~Media() { std::filesystem::remove_all(directory); }
};

Player player(const Media &media) {
    Player mpv(mpv_create(), mpv_terminate_destroy);
    require(bool(mpv), "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"vo", "null"}, {"ao", "null"},
             {"hwdec", "no"}, {"terminal", "no"}, {"pause", "yes"}, {"idle", "yes"}}) {
        check(mpv_set_option_string(mpv.get(), name, value), name);
    }
    check(mpv_initialize(mpv.get()), "initialize");
    const auto video = (media.directory / "video.y4m").string();
    const char *command[] = {"loadfile", video.c_str(), nullptr};
    check(mpv_command(mpv.get(), command), "loadfile");
    const auto deadline = Clock::now() + std::chrono::seconds(5);
    while (Clock::now() < deadline) {
        auto *event = mpv_wait_event(mpv.get(), 0.1);
        if (event->event_id == MPV_EVENT_FILE_LOADED) return mpv;
        require(event->event_id != MPV_EVENT_END_FILE, "media failed to load");
    }
    throw std::runtime_error("file-loaded timeout");
}

// The bridge's event-thread handling, until every add has replied (or the deadline passes).
void pump(mpv_handle *mpv, LinuxSubtitleAdds &adds, int replies) {
    const auto deadline = Clock::now() + std::chrono::seconds(10);
    while (replies > 0 && Clock::now() < deadline) {
        auto *event = mpv_wait_event(mpv, 0.1);
        if (event->event_id != MPV_EVENT_COMMAND_REPLY || !LinuxSubtitleAdds::owns(event->reply_userdata)) continue;
        --replies;
        std::string url;
        const auto repair = adds.finished(event->reply_userdata, event->error, url);
        if (repair) require(applyLinuxSubtitleRepair(mpv, *repair), "repair " + url);
    }
    require(replies == 0, "sub-add replies timed out");
}

std::string selectedText(mpv_handle *mpv) {
    using linux_subtitle_add_detail::property;
    const std::string sid = property(mpv, "sid");
    if (sid.empty() || sid == "no") return "no";
    const int count = std::atoi(property(mpv, "track-list/count").c_str());
    for (int i = 0; i < count; ++i) {
        const std::string prefix = "track-list/" + std::to_string(i) + "/";
        if (property(mpv, prefix + "type") == "sub" && property(mpv, prefix + "id") == sid) {
            const std::string file = property(mpv, prefix + "external-filename");
            const auto dash = file.rfind('-');
            return file.substr(dash + 1, file.size() - dash - 5);
        }
    }
    return "?";
}

int externalSubtitles(mpv_handle *mpv) {
    using linux_subtitle_add_detail::property;
    int total = 0;
    const int count = std::atoi(property(mpv, "track-list/count").c_str());
    for (int i = 0; i < count; ++i) {
        const std::string prefix = "track-list/" + std::to_string(i) + "/";
        if (property(mpv, prefix + "type") == "sub" && property(mpv, prefix + "external") == "yes") ++total;
    }
    return total;
}
}

int main() {
    try {
        Media media;
        SlowServer server;
        {
            // Negative control: the blocking call the bridge used waits for the whole download.
            auto mpv = player(media);
            const auto start = Clock::now();
            const std::string url = server.url(800, "blocking");
            const char *command[] = {"sub-add", url.c_str(), "select", nullptr};
            check(mpv_command(mpv.get(), command), "blocking sub-add");
            require(msSince(start) >= 700, "the blocking sub-add should wait for the download");
        }
        std::cout << "PASS blocking sub-add waits for the download (negative control)\n";
        {
            auto mpv = player(media);
            LinuxSubtitleAdds adds;
            const auto start = Clock::now();
            check(adds.add(mpv.get(), server.url(800, "alpha")), "add");
            require(msSince(start) < 200, "add must return before the download finishes");
            pump(mpv.get(), adds, 1);
            require(selectedText(mpv.get()) == "alpha", "the added subtitle is selected once loaded");
        }
        std::cout << "PASS add returns at once and selects the subtitle when loaded\n";
        {
            // A slow pick, then a fast one: the fast (latest) one stays, the slow one never sticks.
            auto mpv = player(media);
            LinuxSubtitleAdds adds;
            check(adds.add(mpv.get(), server.url(900, "slow")), "slow add");
            check(adds.add(mpv.get(), server.url(50, "fast")), "fast add");
            pump(mpv.get(), adds, 2);
            require(selectedText(mpv.get()) == "fast", "the latest pick must win, got " + selectedText(mpv.get()));
            require(externalSubtitles(mpv.get()) == 1, "the abandoned subtitle must not stay loaded");
        }
        std::cout << "PASS the latest of two picks wins\n";
        {
            // A pick, then subtitles off before it loads: they stay off.
            auto mpv = player(media);
            LinuxSubtitleAdds adds;
            check(adds.add(mpv.get(), server.url(600, "late")), "add");
            adds.abandon(mpv.get(), "no");
            check(mpv_set_property_string(mpv.get(), "sid", "no"), "sid no");
            pump(mpv.get(), adds, 1);
            require(selectedText(mpv.get()) == "no", "subtitles turned off must stay off");
            require(externalSubtitles(mpv.get()) == 0, "the abandoned subtitle must not stay loaded");
        }
        std::cout << "PASS turning subtitles off abandons a pick in flight\n";
        {
            // The repair itself, for an abandoned add that completed anyway (the abort came too late).
            auto mpv = player(media);
            LinuxSubtitleAdds adds;
            const std::string kept = server.url(0, "kept");
            const std::string stray = server.url(0, "stray");
            for (const auto &url : {kept, stray}) {
                const char *command[] = {"sub-add", url.c_str(), "select", nullptr};
                check(mpv_command(mpv.get(), command), "sub-add");
            }
            LinuxSubtitleAdds::Repair repair;
            repair.removeUrl = stray;
            repair.selectUrl = kept;
            require(applyLinuxSubtitleRepair(mpv.get(), repair), "repair");
            require(selectedText(mpv.get()) == "kept" && externalSubtitles(mpv.get()) == 1,
                    "a stray subtitle must be taken out and the wanted one selected");
        }
        std::cout << "PASS a stray subtitle is taken out again\n";
        std::cout << "SUBTITLE_ADD_ASYNC_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL " << error.what() << "\n";
        return 1;
    }
}
