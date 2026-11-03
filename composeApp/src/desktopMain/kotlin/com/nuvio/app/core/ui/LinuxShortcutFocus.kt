package com.nuvio.app.core.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlinx.coroutines.delay

/** How long a screen gets to place its own focus before the root takes it. */
private const val ROOT_FOCUS_DELAY_MS = 300L

/**
 * fork:linux - the app's single-key shortcuts sit in a key handler on the window's root, and Compose only
 * sends a key up from a focused node. When the focused node goes away with nothing taking over, as on the
 * YouTube tab after a mouse user leaves a video, every shortcut does nothing until the next click. The root
 * takes the focus then. Goes after the handler in the chain, so that the handler sees the root's keys.
 */
@Composable
internal fun Modifier.linuxShortcutFocus(): Modifier {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) return this
    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) {
        if (hasFocus) return@LaunchedEffect
        delay(ROOT_FOCUS_DELAY_MS)
        runCatching { requester.requestFocus() }
    }
    return onFocusChanged { hasFocus = it.hasFocus }.focusRequester(requester).focusable()
}
