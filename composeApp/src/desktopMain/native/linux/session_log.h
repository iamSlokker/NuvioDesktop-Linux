#pragma once

// Windows' player logs (nuvioMpvLogAppend and friends), beside nuvio.log in <data folder>/logs, the
// folder Settings opens for a support request: nuvio-mpv.log for the latest main player with four
// older ones, plus a complete nuvio-playback-* file per session (20 kept) and nuvio-trailer-* (5).
// Normal playback writes lifecycle markers and mpv's warnings and errors only. mpv prints complete
// media URLs, and provider URLs carry debrid tokens or signed paths, so only the host is ever kept.

#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <filesystem>
#include <functional>
#include <mutex>
#include <string>
#include <system_error>
#include <unordered_map>
#include <vector>

inline std::string linuxLogRedactUrls(const std::string &line) {
    std::string safe;
    safe.reserve(line.size());
    size_t cursor = 0;
    while (cursor < line.size()) {
        const size_t http = line.find("http://", cursor);
        const size_t https = line.find("https://", cursor);
        const size_t start = std::min(http, https);
        if (start == std::string::npos) {
            safe.append(line, cursor, std::string::npos);
            break;
        }
        safe.append(line, cursor, start - cursor);
        const size_t authority = start + (line.compare(start, 8, "https://") == 0 ? 8 : 7);
        size_t authorityEnd = line.find_first_of("/?# \t\r\n\"'<>(){}", authority);
        if (authorityEnd == std::string::npos) authorityEnd = line.size();
        const size_t userInfoEnd = line.rfind('@', authorityEnd);
        const size_t host = userInfoEnd != std::string::npos && userInfoEnd >= authority ? userInfoEnd + 1 : authority;
        safe.append(line, start, authority - start);
        safe.append(line, host, authorityEnd - host);
        safe += "/<redacted>";
        const size_t end = line.find_first_of(" \t\r\n\"'<>[](){}", authorityEnd);
        cursor = end == std::string::npos ? line.size() : end;
    }
    return safe;
}

// A message mpv repeats (a failing segment, a dropped frame warning) stays readable: a few of the same
// line per ten seconds, then a count. Event thread only.
class LinuxLogRepeatLimit {
public:
    using Clock = std::chrono::steady_clock;
    static constexpr int Limit = 5;
    static constexpr size_t MaxTracked = 128;
    static constexpr auto Window = std::chrono::seconds(10);

    struct Decision {
        bool write;
        std::string note; // To write ahead of the line, when not empty.
    };

    Decision accept(const std::string &line, Clock::time_point now) {
        auto found = counts_.find(line);
        if (found == counts_.end()) {
            if (counts_.size() >= MaxTracked) {
                for (auto it = counts_.begin(); it != counts_.end();)
                    it = now - it->second.start >= Window ? counts_.erase(it) : std::next(it);
                if (counts_.size() >= MaxTracked) return {true, {}};
            }
            counts_[line] = {1, now};
            return {true, {}};
        }
        auto &repeat = found->second;
        if (now - repeat.start >= Window) {
            const int suppressed = repeat.count - (Limit - 1);
            repeat = {1, now};
            if (suppressed <= 0) return {true, {}};
            return {true, "(" + std::to_string(suppressed) + " repeats suppressed in the last window of: " +
                line.substr(0, line.find('\n')) + ")\n"};
        }
        const int count = ++repeat.count;
        if (count < Limit) return {true, {}};
        return {false, count == Limit ? "(suppressing repeats of the previous message for 10s)\n" : ""};
    }

private:
    struct Repeat { int count; Clock::time_point start; };
    std::unordered_map<std::string, Repeat> counts_;
};

class LinuxPlayerLog {
public:
    static constexpr int CurrentLogCount = 5;
    static constexpr size_t PlaybackLogsKept = 20;
    static constexpr size_t TrailerLogsKept = 5;

    // Set once by NativePlayerBridge's initializer. With no folder nothing is written.
    static void setDataDirectory(const std::string &directory) {
        std::lock_guard<std::mutex> lock(mutex());
        dataDirectory() = directory;
    }

    // One per native player, before anything is logged for it.
    void begin(bool trailer) {
        namespace fs = std::filesystem;
        const std::lock_guard<std::mutex> lock(mutex());
        if (dataDirectory().empty()) return;
        std::error_code error;
        const fs::path directory = fs::path(dataDirectory()) / "logs";
        fs::create_directories(directory, error);
        if (error) return;
        const std::string prefix = trailer ? "nuvio-trailer-" : "nuvio-playback-";
        session_ = sessionId();
        path_ = directory / (prefix + session_ + ".log");
        started_ = std::chrono::steady_clock::now();
        prune(directory, prefix, (trailer ? TrailerLogsKept : PlaybackLogsKept) - 1);
        if (trailer) return;
        latestMain() = path_;
        // The session that just ended becomes .1, keeping its failure evidence even when autoplay
        // creates a new player at once. Trailers and outgoing players never write into the current log.
        const auto current = [&](int index) {
            return directory / (index > 0 ? "nuvio-mpv.log." + std::to_string(index) : std::string("nuvio-mpv.log"));
        };
        fs::remove(current(CurrentLogCount - 1), error);
        for (int index = CurrentLogCount - 2; index >= 0; --index) fs::rename(current(index), current(index + 1), error);
        write(current(0), "");
    }

    void append(const std::string &text) {
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started_).count();
        const std::string line = linuxLogRedactUrls(
            "[session=" + session_ + " +" + std::to_string(elapsed) + "ms] " + text);
        const std::lock_guard<std::mutex> lock(mutex());
        if (path_.empty()) return;
        write(path_, line);
        if (path_ == latestMain()) write(path_.parent_path() / "nuvio-mpv.log", line);
    }

    void bridge(const std::string &text) { append("[nuvio-bridge " + wallClock() + "] " + text + "\n"); }

    const std::filesystem::path &path() const { return path_; }

private:
    std::filesystem::path path_;
    std::string session_;
    std::chrono::steady_clock::time_point started_{};

    static std::mutex &mutex() { static std::mutex value; return value; }
    static std::string &dataDirectory() { static std::string value; return value; }
    static std::filesystem::path &latestMain() { static std::filesystem::path value; return value; }

    static void write(const std::filesystem::path &path, const std::string &text) {
        if (FILE *file = std::fopen(path.c_str(), "a")) {
            std::fwrite(text.data(), 1, text.size(), file);
            std::fclose(file);
        }
    }

    static std::string sessionId() {
        static std::atomic<unsigned long long> sequence{0};
        const auto now = std::chrono::system_clock::now();
        const std::time_t seconds = std::chrono::system_clock::to_time_t(now);
        const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count() % 1000;
        std::tm local{};
        localtime_r(&seconds, &local);
        char buffer[96];
        std::snprintf(buffer, sizeof(buffer), "%04d%02d%02d-%02d%02d%02d-%03d-%ld-%llu", local.tm_year + 1900,
            local.tm_mon + 1, local.tm_mday, local.tm_hour, local.tm_min, local.tm_sec, static_cast<int>(millis),
            static_cast<long>(::getpid()), ++sequence);
        return buffer;
    }

    static std::string wallClock() {
        const auto now = std::chrono::system_clock::now();
        const std::time_t seconds = std::chrono::system_clock::to_time_t(now);
        const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count() % 1000;
        std::tm local{};
        localtime_r(&seconds, &local);
        char buffer[48];
        std::snprintf(buffer, sizeof(buffer), "%02d:%02d:%02d.%03d", local.tm_hour, local.tm_min, local.tm_sec,
            static_cast<int>(millis));
        return buffer;
    }

    // Names start with a sortable timestamp, so the newest sort last.
    static void prune(const std::filesystem::path &directory, const std::string &prefix, size_t keepPrevious) {
        std::error_code error;
        std::vector<std::filesystem::path> old;
        for (const auto &entry : std::filesystem::directory_iterator(directory, error)) {
            const std::string name = entry.path().filename().string();
            if (name.rfind(prefix, 0) == 0 && entry.path().extension() == ".log") old.push_back(entry.path());
        }
        std::sort(old.begin(), old.end(), std::greater<std::filesystem::path>());
        for (size_t index = keepPrevious; index < old.size(); ++index) std::filesystem::remove(old[index], error);
    }
};
