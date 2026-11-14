package com.nuvio.app.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Makes a surface the keyboard and a pad can activate, not just the pointer.
 *
 * `Modifier.clickable` only answers the pointer. A `Button` answers Enter and space, but this app's
 * focus surfaces are custom drawings (see [nuvioSweepHighlight]) that cannot take Material's ripple
 * or its focus overlay, so they need the key handling spelled out.
 *
 * Three presses mean "activate this": [NavAction.Select] (Enter by default, or whatever the user
 * rebound Select to), and space. A gamepad's A button arrives as the keystroke bound to
 * Select Focused, so it is covered by the first without knowing the pad exists.
 *
 * The key-down is consumed and the action runs on key-up, so holding a binding down does not fire
 * repeatedly, and a press that turns into a scroll or drag is not treated as a click.
 *
 * The surface is made [focusable] here: `clickable` alone does not put a node in the focus order,
 * so a custom button built on it is invisible to the arrow keys and the D-pad. That omission is why
 * the sign-in page's buttons answered the mouse and nothing else.
 *
 * Focusability does **not** follow [enabled]. A disabled control still has to be reachable: the
 * sign-in button starts disabled (empty form), and tying its focus target to `enabled` removed it
 * from the focus order entirely — the arrows and the D-pad stepped straight past it, so there was
 * no way to Tab or walk to the thing the page is for. It is focused and visibly disabled, and
 * activation is refused below.
 */
fun Modifier.selectWithKeyboardOrPointer(
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this
    // Key handling first: `clickable` installs a focusable node that consumes Enter before later
    // modifiers in the chain see it, so with the order reversed the keyboard could never activate
    // this surface (it looked pressable with a mouse and dead with a keyboard).
    .onKeyEvent { event ->
        if (!enabled) return@onKeyEvent false
        val activates = event.navAction() == NavAction.Select || event.key == Key.Spacebar
        if (!activates) return@onKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> {
                onClick()
                true
            }
            else -> true
        }
    }
    .focusable()
    .clickable(
        enabled = enabled,
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
