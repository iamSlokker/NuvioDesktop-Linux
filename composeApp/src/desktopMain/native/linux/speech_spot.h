#pragma once

// Audio-referenced subtitle sync (DesktopAudioSubtitleSync.kt) samples a few stretches of the
// film's audio. Each is decoded by a throwaway headless mpv over its own connection to the same
// source (same headers, same audio track as the player), audio only, to 16 kHz mono float PCM in
// outputPath; sample 0 is startSeconds. Ported from the Windows bridge's decodeSpeechSpot with the
// same options. Linux passes the headers as mpv's native string array (commas stay inside a
// value), and the caller runs it without any player lock: it blocks for up to 90 s.

#include <mpv/client.h>
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <functional>
#include <string>
#include <utility>
#include <vector>

// Average bytes per second of the source (size over duration), so the sampled stretches can be
// sized to a download budget. Negative when the size is unknown (live, some HLS) and for local
// files, which cost nothing to read.
inline double linuxSpeechSourceBytesPerSecond(const std::string &source, int64_t fileSize, double duration) {
    if (source.rfind("http://", 0) != 0 && source.rfind("https://", 0) != 0) return -1.0;
    return fileSize > 0 && duration > 0.0 ? (double)fileSize / duration : -1.0;
}

// Returns 0 on success, -1 when it cannot start, -2 when the decode did not reach the spot's end.
inline int linuxDecodeSpeechSpot(const std::string &source, const std::vector<std::string> &headers,
                                 const std::string &aid, double startSeconds, double durationSeconds,
                                 const std::string &outputPath, const std::function<bool()> &cancelled) {
    if (source.empty() || outputPath.empty()) return -1;
    mpv_handle *probe = mpv_create();
    if (!probe) return -1;
    const bool numericAid = !aid.empty() && std::all_of(aid.begin(), aid.end(),
        [](unsigned char c) { return std::isdigit(c) != 0; });
    const std::string start = std::to_string(std::max(0.0, startSeconds));
    const std::string end = std::to_string(std::max(0.0, startSeconds) + std::max(1.0, durationSeconds));
    for (const auto &[name, value] : std::vector<std::pair<const char *, std::string>>{
             {"config", "no"}, {"terminal", "no"}, {"load-scripts", "no"}, {"ytdl", "no"},
             {"input-default-bindings", "no"}, {"vid", "no"}, {"sid", "no"}, {"audio-display", "no"},
             {"audio-spdif", ""}, {"af", ""}, {"ao", "pcm"}, {"ao-pcm-waveheader", "no"},
             {"audio-samplerate", "16000"}, {"audio-channels", "mono"}, {"audio-format", "float"},
             // The spot only; nothing read ahead past its end.
             {"cache", "no"}, {"demuxer-readahead-secs", "1"}, {"demuxer-max-bytes", "8MiB"},
             {"hr-seek", "yes"}, {"keep-open", "no"}, {"ao-pcm-file", outputPath},
             {"start", start}, {"end", end}}) {
        mpv_set_option_string(probe, name, value.c_str());
    }
    if (numericAid) mpv_set_option_string(probe, "aid", aid.c_str());
    if (!headers.empty()) {
        std::vector<std::string> owned = headers;
        std::vector<mpv_node> entries(owned.size());
        for (size_t i = 0; i < owned.size(); ++i) {
            entries[i].format = MPV_FORMAT_STRING;
            entries[i].u.string = owned[i].data();
        }
        mpv_node_list list{};
        list.num = (int)entries.size();
        list.values = entries.data();
        mpv_node node{};
        node.format = MPV_FORMAT_NODE_ARRAY;
        node.u.list = &list;
        mpv_set_option(probe, "http-header-fields", MPV_FORMAT_NODE, &node);
    }
    int result = -1;
    if (mpv_initialize(probe) >= 0) {
        const char *load[] = {"loadfile", source.c_str(), nullptr};
        if (mpv_command(probe, load) >= 0) {
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(90);
            while (std::chrono::steady_clock::now() < deadline && !(cancelled && cancelled())) {
                mpv_event *event = mpv_wait_event(probe, 0.25);
                if (event->event_id == MPV_EVENT_SHUTDOWN) break;
                if (event->event_id == MPV_EVENT_END_FILE) {
                    const auto *endFile = static_cast<mpv_event_end_file *>(event->data);
                    result = endFile && endFile->reason == MPV_END_FILE_REASON_EOF ? 0 : -2;
                    break;
                }
            }
        }
    }
    mpv_terminate_destroy(probe);
    return result;
}
