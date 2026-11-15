package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import com.nuvio.app.core.ui.TextInputFocusTracker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridge exposing the desktop player shortcut bindings + rebinding UI to shared settings code.
 * The label flow maps each rebindable [PlayerShortcutAction] to a human-readable key label
 * (e.g. "Space", "F10", "←"). Only a desktop actual exists (the sole KMP target).
 */
expect fun playerShortcutKeyLabels(): StateFlow<Map<PlayerShortcutAction, String>>

/** Ensures persisted overrides are loaded before the reference page reads [playerShortcutKeyLabels]. */
expect fun ensurePlayerShortcutBindingsLoaded()

/** Restores every rebindable player shortcut to its default key. */
expect fun resetAllPlayerShortcuts()

/** Restores just [actions] to their default keys — one section of the shortcuts page. */
expect fun resetPlayerShortcuts(actions: Collection<PlayerShortcutAction>)

/** Reloads bindings for the newly active profile (bindings are profile-scoped). */
expect fun onPlayerShortcutsProfileChanged()

/** Escape in fullscreen playback goes windowed first instead of leaving the player. */
expect fun playerEscWindowedFirstState(): StateFlow<Boolean>
expect fun setPlayerEscWindowedFirst(enabled: Boolean)

expect fun appShortcutKeyLabels(): StateFlow<Map<AppShortcutAction, String>>
expect fun ensureAppShortcutBindingsLoaded()
expect fun resetAllAppShortcuts()
expect fun appShortcutMatches(action: AppShortcutAction, event: KeyEvent): Boolean

/**
 * The binds that close whatever is on top: [AppShortcutAction.DismissOverlay] ("close this", Escape
 * by default) and [AppShortcutAction.GoBack] ("leave this", Backspace by default).
 *
 * They stay two separate, separately rebindable actions - the player dispatcher takes only the
 * dismiss one, and the pad binds B to GoBack while browsing but to DismissOverlay in the player - but
 * for an overlay over a screen they mean the same thing, so overlay code should ask
 * [dismissesOverlay] instead of picking one and stranding whoever presses the other.
 */
internal val OVERLAY_DISMISS_ACTIONS = listOf(
    AppShortcutAction.DismissOverlay,
    AppShortcutAction.GoBack,
)

/**
 * The rule those binds follow, kept apart from the key plumbing so it can be tested: a focused text
 * field keeps the key for itself - Backspace deletes, Escape ends the edit - and the overlay only
 * closes on the press after that, once no field is active. Anything else matching either bind
 * dismisses.
 *
 * The text-field case is the one that must never be left to each caller: an overlay that answered
 * Backspace while a box had focus would swallow the keystroke the user aimed at the box.
 */
internal fun shouldDismissOverlay(
    textInputActive: Boolean,
    matchesDismiss: Boolean,
    matchesBack: Boolean,
): Boolean = !textInputActive && (matchesDismiss || matchesBack)

/**
 * True when [event] should close the overlay on top: either close bind, as currently bound (so a
 * rebind of either moves with it), and only while no text field holds the key.
 */
fun KeyEvent.dismissesOverlay(): Boolean = shouldDismissOverlay(
    textInputActive = TextInputFocusTracker.active.value,
    matchesDismiss = appShortcutMatches(AppShortcutAction.DismissOverlay, this),
    matchesBack = appShortcutMatches(AppShortcutAction.GoBack, this),
)

object AppShortcutBridge {
    private val _events = MutableSharedFlow<AppShortcutAction>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    fun emit(action: AppShortcutAction) { _events.tryEmit(action) }
}

/**
 * Modal that captures the next key press and rebinds [action] to it, rejecting reserved/fixed
 * keys and keys already assigned to another action. Includes reset-to-default and cancel.
 */
@Composable
expect fun PlayerShortcutRebindDialog(action: PlayerShortcutAction, onDismiss: () -> Unit)

@Composable
expect fun AppShortcutRebindDialog(action: AppShortcutAction, onDismiss: () -> Unit)
