// The player's session logs (session_log.h) in a temporary folder: names, retention, rotation, URL
// redaction and the repeat limit. No libmpv, display or JNI.
#include "session_log.h"
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>

namespace {
namespace fs = std::filesystem;
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
std::string read(const fs::path &path) {
    std::ifstream file(path);
    std::stringstream text;
    text << file.rdbuf();
    return text.str();
}
size_t count(const fs::path &directory, const std::string &prefix) {
    size_t total = 0;
    for (const auto &entry : fs::directory_iterator(directory))
        if (entry.path().filename().string().rfind(prefix, 0) == 0) ++total;
    return total;
}

void redaction() {
    const auto safe = linuxLogRedactUrls;
    require(safe("no url here") == "no url here", "plain text passes");
    require(safe("open https://cdn.example.com/dl/SECRETTOKEN/film.mkv?sig=abc done") ==
        "open https://cdn.example.com/<redacted> done", "path and query go");
    require(safe("http://user:pass@host.example:8080/a") == "http://host.example:8080/<redacted>", "credentials go");
    require(safe("a https://one.example/x b http://two.example/y?z=1 c") ==
        "a https://one.example/<redacted> b http://two.example/<redacted> c", "every URL");
    require(safe("https://host.example") == "https://host.example/<redacted>", "bare host");
    require(safe("[ffmpeg] https: HTTP error 429 Too Many Requests\n") ==
        "[ffmpeg] https: HTTP error 429 Too Many Requests\n", "a scheme label is not a URL");
    const std::string query = safe("url=https://a.example/b?token=SECRET,next");
    require(query.find("SECRET") == std::string::npos, "no query leaks");
}

void repeats() {
    LinuxLogRepeatLimit limit;
    const auto start = LinuxLogRepeatLimit::Clock::now();
    int written = 0;
    std::string note;
    for (int i = 0; i < 20; ++i) {
        const auto decision = limit.accept("same\n", start);
        if (decision.write) ++written;
        if (!decision.note.empty()) note = decision.note;
    }
    require(written == LinuxLogRepeatLimit::Limit - 1, "four lines are written, then silence");
    require(note.find("suppressing") != std::string::npos, "and it says so");
    const auto later = limit.accept("same\n", start + LinuxLogRepeatLimit::Window);
    require(later.write && later.note.find("16 repeats suppressed") != std::string::npos, "the count comes after the window: " + later.note);
    require(limit.accept("different\n", start).write, "other lines are unaffected");
}

void files() {
    const fs::path root = fs::temp_directory_path() / ("nuvio-player-log-" + std::to_string(::getpid()));
    fs::remove_all(root);
    {
        LinuxPlayerLog beforeFolder;
        beforeFolder.begin(false);
        beforeFolder.append("dropped\n");
        require(beforeFolder.path().empty(), "no folder, no file");
    }
    LinuxPlayerLog::setDataDirectory(root.string());
    const fs::path logs = root / "logs";

    LinuxPlayerLog first;
    first.begin(false);
    first.bridge("initialize source=https://cdn.example.com/SECRET/film.mkv");
    require(fs::exists(first.path()) && first.path().filename().string().rfind("nuvio-playback-", 0) == 0, "session file");
    const std::string text = read(first.path());
    require(text.find("[nuvio-bridge ") != std::string::npos && text.find("[session=") != std::string::npos, "markers");
    require(text.find("SECRET") == std::string::npos && text.find("cdn.example.com") != std::string::npos, "file is redacted");
    require(read(logs / "nuvio-mpv.log") == text, "the current log mirrors the main player");

    LinuxPlayerLog trailer;
    trailer.begin(true);
    trailer.bridge("trailer");
    require(trailer.path().filename().string().rfind("nuvio-trailer-", 0) == 0, "trailer file");
    require(read(logs / "nuvio-mpv.log") == text, "a trailer never writes into the current log");

    LinuxPlayerLog second;
    second.begin(false);
    require(read(logs / "nuvio-mpv.log.1") == text, "the last session rotates to .1");
    require(read(logs / "nuvio-mpv.log").empty(), "a new current log starts empty");
    second.append("second\n");
    const std::string secondText = read(logs / "nuvio-mpv.log");
    first.append("outgoing\n");
    require(read(logs / "nuvio-mpv.log") == secondText, "an outgoing player keeps to its own file");
    require(read(first.path()).find("outgoing") != std::string::npos, "and still has it");

    for (int i = 0; i < 30; ++i) { LinuxPlayerLog log; log.begin(false); log.append("x\n"); }
    for (int i = 0; i < 12; ++i) { LinuxPlayerLog log; log.begin(true); log.append("x\n"); }
    require(count(logs, "nuvio-playback-") == LinuxPlayerLog::PlaybackLogsKept, "twenty playback logs: " + std::to_string(count(logs, "nuvio-playback-")));
    require(count(logs, "nuvio-trailer-") == LinuxPlayerLog::TrailerLogsKept, "five trailer logs: " + std::to_string(count(logs, "nuvio-trailer-")));
    require(count(logs, "nuvio-mpv.log") == static_cast<size_t>(LinuxPlayerLog::CurrentLogCount), "five current logs");
    LinuxPlayerLog::setDataDirectory("");
    fs::remove_all(root);
}
}

int main() {
    try {
        redaction();
        std::cout << "PASS URLs keep only the host\n";
        repeats();
        std::cout << "PASS repeats are limited and counted\n";
        files();
        std::cout << "PASS names, rotation and retention match Windows\n";
        std::cout << "PLAYER_LOG_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
