package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage

/**
 * The HUD's own preferences on Linux: time remaining, close menu after selecting, source panel width.
 * The page keeps them in localStorage, which Windows' WebView2 profile keeps on disk but Linux's
 * ephemeral WebKit context loses with every player. A persistent context would also keep cookies and
 * the disk cache of the remote images the HUD loads, so only these values are kept, here. The page's
 * code is unchanged: its fork:linux block seeds localStorage from the URL and reports each write.
 */
internal object LinuxHudPreferences {
    const val EVENT_PREFIX = "linuxHudPref_"
    private const val REMAINING_TIME = "nuvioShowRemainingTime"
    private const val CLOSE_MENU = "nuvioContextMenuCloseOnSelect"
    private const val PANEL_WIDTH = "nuvioSourcePanelWidth"
    private val KEYS = listOf(REMAINING_TIME, CLOSE_MENU, PANEL_WIDTH)

    private val store by lazy { DesktopStorage.store("nuvio_linux_hud") }

    /** The URL parameter that carries the saved values; empty values still turn the reporting on. */
    fun urlParameter(): String = "linuxHudPrefs=" + encode(KEYS.mapNotNull { key -> store.getString(key)?.let { key to it } })

    fun record(type: String, value: Double) {
        val key = type.removePrefix(EVENT_PREFIX)
        if (key !in KEYS) return
        store.putString(key, stored(key, value))
    }

    /** `key:value,key:value`: keys are fixed, and values are only ever true, false or a number. */
    internal fun encode(values: List<Pair<String, String>>): String =
        values.joinToString(",") { (key, value) -> "$key:$value" }

    /** What to keep for a reported write, or null to forget it: the page sends -1 for a removal. */
    internal fun stored(key: String, value: Double): String? = when {
        !value.isFinite() || value < 0.0 -> null
        key == PANEL_WIDTH -> value.toLong().toString()
        else -> (value != 0.0).toString()
    }
}
