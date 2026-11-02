package com.nuvio.app.core.i18n

import com.nuvio.app.features.player.desktop.DesktopHostOs
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

internal actual fun platformString(resource: StringResource): StringResource =
    if (DesktopHostOs.current == DesktopHostOs.LINUX) {
        linuxString(resource, flatpak = !System.getenv("FLATPAK_ID").isNullOrBlank())
    } else {
        resource
    }

/** Linux's own wording, kept in the strings_linux.xml files so upstream's strings.xml stays untouched. */
internal fun linuxString(resource: StringResource, flatpak: Boolean): StringResource = when (resource) {
    Res.string.settings_playback_desktop_audio_channels_desc -> Res.string.linux_audio_channels_desc
    Res.string.settings_playback_desktop_custom_shader_paths_hint -> Res.string.linux_custom_shader_paths_hint
    Res.string.cast_no_devices -> Res.string.linux_cast_no_devices
    Res.string.cast_dolby_vision_needs_ffmpeg ->
        if (flatpak) Res.string.linux_cast_dolby_vision_flatpak else Res.string.linux_cast_dolby_vision_needs_ffmpeg
    Res.string.games_editor_c_games_example_game_exe_a -> Res.string.linux_games_editor_path_example
    Res.string.settings_updates_auto_install_description -> Res.string.linux_updates_auto_install_description
    Res.string.settings_youtube_explainer -> Res.string.linux_settings_youtube_explainer
    else -> resource
}
