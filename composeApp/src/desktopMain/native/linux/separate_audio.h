#pragma once

#include <mpv/client.h>
#include <string>
#include <vector>

// fork:youtube-languages - sourceAudioUrl's first line is the audio track; further lines are more
// language tracks of a dubbed YouTube video, "lang<TAB>title<TAB>url" (encodePlayerAudioSources).
inline std::string linuxPrimaryAudioUrl(const std::string &audio) {
    return audio.substr(0, audio.find('\n'));
}

// The primary as it came, each further language as a one-file EDL naming its track. EDL values are
// length-quoted (%n%), so a title's commas or semicolons stay in it.
inline std::vector<std::string> linuxAudioFiles(const std::string &audio) {
    std::vector<std::string> files;
    if (audio.empty()) return files;
    files.push_back(linuxPrimaryAudioUrl(audio));
    const auto quoted = [](const std::string &value) {
        return "%" + std::to_string(value.size()) + "%" + value;
    };
    for (size_t end = audio.find('\n'); end != std::string::npos;) {
        const size_t start = end + 1;
        end = audio.find('\n', start);
        const std::string line = audio.substr(start, end == std::string::npos ? end : end - start);
        const size_t tab1 = line.find('\t');
        const size_t tab2 = tab1 == std::string::npos ? tab1 : line.find('\t', tab1 + 1);
        if (tab2 == std::string::npos) {
            if (!line.empty()) files.push_back(line);
            continue;
        }
        const std::string lang = line.substr(0, tab1), title = line.substr(tab1 + 1, tab2 - tab1 - 1);
        const std::string url = line.substr(tab2 + 1);
        if (url.empty()) continue;
        std::string file = "edl://!track_meta";
        if (!lang.empty()) file += ",lang=" + quoted(lang);
        if (!title.empty()) file += ",title=" + quoted(title);
        files.push_back(file + ";" + quoted(url));
    }
    return files;
}

// mpv titles an external track after the end of its URL (decoded), which is no name for a person to
// read. A further language's EDL names its own, and a local file's name stays useful.
inline std::string linuxTrackTitleOf(const std::string &title, const std::string &externalFilename) {
    const bool url = externalFilename.rfind("http://", 0) == 0 || externalFilename.rfind("https://", 0) == 0;
    return url ? "" : title;
}

// Configure before mpv_initialize/loadfile. List-action suffixes such as
// audio-files-append are CLI/config syntax, not libmpv option names.
inline int setLinuxSeparateAudio(mpv_handle *mpv, const std::string &audioUrl) {
    if (audioUrl.empty()) return 0;
    std::vector<std::string> files = linuxAudioFiles(audioUrl);
    std::vector<mpv_node> entries(files.size());
    for (size_t i = 0; i < files.size(); ++i) {
        entries[i].format = MPV_FORMAT_STRING;
        entries[i].u.string = const_cast<char *>(files[i].c_str());
    }
    mpv_node_list list{};
    list.num = static_cast<int>(entries.size());
    list.values = entries.data();
    mpv_node node{};
    node.format = MPV_FORMAT_NODE_ARRAY;
    node.u.list = &list;
    // A native array keeps URL delimiters intact; libmpv copies the value.
    return mpv_set_option(mpv, "audio-files", MPV_FORMAT_NODE, &node);
}
