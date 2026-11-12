package com.nuvio.app.features.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Asks the sign-in page to put focus back after the on-screen keyboard closes.
 *
 * The keyboard is a separate popup window. Closing it returns AWT focus to the main window but can
 * leave Compose's focus owner unset, which makes the page look alive while every D-pad press goes
 * nowhere. The overlay only raises this when the keyboard is explicitly dismissed, not when focus
 * legitimately moves to another control, so it cannot steal focus from a button the person chose.
 */
internal object AuthFocusRestore {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests

    fun request() {
        _requests.tryEmit(Unit)
    }
}
