// The Windows subtitle rules (subtitle_style.h), then real libmpv with an SRT and an ASS file: mpv
// reports each selected codec, the observed property fires on a track switch, and sub-scale only
// reaches the ASS track. Null outputs; never creates a window or leaves the temp directory.
#include "subtitle_style.h"
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <stdexcept>
#include <unistd.h>

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const char *operation) {
    require(result >= 0, std::string(operation) + ": " + mpv_error_string(result));
}
std::string property(mpv_handle *mpv, const char *name) {
    char *value = mpv_get_property_string(mpv, name);
    require(value != nullptr, std::string("get ") + name);
    std::string result(value);
    mpv_free(value);
    return result;
}

void assModeRules() {
    LinuxSubtitleAssMode state;
    const auto first = state.refresh("");
    require(first && first->mode == "force" && first->scale == 1.0, "no track starts at force, scale 1");
    require(!state.refresh("subrip"), "plain text after plain text writes nothing");
    const auto ass = state.refresh("ass");
    require(ass && ass->mode == "no" && ass->scale == 1.0, "ASS defaults to the file's own styling");
    require(state.request("scale", 1.5), "new request");
    const auto scaled = state.refresh("ass", true);
    require(scaled && scaled->mode == "scale" && scaled->scale == 1.5, "request reaches an ASS track");
    const auto srt = state.refresh("subrip");
    require(srt && srt->mode == "force" && srt->scale == 1.0, "the ASS size never reaches an SRT track");
    const auto back = state.refresh("ssa");
    require(back && back->mode == "scale" && back->scale == 1.5, "and comes back on the next ASS track");
    require(!state.request("bogus", 1.0), "unknown mode refused");
    require(!state.request("scale", 1.5), "same request is not news");
    require(state.request("force", 99.0), "scale is clamped, not refused");
    const auto clamped = state.refresh("ass", true);
    require(clamped && clamped->scale == 5.0, "scale ceiling");
    require(linuxSubtitleCodecIsAss("ass") && linuxSubtitleCodecIsAss("ssa"), "ASS codecs");
    for (const char *codec : {"subrip", "webvtt", "mov_text", "hdmv_pgs_subtitle", "dvd_subtitle", ""})
        require(!linuxSubtitleCodecIsAss(codec), std::string("not ASS: ") + codec);
}

void styleRules() {
    const auto map = [](const LinuxSubtitleStyle &style) {
        const auto list = linuxSubtitleStyleProperties(style);
        return std::map<std::string, std::string>(list.begin(), list.end());
    };
    auto empty = map(LinuxSubtitleStyle{});
    require(empty["sub-font"] == "sans-serif", "empty font is sans-serif");
    require(empty["sub-color"] == "#FFFFFFFF" && empty["sub-back-color"] == "#00000000" &&
        empty["sub-outline-color"] == "#FF000000", "empty colours fall back");
    require(empty["sub-border-style"] == "opaque-box", "empty background keeps Windows' border choice");
    require(std::stod(empty["sub-font-size"]) == 18.0, "size floor");

    LinuxSubtitleStyle style;
    style.font = "Noto Sans";
    style.background = "#80000000";
    style.outlineSize = 99.0;
    style.fontSize = 500.0;
    style.position = 400;
    style.bold = true;
    auto high = map(style);
    require(high["sub-font"] == "Noto Sans" && high["sub-bold"] == "yes", "values pass through");
    require(high["sub-border-style"] == "opaque-box", "a visible background is a box");
    require(std::stod(high["sub-outline-size"]) == 8.0, "outline ceiling");
    require(std::stod(high["sub-font-size"]) == 96.0, "size ceiling");
    require(high["sub-pos"] == "150", "position ceiling");
    style.position = -3;
    style.outlineSize = -1.0;
    style.background = "#00112233";
    auto low = map(style);
    require(low["sub-pos"] == "0" && std::stod(low["sub-outline-size"]) == 0.0, "lower limits");
    require(low["sub-border-style"] == "outline-and-shadow", "a transparent background is an outline");
}

struct Media {
    std::filesystem::path directory;
    Media() {
        directory = std::filesystem::temp_directory_path() / ("nuvio-subtitle-style-" + std::to_string(::getpid()));
        std::filesystem::create_directories(directory);
        std::ofstream video(directory / "video.y4m", std::ios::binary);
        video << "YUV4MPEG2 W2 H2 F25:1 Ip A1:1 C420jpeg\n";
        for (int frame = 0; frame < 50; ++frame) video << "FRAME\n" << std::string(6, '\x40');
        std::ofstream(directory / "plain.srt") << "1\n00:00:00,000 --> 00:00:02,000\nplain\n";
        std::ofstream(directory / "styled.ass")
            << "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n"
            << "Format: Name, Fontname, Fontsize\nStyle: Default,Arial,20\n\n[Events]\n"
            << "Format: Layer, Start, End, Style, Text\nDialogue: 0,0:00:00.00,0:00:02.00,Default,styled\n";
    }
    ~Media() { std::error_code ignored; std::filesystem::remove_all(directory, ignored); }
};

// Drains events until the observed codec reads expected; the observation is what the bridge acts on.
void waitForCodec(mpv_handle *mpv, LinuxSubtitleAssMode &state, const std::string &expected) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (std::chrono::steady_clock::now() < deadline) {
        auto *event = mpv_wait_event(mpv, 0.1);
        if (event->event_id == MPV_EVENT_PROPERTY_CHANGE && event->reply_userdata == 7) {
            linuxRefreshSubtitleAssMode(mpv, state);
            if (linuxSelectedSubtitleCodec(mpv) == expected) return;
        }
    }
    throw std::runtime_error("no observation of codec '" + expected + "'");
}

void realLibmpv() {
    Media media;
    std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)> player(mpv_create(), mpv_terminate_destroy);
    mpv_handle *mpv = player.get();
    require(mpv != nullptr, "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"}, {"vo", "null"}, {"ao", "null"},
             {"terminal", "no"}, {"pause", "yes"}, {"idle", "yes"}, {"keep-open", "yes"}, {"sid", "no"}})
        check(mpv_set_option_string(mpv, name, value), name);
    check(mpv_initialize(mpv), "initialize");
    check(mpv_observe_property(mpv, 7, "current-tracks/sub/codec", MPV_FORMAT_STRING), "observe");
    const auto video = (media.directory / "video.y4m").string();
    const char *load[] = {"loadfile", video.c_str(), nullptr};
    check(mpv_command(mpv, load), "loadfile");
    for (auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);;) {
        require(std::chrono::steady_clock::now() < deadline, "file did not load");
        if (mpv_wait_event(mpv, 0.1)->event_id == MPV_EVENT_FILE_LOADED) break;
    }
    require(property(mpv, "sid") == "no", "sid=no holds after load");

    LinuxSubtitleAssMode state;
    require(state.request("scale", 2.0), "request");
    linuxRefreshSubtitleAssMode(mpv, state, true);
    require(property(mpv, "sub-ass-override") == "force", "no track is force");
    require(std::stod(property(mpv, "sub-scale")) == 1.0, "no track has scale 1");

    const auto srt = (media.directory / "plain.srt").string();
    const char *addSrt[] = {"sub-add", srt.c_str(), "select", nullptr};
    check(mpv_command(mpv, addSrt), "add srt");
    waitForCodec(mpv, state, "subrip");
    require(property(mpv, "sub-ass-override") == "force", "SRT is force");
    require(std::stod(property(mpv, "sub-scale")) == 1.0, "SRT is not rescaled");

    const auto ass = (media.directory / "styled.ass").string();
    const char *addAss[] = {"sub-add", ass.c_str(), "select", nullptr};
    check(mpv_command(mpv, addAss), "add ass");
    waitForCodec(mpv, state, "ass");
    require(property(mpv, "sub-ass-override") == "scale", "ASS takes the requested level");
    require(std::stod(property(mpv, "sub-scale")) == 2.0, "ASS takes the requested size");

    check(mpv_set_property_string(mpv, "sid", "1"), "back to the SRT");
    waitForCodec(mpv, state, "subrip");
    require(property(mpv, "sub-ass-override") == "force" && std::stod(property(mpv, "sub-scale")) == 1.0,
        "a track switch restores plain text");

    LinuxSubtitleStyle style;
    for (const auto &[name, value] : linuxSubtitleStyleProperties(style))
        check(mpv_set_property_string(mpv, name.c_str(), value.c_str()), name.c_str());
    require(property(mpv, "sub-font") == "sans-serif", "mpv keeps the fallback font");
}
}

int main() {
    try {
        assModeRules();
        std::cout << "PASS override level and size follow the selected codec\n";
        styleRules();
        std::cout << "PASS style fallbacks and limits\n";
        realLibmpv();
        std::cout << "PASS real libmpv: SRT stays at force and scale 1, ASS takes the request\n";
        std::cout << "SUBTITLE_STYLE_PASS\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
