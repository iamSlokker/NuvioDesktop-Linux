package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.PlayerShortcutAction
import java.awt.event.KeyEvent

/*
 * Input for the experimental Linux HDR session. mpv owns the focused Wayland window, so
 * keys and mouse arrive there; each bound key is forwarded once as an application code
 * (an AWT VK_ code, or a negative session-only code) and resolved below against the same
 * user shortcut bindings as the embedded player. The AWT dispatcher never sees these
 * keys while the HDR window has focus, so every press is handled exactly once.
 */

/** Session-only code: leave the HDR session and continue in the embedded player. */
internal const val LINUX_HDR_KEY_LEAVE = -100

/** mpv input names bound in the HDR window, with the application code each one forwards. */
internal val linuxHdrMpvKeyBindings: List<Pair<String, Int>> = buildList {
    ('a'..'z').forEach { add(it.toString() to KeyEvent.VK_A + (it - 'a')) }
    ('0'..'9').forEach { add(it.toString() to KeyEvent.VK_0 + (it - '0')) }
    (1..12).forEach { add("F$it" to KeyEvent.VK_F1 + it - 1) }
    addAll(
        listOf(
            "SPACE" to KeyEvent.VK_SPACE,
            "ESC" to KeyEvent.VK_ESCAPE,
            "ENTER" to KeyEvent.VK_ENTER,
            "KP_ENTER" to KeyEvent.VK_ENTER,
            "LEFT" to KeyEvent.VK_LEFT,
            "RIGHT" to KeyEvent.VK_RIGHT,
            "UP" to KeyEvent.VK_UP,
            "DOWN" to KeyEvent.VK_DOWN,
            "BS" to KeyEvent.VK_BACK_SPACE,
            "TAB" to KeyEvent.VK_TAB,
            "PGUP" to KeyEvent.VK_PAGE_UP,
            "PGDWN" to KeyEvent.VK_PAGE_DOWN,
            "HOME" to KeyEvent.VK_HOME,
            "END" to KeyEvent.VK_END,
            "DEL" to KeyEvent.VK_DELETE,
            "INS" to KeyEvent.VK_INSERT,
            "[" to KeyEvent.VK_OPEN_BRACKET,
            "]" to KeyEvent.VK_CLOSE_BRACKET,
            "," to KeyEvent.VK_COMMA,
            "." to KeyEvent.VK_PERIOD,
            "-" to KeyEvent.VK_MINUS,
            "=" to KeyEvent.VK_EQUALS,
            "/" to KeyEvent.VK_SLASH,
            ";" to KeyEvent.VK_SEMICOLON,
            // Mouse: double click is the familiar "leave fullscreen" gesture; right click
            // and the wheel follow mpv's own defaults (pause, volume).
            "MBTN_LEFT_DBL" to LINUX_HDR_KEY_LEAVE,
            "MBTN_RIGHT" to KeyEvent.VK_SPACE,
            "MBTN_BACK" to KeyEvent.VK_ESCAPE,
            "WHEEL_UP" to KeyEvent.VK_UP,
            "WHEEL_DOWN" to KeyEvent.VK_DOWN,
            // A compositor/user close of the HDR window returns to the embedded player
            // instead of quitting mpv (its unbound default).
            "CLOSE_WIN" to LINUX_HDR_KEY_LEAVE,
        ),
    )
    // Media keys are deliberately not bound: desktops deliver them through MPRIS, which
    // already drives this player; binding them here could act twice.
}

/** Wire format consumed by the native binding installer (split at the last '='). */
internal fun linuxHdrMpvKeyBindingEntries(): Array<String> =
    linuxHdrMpvKeyBindings.map { (name, code) -> "$name=$code" }.toTypedArray()

internal sealed interface LinuxHdrInputAction {
    /** An existing controller/engine event, dispatched exactly like the AWT key path. */
    data class Event(val type: String, val value: Double = 1.0) : LinuxHdrInputAction
    data object Back : LinuxHdrInputAction
    data object LeaveHdr : LinuxHdrInputAction
    data object CycleHdr : LinuxHdrInputAction
    data object SkipInterval : LinuxHdrInputAction
    data object ToggleDiagnostics : LinuxHdrInputAction
    /** A shortcut whose feature needs the embedded player/HUD (SDR grading, SVP, shaders). */
    data class Unavailable(val label: String) : LinuxHdrInputAction
    data object Ignored : LinuxHdrInputAction
}

/**
 * Mirrors the embedded player's KeyEventDispatcher (PlayerEngine.desktop.kt): fixed
 * directional keys, then rebindable player shortcuts. Panels need the WebKit HUD, which
 * this session deliberately lacks, so they leave the HDR session instead.
 */
internal fun linuxHdrInputAction(
    code: Int,
    playerAction: (Int) -> PlayerShortcutAction?,
    appKey: (AppShortcutAction) -> Int,
): LinuxHdrInputAction {
    if (code == LINUX_HDR_KEY_LEAVE) return LinuxHdrInputAction.LeaveHdr
    if (code == KeyEvent.VK_ESCAPE || code == appKey(AppShortcutAction.DismissOverlay) ||
        code == appKey(AppShortcutAction.GoBack)
    ) return LinuxHdrInputAction.Back
    if (code == appKey(AppShortcutAction.ToggleFullscreen)) return LinuxHdrInputAction.LeaveHdr
    when (code) {
        KeyEvent.VK_LEFT -> return LinuxHdrInputAction.Event("keyboardSeekBack")
        KeyEvent.VK_RIGHT -> return LinuxHdrInputAction.Event("keyboardSeekForward")
        KeyEvent.VK_UP -> return LinuxHdrInputAction.Event("volumeUp")
        KeyEvent.VK_DOWN -> return LinuxHdrInputAction.Event("volumeDown")
    }
    val action = playerAction(code)
    if (action == null) {
        // Select/OK on a remote or keyboard toggles playback when no panel can be open.
        return if (code == appKey(AppShortcutAction.SelectFocused)) LinuxHdrInputAction.Event("keyboardToggle")
        else LinuxHdrInputAction.Ignored
    }
    return when (action) {
        PlayerShortcutAction.PlayPause,
        PlayerShortcutAction.AlternatePlayPause -> LinuxHdrInputAction.Event("keyboardToggle")
        PlayerShortcutAction.ToggleMute -> LinuxHdrInputAction.Event("keyboardToggleMute")
        PlayerShortcutAction.SeekBackward -> LinuxHdrInputAction.Event("keyboardSeekBack")
        PlayerShortcutAction.SeekForward -> LinuxHdrInputAction.Event("keyboardSeekForward")
        PlayerShortcutAction.SpeedUp -> LinuxHdrInputAction.Event("keyboardSpeedStep", 1.0)
        PlayerShortcutAction.SpeedDown -> LinuxHdrInputAction.Event("keyboardSpeedStep", -1.0)
        PlayerShortcutAction.ToggleSpeed -> LinuxHdrInputAction.Event("keyboardSpeedToggle")
        PlayerShortcutAction.NextSubtitle -> LinuxHdrInputAction.Event("keyboardNextSubtitle")
        PlayerShortcutAction.NextAudio -> LinuxHdrInputAction.Event("keyboardNextAudio")
        PlayerShortcutAction.CycleZoom -> LinuxHdrInputAction.Event("resize")
        PlayerShortcutAction.OpenSources,
        PlayerShortcutAction.OpenEpisodes -> LinuxHdrInputAction.LeaveHdr
        PlayerShortcutAction.SkipInterval -> LinuxHdrInputAction.SkipInterval
        PlayerShortcutAction.CycleHdr -> LinuxHdrInputAction.CycleHdr
        PlayerShortcutAction.ToggleMpvDiagnostics -> LinuxHdrInputAction.ToggleDiagnostics
        PlayerShortcutAction.CycleSvp,
        PlayerShortcutAction.CycleColorProfile,
        PlayerShortcutAction.CycleAnime -> LinuxHdrInputAction.Unavailable(action.displayName)
    }
}

/**
 * Gamepad input normally becomes synthetic AWT key events addressed to the focused Nuvio
 * window. While the HDR session's own Wayland window has focus no AWT window does, so the
 * injector hands the key code here instead. Only an active HDR session installs a sink.
 */
internal object LinuxHdrInputRoute {
    @Volatile
    private var sink: ((Int) -> Boolean)? = null

    fun install(value: (Int) -> Boolean) {
        sink = value
    }

    fun clear(value: (Int) -> Boolean) {
        if (sink === value) sink = null
    }

    /** True when an active HDR session accepted [keyCode]. */
    fun deliver(keyCode: Int): Boolean = sink?.invoke(keyCode) ?: false
}
