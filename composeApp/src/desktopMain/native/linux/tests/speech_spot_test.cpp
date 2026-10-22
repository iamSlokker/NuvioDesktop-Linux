// speech_spot.h against real libmpv: a generated 48 kHz stereo WAV decoded to 16 kHz mono float.
// Null outputs only: never creates a window, initializes GTK/JNI, accesses the network, or opens
// an audio device (ao=pcm writes a file).
#include "speech_spot.h"
#include <cmath>
#include <cstring>
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

// 10 s of a 440 Hz tone, PCM16 48 kHz stereo.
void writeWav(const std::filesystem::path &path) {
    const uint32_t rate = 48000, seconds = 10, channels = 2, frames = rate * seconds;
    std::ofstream out(path, std::ios::binary);
    out.write("RIFF", 4);
    put32(out, 36 + frames * channels * 2);
    out.write("WAVEfmt ", 8);
    put32(out, 16); put16(out, 1); put16(out, channels); put32(out, rate);
    put32(out, rate * channels * 2); put16(out, channels * 2); put16(out, 16);
    out.write("data", 4);
    put32(out, frames * channels * 2);
    for (uint32_t i = 0; i < frames; ++i) {
        const auto sample = (int16_t)std::lround(12000.0 * std::sin(2.0 * M_PI * 440.0 * i / rate));
        for (uint32_t c = 0; c < channels; ++c) out.write(reinterpret_cast<const char *>(&sample), 2);
    }
    require(bool(out), "write test WAV");
}
} // namespace

int main() {
    try {
        require(linuxSpeechSourceBytesPerSecond("https://host/a.mkv", 6000000000LL, 6000.0) == 1000000.0,
                "bytes per second of a remote source");
        require(linuxSpeechSourceBytesPerSecond("/media/a.mkv", 6000000000LL, 6000.0) < 0, "local files cost nothing");
        require(linuxSpeechSourceBytesPerSecond("https://host/live.m3u8", -1, 0.0) < 0, "unknown size");

        const auto dir = std::filesystem::temp_directory_path() / "nuvio-speech-spot-test";
        std::filesystem::remove_all(dir);
        std::filesystem::create_directories(dir);
        const auto wav = dir / "tone.wav";
        const auto pcm = dir / "spot.f32";
        writeWav(wav);

        require(linuxDecodeSpeechSpot(wav.string(), {}, "", 3.0, 2.0, pcm.string(), [] { return true; }) == -1,
                "a cancelled decode reports failure");
        const int result = linuxDecodeSpeechSpot(wav.string(), {"X-Test: a, b"}, "1", 3.0, 2.0, pcm.string(), nullptr);
        require(result == 0, "decode result " + std::to_string(result));
        const auto bytes = std::filesystem::file_size(pcm);
        // 2 s of 16 kHz mono float: 128000 bytes, give or take one audio frame at each end.
        require(bytes > 120000 && bytes < 136000, "decoded size " + std::to_string(bytes));
        std::vector<float> samples(bytes / sizeof(float));
        std::ifstream(pcm, std::ios::binary).read(reinterpret_cast<char *>(samples.data()), bytes);
        double peak = 0.0;
        for (float sample : samples) peak = std::max(peak, (double)std::fabs(sample));
        // 12000/32768 = 0.366 per channel; the mono downmix keeps the tone well above silence.
        require(peak > 0.2 && peak < 1.0, "decoded peak " + std::to_string(peak));
        std::filesystem::remove_all(dir);
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
    std::cout << "SPEECH_SPOT_PASS\n";
    return 0;
}
