package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.nuvio.app.features.player.dismissesOverlay

/**
 * The transient panels currently up, innermost last, so a Back request can close the top one before
 * it reaches the screen behind it. A desktop `Dialog` only maps Escape through its own navigation
 * event input, while a pad's B button and Backspace arrive as ordinary key events elsewhere; the
 * screens that own a Back ladder ask this registry first, which covers every key that means "back".
 */
object DialogBackRegistry {
    private data class Entry(val owner: Any, val dismiss: () -> Unit)

    private val entries = mutableListOf<Entry>()

    fun register(owner: Any, dismiss: () -> Unit) {
        entries.removeAll { it.owner === owner }
        entries += Entry(owner, dismiss)
    }

    fun unregister(owner: Any) {
        entries.removeAll { it.owner === owner }
    }

    /** Dismisses the innermost panel, if one is up. False when Back belongs to the screen instead. */
    fun dismissTop(): Boolean {
        val entry = entries.lastOrNull() ?: return false
        entries.remove(entry)
        runCatching { entry.dismiss() }
        return true
    }

    fun clear() {
        entries.clear()
    }
}

/**
 * Registers the panel this composable draws as the one Back closes first. The effect lives as long
 * as the panel is composed, so a dialog that closes by its own button or scrim leaves the stack too.
 * [onDismiss] may change without re-registering.
 */
@Composable
fun DialogBackRegistration(onDismiss: () -> Unit) {
    val owner = remember { Any() }
    val currentDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(owner) {
        DialogBackRegistry.register(owner) { currentDismiss() }
        onDispose { DialogBackRegistry.unregister(owner) }
    }
}

/**
 * Dismisses a dialog on either close bind - Close/Dismiss overlay (Escape) or Go back (Backspace,
 * which is also a pad's B while browsing); see [dismissesOverlay]. Both are asked for because a
 * dialog is at once the overlay on top and the top of the Back stack, so answering only one leaves
 * the other's users unable to close it.
 *
 * Applied at the dialog's root, so the preview phase sees the key before any focused control does:
 * a settings row inside the dialog would otherwise answer Back by trying to focus the page's
 * sidebar, which belongs to the parent window's focus manager and can never be reached from here.
 * A text field keeps the key: [dismissesOverlay] answers false while one holds focus, so Backspace
 * deletes and Escape ends the edit; the next press, with no field active, closes the dialog.
 */
fun Modifier.dismissOnBackKey(onDismiss: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (event.dismissesOverlay()) {
        onDismiss()
        true
    } else {
        false
    }
}
