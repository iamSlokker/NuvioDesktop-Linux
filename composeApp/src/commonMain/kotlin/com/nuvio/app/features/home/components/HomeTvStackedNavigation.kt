package com.nuvio.app.features.home.components

/**
 * Row stepping for TV Mode's stacked-rows layout (hero on top, several catalog rows below).
 *
 * Works on row indices only; the hero is not keyboard-focusable in this layout, so row 0 is the
 * top of the keyboard range. Rows with no items are skipped. Returns the destination row index,
 * or [currentRow] unchanged when there is no non-empty row in [delta]'s direction (the key is
 * still consumed by the caller, matching the clamped Up/Down behaviour of the single-row shelf).
 */
internal fun homeTvStackedNextRow(
    currentRow: Int,
    delta: Int,
    rowItemCounts: List<Int>,
): Int {
    if (rowItemCounts.isEmpty() || delta == 0) return currentRow.coerceIn(0, (rowItemCounts.size - 1).coerceAtLeast(0))
    val step = if (delta > 0) 1 else -1
    var candidate = currentRow + step
    while (candidate in rowItemCounts.indices) {
        if (rowItemCounts[candidate] > 0) return candidate
        candidate += step
    }
    return currentRow.coerceIn(0, rowItemCounts.lastIndex)
}

/**
 * Whether the continue-watching card drives the hero. On Linux TV Mode (stacked rows) the hero always
 * follows focus: the session-wide dismiss is not applied there, so a focused card is never replaced
 * by the featured item. Other platforms keep the dismiss.
 */
internal fun continueWatchingHeroFollowsFocus(
    tvModeEnabled: Boolean,
    stackedRowsEnabled: Boolean,
    dismissed: Boolean,
): Boolean = !(tvModeEnabled && !stackedRowsEnabled && dismissed)

/**
 * Whether Escape on Home closes the focused continue-watching hero (the session-wide dismiss). Off on
 * Linux TV Mode: Escape there falls through to the sidebar handoff instead.
 */
internal fun escapeClosesContinueWatchingHero(
    tvModeEnabled: Boolean,
    stackedRowsEnabled: Boolean,
    heroFocused: Boolean,
): Boolean = tvModeEnabled && !stackedRowsEnabled && heroFocused
