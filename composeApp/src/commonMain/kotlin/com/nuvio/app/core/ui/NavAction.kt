package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent

/**
 * A navigation press, after the user's bindings have been applied.
 *
 * Every surface that walks a selection (the sidebar, the two bars across the top, the Settings
 * page's ladder, the context menu, the player HUD's Compose side) asks this rather than comparing
 * raw [Key]s. A user who rebinds Move Left, Select, Back or Dismiss in
 * Settings > Keyboard shortcuts changes what [navigationKey] returns, so the surfaces follow the
 * new key for free; a surface that compared `event.key` against `Key.DirectionLeft` would keep
 * listening to the historical default and quietly ignore the rebind.
 *
 * Gamepad presses arrive here too: the pad synthesizes the keystroke of the action it is bound to
 * (`GamepadTarget.AppAction`), so one binding serves both devices.
 *
 * Not every consumer wants the same gate on top of this:
 *  - [navAction] is the plain mapping; the bars and menus use it because those surfaces are never
 *    reached while a text field is typing.
 *  - [navActionUnlessTyping] adds the app's text-field lock, for the page-level hand-off that must
 *    not steal Escape/Back from a field that is deleting or clearing.
 *  - A text field's own arrow handling ([arrowFocusDirection]) deliberately reads the raw key in
 *    the typing case, so a rebinding of Move Up/Down does not turn the cursor keys off.
 */
internal enum class NavAction { Up, Down, Left, Right, Select, Back, Dismiss }

/** The action a canonical key (see [navigationKey]) stands for, or null for a key that is not navigation. */
internal fun navActionForKey(key: Key): NavAction? = when (key) {
    Key.DirectionUp -> NavAction.Up
    Key.DirectionDown -> NavAction.Down
    Key.DirectionLeft -> NavAction.Left
    Key.DirectionRight -> NavAction.Right
    Key.Enter, Key.NumPadEnter -> NavAction.Select
    Key.Backspace, Key.Back -> NavAction.Back
    Key.Escape -> NavAction.Dismiss
    else -> null
}

/** The user's bindings applied, without the text-field gate. */
internal fun KeyEvent.navAction(): NavAction? = navActionForKey(navigationKey())

/** The user's bindings applied, or null while a text field holds focus (it owns those keys). */
internal fun KeyEvent.navActionUnlessTyping(): NavAction? =
    if (TextInputFocusTracker.active.value) null else navAction()
