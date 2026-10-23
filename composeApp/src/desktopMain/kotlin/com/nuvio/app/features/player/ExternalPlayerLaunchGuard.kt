package com.nuvio.app.features.player

/**
 * Stops an accidental second activation (a double click, or Enter pressed twice) from opening the
 * same source in a second external player. A repeat is ignored only while the player started for
 * that source is still running and was started moments ago; another source, a closed player, or a
 * player that handed the file to an already-open window (and so exited) always launches.
 */
internal class ExternalPlayerLaunchGuard(
    private val windowMillis: Long = 30_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Launch(val source: String, val startedAtMillis: Long, val isAlive: () -> Boolean)

    private var last: Launch? = null

    @Synchronized
    fun isRepeat(source: String): Boolean {
        val launch = last ?: return false
        return launch.source == source &&
            nowMillis() - launch.startedAtMillis < windowMillis &&
            launch.isAlive()
    }

    @Synchronized
    fun launched(source: String, isAlive: () -> Boolean) {
        last = Launch(source, nowMillis(), isAlive)
    }
}
