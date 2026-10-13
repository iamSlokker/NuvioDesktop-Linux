package com.nuvio.app.features.player.desktop

import java.awt.event.KeyEvent

/** EDT-only: consume one Escape gesture before it can reach both AWT and Compose. */
internal class LinuxPlayerEscape(private val os: DesktopHostOs) {
    private var heldKey: Int? = null

    fun reset() { heldKey = null }

    fun dispatch(
        event: KeyEvent,
        ownsWindow: Boolean,
        dismissKey: Int,
        escape: () -> Unit,
    ): Boolean {
        if (os != DesktopHostOs.LINUX || event.isConsumed) return false
        if (!ownsWindow) {
            reset()
            return false
        }
        if (event.id == KeyEvent.KEY_RELEASED && event.keyCode == heldKey) {
            reset()
            event.consume()
            return true
        }
        if (event.id != KeyEvent.KEY_PRESSED ||
            (event.keyCode != KeyEvent.VK_ESCAPE && event.keyCode != dismissKey) ||
            event.isMetaDown || event.isControlDown || event.isAltDown || event.isShiftDown
        ) return false
        // Auto-repeat must not close a panel and then immediately leave playback.
        if (heldKey == null) {
            heldKey = event.keyCode
            escape()
        }
        event.consume()
        return true
    }
}

internal const val LINUX_PLAYER_ESCAPE_SCRIPT = "window.nuvioHandleLinuxEscape && window.nuvioHandleLinuxEscape()"

/** PiP deliberately suspends HUD scripts/messages; use the same Back action directly there. */
internal fun deliverLinuxPlayerEscape(
    handle: Long,
    pictureInPicture: Boolean,
    runJavaScript: (Long, String) -> Unit,
    back: () -> Unit,
) {
    if (handle == 0L || pictureInPicture) back()
    else runJavaScript(handle, LINUX_PLAYER_ESCAPE_SCRIPT)
}
