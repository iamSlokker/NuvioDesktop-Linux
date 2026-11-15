package com.nuvio.app.features.music

/**
 * The desktop can deliver a media key twice: once through MPRIS (the desktop routes the key to
 * the published player) and once as an AWT key event when it is not consumed. Only the first is
 * acted on; the other is treated as the same press within [WINDOW_MS].
 *
 * The window is tracked per action: a quick Fast Rewind followed by Fast Forward is two keys, not
 * a duplicate, while two deliveries of the same key are one press.
 */
internal object MusicMediaKeyGate {
    private const val WINDOW_MS = 300L
    private val lastAt = mutableMapOf<String, Long>()

    fun claim(action: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val previous = lastAt[action]
        if (previous != null && nowMs - previous < WINDOW_MS) return false
        lastAt[action] = nowMs
        return true
    }
}
