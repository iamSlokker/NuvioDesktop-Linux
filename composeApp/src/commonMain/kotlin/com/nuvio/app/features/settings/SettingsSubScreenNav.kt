package com.nuvio.app.features.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
import com.nuvio.app.core.ui.TextInputFocusTracker
import com.nuvio.app.core.ui.arrowFocusDirection
import com.nuvio.app.core.ui.focusedControl
import com.nuvio.app.core.ui.navigationKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The three shared navigation helpers for a Settings sub-screen: a dialog or an editor that replaces
 * the page rather than living in its content list.
 *
 * They exist because a sub-screen is a separate focus owner from the page behind it. Before these
 * helpers, Escape closed nothing, the control that opened the sub-screen was forgotten, and with
 * nothing focused afterwards the Settings arrows stopped working. Apply the helpers in the
 * sub-screen; do not add another per-screen back handler beside them.
 */

/**
 * Wraps a dialog, editor or picker that sits above a Settings page.
 *
 * - On open it focuses [firstControl] when given, otherwise the first registered control in the
 *   content region.
 * - Escape and Back close it through [onClose] in the preview phase, before a row or text field
 *   consumes them. While a text field is typing, the field keeps its own Escape.
 * - On close it puts focus back on the control that opened it, captured when this composed. When
 *   that node has left composition (a full-page editor replaces the page), it asks the Settings
 *   content region for its entry for a couple of seconds instead, so the page is walkable again.
 * - Registers Back once with [SettingsBackBridge], for the app-level Back path (mouse Back, a
 *   rebinding, the gamepad's B). The direct preview handler covers the keyboard before that.
 */
@Composable
internal fun settingsSubScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    firstControl: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val latestOnClose by rememberUpdatedState(onClose)
    var closed by remember { mutableStateOf(false) }
    // The control whose Select opened this: its node is still attached for a dialog, so on close the
    // keys land on exactly that row instead of the page's first control.
    val opener = remember { focusManager.focusedControl() }

    fun close() {
        if (closed) return
        closed = true
        latestOnClose()
    }

    LaunchedEffect(Unit) {
        // The content's requesters attach after this composition, so try over several frames. An
        // entry from a layer that is still leaving composition can take the focus and die with it,
        // so a success only counts once the focus is verified to stick.
        repeat(20) {
            withFrameNanos { }
            val focused = if (firstControl != null) {
                runCatching { firstControl.requestFocus() }.getOrDefault(false)
            } else {
                focusEntrySticking(focusManager)
            }
            if (focused) return@LaunchedEffect
            delay(16)
        }
    }

    DisposableEffect(Unit) {
        val token = Any()
        SettingsBackBridge.register(token) {
            if (closed) {
                false
            } else {
                close()
                true
            }
        }
        onDispose {
            SettingsBackBridge.unregister(token)
            // Any way the sub-screen leaves — its own close, Save, or the header's back arrow —
            // must give the keys back. The restore is a no-op when something already holds them,
            // so an unrelated route change cannot steal focus.
            restoreSettingsSubScreenFocus(focusManager, opener)
        }
    }

    Box(
        modifier = modifier
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // A field's Escape clears the edit first; the field keeps that key while typing.
                if (TextInputFocusTracker.active.value) return@onPreviewKeyEvent false
                when (event.navigationKey()) {
                    Key.Escape, Key.Backspace -> {
                        close()
                        true
                    }
                    else -> false
                }
            },
        content = content,
    )
}

/**
 * Puts the keys back after a sub-screen closes. The opener node is exact but disappears when the
 * sub-screen replaced the page, so the fallback waits for the page's new requesters to register.
 */
private fun restoreSettingsSubScreenFocus(
    focusManager: FocusManager,
    opener: FocusTargetModifierNode?,
) {
    if (opener != null && opener.node.isAttached &&
        runCatching { opener.requestFocus() }.getOrDefault(false)
    ) {
        return
    }
    CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
        // The closing layer's entries are still registered for a frame or two after it leaves
        // composition, so an entry can take the focus and then die with its row. Retry until the
        // focus actually sticks (see [focusEntrySticking]).
        delay(32)
        repeat(10) {
            if (focusEntrySticking(focusManager)) return@launch
            delay(32)
        }
    }
}

/**
 * Focuses the content region's entry and verifies, a frame later, that the focus actually held.
 * During a layer transition the closing layer's entries are still registered: one of them can
 * accept the focus request and then leave composition, dropping the keys to nowhere. A frame later
 * the focus is either on that node (alive and good) or lost, in which case the caller retries.
 */
private suspend fun focusEntrySticking(focusManager: FocusManager): Boolean {
    // Never steal the keys: if a control (the parent page's, the user's) already has them, the
    // restore is done.
    if (focusManager.focusedControl() != null) return true
    if (!SettingsContentFocus.requestEntry(focusManager)) return false
    withFrameNanos { }
    return focusManager.focusedControl() != null
}

/**
 * Shared state for a keyboard-driven drag list: which item was picked up and where it has moved to.
 * The list keeps drawing itself as usual; the helper only turns Select/Up/Down/Escape into the same
 * move callback the pointer drag uses.
 */
internal class SettingsKeyboardReorderState(
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
) {
    var itemCount by mutableIntStateOf(0)
        internal set

    var pickedUpIndex by mutableStateOf<Int?>(null)
        private set

    var currentIndex by mutableStateOf<Int?>(null)
        private set

    fun pickUp(index: Int) {
        pickedUpIndex = index
        currentIndex = index
    }

    fun moveBy(delta: Int) {
        val from = currentIndex ?: return
        val last = (itemCount - 1).coerceAtLeast(0)
        currentIndex = (from + delta).coerceIn(0, last)
    }

    /** Drops the picked item at its current slot and calls the list's existing move callback. */
    fun drop() {
        val from = pickedUpIndex ?: return
        val to = currentIndex ?: from
        reset()
        if (from != to) onMove(from, to)
    }

    fun cancel() = reset()

    private fun reset() {
        pickedUpIndex = null
        currentIndex = null
    }
}

@Composable
internal fun rememberSettingsKeyboardReorderState(
    itemCount: Int,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): SettingsKeyboardReorderState {
    val state = remember(onMove) { SettingsKeyboardReorderState(onMove) }
    SideEffect { state.itemCount = itemCount }
    return state
}

/**
 * Makes one row of a drag list a keyboard stop. Select picks the focused item up; Up and Down move
 * it one slot; Select drops it and calls the list's move callback; Escape cancels. While nothing is
 * picked up, Up, Down and Escape are left to the row's ordinary navigation, and a control *inside*
 * the row (an edit or delete icon) keeps its own Select instead of the row's pick-up stealing it.
 * Right descends from the row into its first child control and Left returns to the row, so the
 * icons beside the name are reachable even though the row itself spans their width.
 */
@Composable
internal fun Modifier.settingsKeyboardReorder(
    state: SettingsKeyboardReorderState,
    index: Int,
): Modifier = composed {
    // The row's own focus target is what picks up; a child control holding the keys keeps Select.
    var selfFocused by remember { mutableStateOf(false) }
    var subtreeFocused by remember { mutableStateOf(false) }
    val rowRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    this
        .focusRequester(rowRequester)
        .onFocusChanged { selfFocused = it.isFocused; subtreeFocused = it.hasFocus }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.navigationKey()) {
                Key.Enter, Key.NumPadEnter -> {
                    if (!selfFocused) return@onPreviewKeyEvent false
                    if (state.pickedUpIndex == null) state.pickUp(index) else state.drop()
                    true
                }
                Key.DirectionRight -> if (selfFocused && focusManager.moveFocus(FocusDirection.Enter)) {
                    true
                } else {
                    false
                }
                Key.DirectionLeft -> if (!selfFocused && subtreeFocused) {
                    rowRequester.requestFocus()
                    true
                } else {
                    false
                }
                Key.DirectionUp -> if (state.pickedUpIndex != null) {
                    state.moveBy(-1)
                    true
                } else {
                    false
                }
                Key.DirectionDown -> if (state.pickedUpIndex != null) {
                    state.moveBy(1)
                    true
                } else {
                    false
                }
                Key.Escape -> if (state.pickedUpIndex != null) {
                    state.cancel()
                    true
                } else {
                    false
                }
                else -> false
            }
        }
}

/**
 * Keys for a single-line settings field. Up and Down move focus out of the field instead of moving
 * the caret (and dying there); Escape moves focus to the stop before the field and never clears
 * focus to null. Apply it to the field, not to the whole page: other controls keep their own keys.
 */
@Composable
internal fun Modifier.settingsTextFieldKeys(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // While a field is focused, arrow keys keep their raw identity (arrowFocusDirection falls
        // back to the raw key, not the rebound navigation action), so Up/Down leave the field and
        // Left/Right stay for the caret. A text field's focus node does not move spatially (that is
        // exactly why it traps the arrows), so it leaves through traversal order, as the field's
        // native Escape already does. Escape keeps focus on the page.
        when (arrowFocusDirection(event)) {
            FocusDirection.Up -> focusManager.moveFocus(FocusDirection.Previous)
            FocusDirection.Down -> focusManager.moveFocus(FocusDirection.Next)
            else -> if (event.key == Key.Escape) {
                // First Escape ends the edit and leaves the field. The neighbour may be another
                // text field (Name / Backdrop), whose focus keeps the text session alive and
                // swallows the next Escape, so keep stepping Next until the focus is on a control
                // that is not a field. The second Escape then closes the enclosing layer.
                if (focusManager.moveFocus(FocusDirection.Previous) ||
                    focusManager.moveFocus(FocusDirection.Next)
                ) {
                    var steps = 0
                    while (TextInputFocusTracker.active.value && steps < 8) {
                        if (!focusManager.moveFocus(FocusDirection.Next)) break
                        steps += 1
                    }
                }
                // A field that is the screen's only control cannot leave; there the edit and the
                // layer close as one, through the ordinary Back ladder. Focus is never cleared.
                if (TextInputFocusTracker.active.value) DesktopNavigationGestureBridge.requestBack()
                true
            } else {
                false
            }
        }
    }
}
