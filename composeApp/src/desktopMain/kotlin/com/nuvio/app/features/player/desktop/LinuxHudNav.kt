package com.nuvio.app.features.player.desktop

import java.awt.event.KeyEvent

/**
 * What controls.js reports as `hudNavState` (see its linux-hud-nav block):
 * - [UNAVAILABLE]: an overlay, lock, error or Windows/macOS. Keys keep their normal meaning.
 * - [HIDDEN]: the HUD is hidden. Select reveals it; arrows keep seeking and changing volume.
 * - [VISIBLE]: the HUD is up with no control focused. Arrows and Select drive it.
 * - [FOCUSED]: a control holds nav focus. The bound PlayPause key activates it, so pad A works.
 */
internal object LinuxHudNavState {
    const val UNAVAILABLE = 0
    const val HIDDEN = 1
    const val VISIBLE = 2
    const val FOCUSED = 3

    /** The volume slider is selected: all four arrows change volume, Escape leaves the mode. */
    const val VOLUME_FOCUSED = 4
}

/**
 * Whether the player's AWT KeyEventDispatcher should take arrows, Enter and Escape for the player
 * rather than pass them through to Compose. It owns the keys only while it is the main surface —
 * the video filling the window. In the YouTube mini/corner player, classic Picture-in-Picture, or
 * the browse-in-PiP corner box, the app is being used around the video, so the keys drive the app.
 */
internal fun playerOwnsKeys(
    miniPlayer: Boolean,
    pictureInPicture: Boolean,
    browsePip: Boolean,
): Boolean = !miniPlayer && !pictureInPicture && !browsePip

/** Where focus sits as the YouTube watch page and its HUD trade keys. */
internal enum class YouTubeWatchFocusLocation { HudBottomRow, PageFirstControl }

/**
 * The location a Down or Up press moves focus to across the HUD/page boundary on the YouTube watch
 * page, or null when the press has no cross-boundary meaning. Down on the HUD's bottom row goes to
 * the first watch-page control under the video; Up from that control returns to the HUD.
 */
internal fun youTubeWatchFocusAfter(
    location: YouTubeWatchFocusLocation,
    keyCode: Int,
    keys: HudNavKeyBindings,
): YouTubeWatchFocusLocation? = when {
    location == YouTubeWatchFocusLocation.HudBottomRow && keyCode == keys.down ->
        YouTubeWatchFocusLocation.PageFirstControl
    location == YouTubeWatchFocusLocation.PageFirstControl && keyCode == keys.up ->
        YouTubeWatchFocusLocation.HudBottomRow
    else -> null
}

/**
 * The player's bound navigation keys, as AWT key codes. Every one of these is a rebindable action
 * (Select, PlayPause, Dismiss/Close and the four Move directions), so the player honours the same
 * bindings as the rest of the app: the key is resolved here, and controls.js/mpv still receive only
 * the fixed key names they know ("Escape", "Enter", "ArrowLeft", ...).
 */
internal data class HudNavKeyBindings(
    val select: Int,
    val playPause: Int,
    val dismiss: Int,
    val up: Int,
    val down: Int,
    val left: Int,
    val right: Int,
) {
    companion object {
        /** Reads the live bindings; call per event so a rebind takes effect without a restart. */
        fun current(): HudNavKeyBindings = HudNavKeyBindings(
            select = com.nuvio.app.features.player.AppShortcutsRepository.keyCode(
                com.nuvio.app.features.player.AppShortcutAction.SelectFocused,
            ),
            playPause = com.nuvio.app.features.player.PlayerShortcutsRepository.keyCode(
                com.nuvio.app.features.player.PlayerShortcutAction.PlayPause,
            ),
            dismiss = com.nuvio.app.features.player.AppShortcutsRepository.keyCode(
                com.nuvio.app.features.player.AppShortcutAction.DismissOverlay,
            ),
            up = com.nuvio.app.features.player.AppShortcutsRepository.keyCode(
                com.nuvio.app.features.player.AppShortcutAction.MoveUp,
            ),
            down = com.nuvio.app.features.player.AppShortcutsRepository.keyCode(
                com.nuvio.app.features.player.AppShortcutAction.MoveDown,
            ),
            left = com.nuvio.app.features.player.AppShortcutsRepository.keyCode(
                com.nuvio.app.features.player.AppShortcutAction.MoveLeft,
            ),
            right = com.nuvio.app.features.player.AppShortcutsRepository.keyCode(
                com.nuvio.app.features.player.AppShortcutAction.MoveRight,
            ),
        )
    }
}

/**
 * The key name controls.js expects for [keyCode] under [state], or null when the key keeps its
 * normal meaning. Every key this answers for is a bound one, so rebinding it is honored.
 */
internal fun linuxHudNavKey(
    keyCode: Int,
    state: Int,
    keys: HudNavKeyBindings,
): String? {
    val selectKeyCode = keys.select
    val playPauseKeyCode = keys.playPause
    val dismissKeyCode = keys.dismiss
    if (state == LinuxHudNavState.UNAVAILABLE) return null
    // Only the volume slider's selected mode takes Escape from the player's normal Back handling:
    // it leaves the slider so the arrows navigate the HUD again.
    if (state == LinuxHudNavState.VOLUME_FOCUSED && keyCode == dismissKeyCode) return "Escape"
    val arrow = when (keyCode) {
        keys.left -> "ArrowLeft"
        keys.right -> "ArrowRight"
        keys.up -> "ArrowUp"
        keys.down -> "ArrowDown"
        else -> null
    }
    return when {
        arrow != null -> arrow.takeIf { state >= LinuxHudNavState.VISIBLE }
        keyCode == selectKeyCode -> "Enter"
        keyCode == playPauseKeyCode && state == LinuxHudNavState.FOCUSED -> "Enter"
        else -> null
    }
}

/**
 * The player command an Up or Down press sends while the HUD is hidden or the volume slider has no
 * nav focus, or null when the press does nothing. With the "Player volume control" setting off the
 * keys do nothing, so they neither change volume nor reach the player beneath.
 */
internal fun linuxVolumeKeyType(keyCode: Int, volumeControlEnabled: Boolean): String? {
    if (!volumeControlEnabled) return null
    return when (keyCode) {
        KeyEvent.VK_UP -> "volumeUp"
        KeyEvent.VK_DOWN -> "volumeDown"
        else -> null
    }
}

/**
 * Lets a held Enter-class key activate once per press. AWT repeats a held key as a run of
 * KEY_PRESSED events with no release in between, so a press whose key is already held is a repeat.
 */
internal class LinuxHudSelectRepeatGuard {
    private val held = HashSet<Int>()

    /** True when this press should be forwarded; false for a repeat of a key already held. */
    fun accept(keyCode: Int): Boolean = held.add(keyCode)

    fun release(keyCode: Int) {
        held.remove(keyCode)
    }
}
