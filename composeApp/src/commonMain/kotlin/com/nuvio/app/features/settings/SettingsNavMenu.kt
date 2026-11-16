package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * What a key does in a Settings dropdown or multi-select menu.
 *
 * [Open] is the trigger's Select, before the menu is up. Everything else happens inside the popup.
 * [Ignore] is a key the menu consumes but does nothing with, so Left and Right cannot step out of
 * the menu.
 */
internal enum class SettingsMenuKeyAction { Open, MoveUp, MoveDown, Commit, Toggle, Close, Ignore }

/**
 * Maps a Settings navigation action to a menu action. Select commits on a single-select menu and
 * toggles on a multi-select one; Back closes either, and Left and Right are deliberately inert.
 */
internal fun settingsMenuKeyAction(
    action: SettingsNavKeyAction?,
    multiSelect: Boolean,
): SettingsMenuKeyAction? = when (action) {
    SettingsNavKeyAction.Up -> SettingsMenuKeyAction.MoveUp
    SettingsNavKeyAction.Down -> SettingsMenuKeyAction.MoveDown
    SettingsNavKeyAction.Activate -> if (multiSelect) SettingsMenuKeyAction.Toggle else SettingsMenuKeyAction.Commit
    SettingsNavKeyAction.Back -> SettingsMenuKeyAction.Close
    SettingsNavKeyAction.Left, SettingsNavKeyAction.Right -> SettingsMenuKeyAction.Ignore
    null -> null
}

/** Select on the closed trigger opens the menu. Nothing else opens it from the keyboard. */
internal fun settingsMenuTriggerAction(action: SettingsNavKeyAction?): SettingsMenuKeyAction? =
    if (action == SettingsNavKeyAction.Activate) SettingsMenuKeyAction.Open else null

/**
 * The option a menu opens on: the selected one, or the first when nothing is selected. [selected]
 * is the option index, or -1 when the current value matches no option.
 */
internal fun settingsMenuInitialIndex(selected: Int, count: Int): Int =
    if (count <= 0) -1 else selected.takeIf { it in 0 until count } ?: 0

/**
 * The option one step in [direction] (-1 up, +1 down). Clamped at both ends: a menu neither wraps
 * nor lets an arrow walk out of it. An empty menu has no option to land on (-1).
 */
internal fun settingsMenuIndexAfterMove(index: Int, count: Int, direction: Int): Int {
    if (count <= 0) return -1
    val from = index.coerceIn(0, count - 1)
    return (from + direction).coerceIn(0, count - 1)
}

/** The menu's visible state as the key handler changes it. */
internal data class SettingsMenuState(val index: Int, val expanded: Boolean)

/**
 * Applies one key. Close only folds the menu — Escape and Back never choose anything — and Commit
 * leaves a multi-select menu open so several options can be ticked in one visit.
 */
internal fun settingsMenuReduce(
    state: SettingsMenuState,
    action: SettingsMenuKeyAction,
    count: Int,
    multiSelect: Boolean,
): SettingsMenuState = when (action) {
    SettingsMenuKeyAction.MoveUp -> state.copy(index = settingsMenuIndexAfterMove(state.index, count, -1))
    SettingsMenuKeyAction.MoveDown -> state.copy(index = settingsMenuIndexAfterMove(state.index, count, 1))
    SettingsMenuKeyAction.Commit, SettingsMenuKeyAction.Toggle -> state.copy(expanded = multiSelect)
    SettingsMenuKeyAction.Close -> state.copy(expanded = false)
    SettingsMenuKeyAction.Open, SettingsMenuKeyAction.Ignore -> state
}

/**
 * A menu item's pointer half, plus keeping the item the keys are on inside the menu's viewport.
 *
 * It deliberately does **not** use [settingsNavItem]: that modifier sends Back to the app-level
 * gesture bridge, which inside an open menu would walk the page behind it up a level instead of
 * closing the menu. The menu's keys live on its popup (see `SettingsMenuPanel`), and the item the
 * keys are on is tracked as an index there, because a desktop popup gets its own focus owner and
 * focus cannot be moved into it by geometry. The row still paints [active] itself, the way it
 * paints hover, so this only adds the click and the scroll.
 */
internal fun Modifier.settingsMenuItem(
    active: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = composed {
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(active) {
        if (active) runCatching { inView.bringIntoView() }
    }
    this
        .bringIntoViewRequester(inView)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}
