package com.nuvio.app.features.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The Settings top bar's search box, asked for by the remote's Search key while Settings is the
 * active screen. The box always exists; the counter lets a request reach it without a click.
 *
 * The port's App.kt calls into this from its top bar. It lives in its own file on the 1.38.1 test
 * branch because that branch took upstream's SettingsScreen.kt, where the object was inlined and
 * which upstream's router has since reworked, so only this much of it survives; wiring
 * `active`/`focusRequests` back into upstream's DesktopSettingsTopBar is still to be done.
 */
internal object SettingsTopBarSearch {
    internal var focusRequests by mutableIntStateOf(0)
        private set

    /** A spoken search: the words go into the Settings box, not the video search. */
    internal var pendingText by mutableStateOf<String?>(null)

    /** True only while the Settings top bar is composed, so a stale request cannot fire later. */
    internal var active by mutableStateOf(false)

    fun requestFocus(): Boolean {
        if (!active) return false
        focusRequests++
        return true
    }

    /** The remote's Search key, or a voice search with no named scope: search Settings. */
    fun searchFor(query: String?) {
        pendingText = query
        requestFocus()
    }

    fun deactivate() {
        active = false
        focusRequests = 0
    }
}
