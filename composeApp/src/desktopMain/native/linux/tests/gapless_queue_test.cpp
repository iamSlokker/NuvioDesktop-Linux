// gapless_queue.h against real libmpv: two generated WAVs played back to back in one mpv.
// Null outputs only: never creates a window, initializes GTK/JNI, accesses the network, or opens
// an audio device (ao=null, vo=null).
#include "gapless_queue.h"
#include <chrono>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

void put16(std::ofstream &out, uint16_t value) { out.write(reinterpret_cast<const char *>(&value), 2); }
void put32(std::ofstream &out, uint32_t value) { out.write(reinterpret_cast<const char *>(&value), 4); }

// One second of a tone, PCM16 48 kHz mono.
void writeWav(const std::filesystem::path &path, double hz) {
    const uint32_t rate = 48000, frames = rate;
    std::ofstream out(path, std::ios::binary);
    out.write("RIFF", 4);
    put32(out, 36 + frames * 2);
    out.write("WAVEfmt ", 8);
    put32(out, 16); put16(out, 1); put16(out, 1); put32(out, rate);
    put32(out, rate * 2); put16(out, 2); put16(out, 16);
    out.write("data", 4);
    put32(out, frames * 2);
    for (uint32_t i = 0; i < frames; ++i) {
        const auto sample = (int16_t)std::lround(8000.0 * std::sin(2.0 * M_PI * hz * i / rate));
        out.write(reinterpret_cast<const char *>(&sample), 2);
    }
    require(bool(out), "write test WAV");
}

int64_t playlistPos(mpv_handle *mpv) {
    int64_t value = -1;
    mpv_get_property(mpv, "playlist-pos", MPV_FORMAT_INT64, &value);
    return value;
}
} // namespace

int main() {
    try {
        const auto plain = linuxGaplessPerFileOptions({}, {"af=volume=-3dB", "start=0", "no-equals"});
        require(plain == "af=%11%volume=-3dB,start=%1%0", "options are length-quoted, a bare word is skipped: " + plain);
        const auto headers = linuxGaplessPerFileOptions({"Cookie: a=1, b=2", "X-Path: c\\d"}, {});
        require(headers == "http-header-fields=%30%Cookie: a=1\\, b=2,X-Path: c\\\\d",
                "header commas and backslashes are escaped inside one quoted list: " + headers);

        const auto dir = std::filesystem::temp_directory_path() / "nuvio-gapless-queue-test";
        std::filesystem::remove_all(dir);
        std::filesystem::create_directories(dir);
        const auto first = dir / "first.wav", second = dir / "second.wav";
        writeWav(first, 440.0);
        writeWav(second, 660.0);

        mpv_handle *mpv = mpv_create();
        require(mpv != nullptr, "mpv_create");
        for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
                 {"config", "no"}, {"terminal", "no"}, {"load-scripts", "no"}, {"ytdl", "no"},
                 {"vo", "null"}, {"ao", "null"}, {"keep-open", "yes"}, {"idle", "yes"}}) {
            mpv_set_option_string(mpv, name, value);
        }
        require(mpv_initialize(mpv) >= 0, "mpv_initialize");
        const char *load[] = {"loadfile", first.c_str(), nullptr};
        require(mpv_command(mpv, load) >= 0, "loadfile");

        LinuxGaplessQueue queue;
        require(!queue.takesEof(), "with nothing queued an EOF is the end of playback");
        require(queue.queue(mpv, second.string(), {}, {"start=0"}) >= 0, "queue the second file");
        require(queue.queued(), "queued");

        int loaded = 0, eofs = 0, takenEofs = 0;
        int64_t secondPos = -1;
        bool idle = false;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
        while (std::chrono::steady_clock::now() < deadline && !idle) {
            mpv_event *event = mpv_wait_event(mpv, 0.25);
            if (event->event_id == MPV_EVENT_FILE_LOADED) {
                ++loaded;
                if (loaded == 2) secondPos = playlistPos(mpv);
            } else if (event->event_id == MPV_EVENT_END_FILE) {
                const auto *end = static_cast<mpv_event_end_file *>(event->data);
                if (end && end->reason == MPV_END_FILE_REASON_EOF) {
                    ++eofs;
                    if (queue.takesEof()) ++takenEofs;
                }
            } else if (event->event_id == MPV_EVENT_PROPERTY_CHANGE || event->event_id == MPV_EVENT_NONE) {
                // keep-open holds the last file at its end: eof-reached says playback is over.
                int eof = 0;
                if (loaded == 2 && mpv_get_property(mpv, "eof-reached", MPV_FORMAT_FLAG, &eof) >= 0 && eof) idle = true;
            }
        }
        mpv_terminate_destroy(mpv);
        std::filesystem::remove_all(dir);

        require(loaded == 2, "both files loaded in the one player (" + std::to_string(loaded) + ")");
        require(secondPos == 1, "the queued file plays as playlist entry 1 (" + std::to_string(secondPos) + ")");
        require(eofs == 1 && takenEofs == 1, "the move to the queued file is the one EOF, and it is taken (" +
                std::to_string(eofs) + "/" + std::to_string(takenEofs) + ")");
        require(idle, "the last file ends at eof-reached, which is the real end");

        LinuxGaplessQueue cleared;
        mpv_handle *other = mpv_create();
        mpv_set_option_string(other, "vo", "null");
        mpv_set_option_string(other, "ao", "null");
        require(mpv_initialize(other) >= 0, "mpv_initialize (clear)");
        require(cleared.queue(other, "", {}, {}) == 0 && !cleared.queued(), "an empty url only drops the queue");
        mpv_terminate_destroy(other);

        std::cout << "GAPLESS_QUEUE_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "GAPLESS_QUEUE_FAIL: " << error.what() << "\n";
        return 1;
    }
}
