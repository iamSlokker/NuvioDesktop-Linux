package com.nuvio.app.features.input

/** A hardware transport key, in the order the desktop media controls expect. */
internal enum class DesktopMediaKeyAction(val gateName: String) {
    PlayPause("playpause"),
    Next("next"),
    Previous("previous"),
}

// AWT does not expose constants for these keys; these are their X11/Windows codes.
// Fast Forward/Rewind have no AWT key code here: desktops deliver them to MPRIS as a large
// relative Seek, which LinuxMusicMpris maps to next/previous.
private const val MEDIA_KEY_NEXT_TRACK = 0xB0
private const val MEDIA_KEY_PREV_TRACK = 0xB1
private const val MEDIA_KEY_PLAY_PAUSE = 0xB3

internal fun desktopMediaKeyAction(keyCode: Int): DesktopMediaKeyAction? = when (keyCode) {
    MEDIA_KEY_PLAY_PAUSE -> DesktopMediaKeyAction.PlayPause
    MEDIA_KEY_NEXT_TRACK -> DesktopMediaKeyAction.Next
    MEDIA_KEY_PREV_TRACK -> DesktopMediaKeyAction.Previous
    else -> null
}
