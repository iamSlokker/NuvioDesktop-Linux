package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/*
 * A cursor over rows of cards: the shape the Library's grid and section lists use, and that a new
 * row-shaped surface can adopt instead of re-deriving index arithmetic per key.
 *
 * The cursor is plain data. It is clamped against the row sizes the caller computes (a partial last
 * grid row, a section showing only its preview cards), and a press that cannot move comes back as
 * [NavGridStep.AtEdge] rather than silently doing nothing: the caller decides whether an edge means
 * "hand the keys to the bar above" or "swallow the press", so those choices stay with the surface
 * that knows what is drawn around it.
 *
 * Movement rules, matching the Library before this module existed:
 *  - Up/Down move a row and clamp at the first/last; Down on the last row and Up on the first are
 *    edges, not moves.
 *  - Left/Right move within the row and clamp at either end; both ends are edges.
 *  - On a row change, [keepItemAcrossRows] keeps the column (a grid, so a partial last row pins to
 *    its own last card) or resets it to 0 (separate shelves, where sharing a column is meaningless).
 */

/** Where the keyboard is: a row index and an item index inside it. */
internal data class NavGridCursor(val row: Int = 0, val item: Int = 0)

/** What one press did. */
internal sealed interface NavGridStep {
    data class Moved(val cursor: NavGridCursor) : NavGridStep

    /**
     * The press had nowhere to go in [direction]. The surface answers it: the Library reaches the
     * bar above from the first row, and swallows the other edges so a held key cannot escape.
     */
    data class AtEdge(val direction: NavAction) : NavGridStep
}

/** The number of items in each row of a uniform grid that wraps [itemCount] items into [columns]. */
internal fun uniformGridRowSizes(itemCount: Int, columns: Int): List<Int> {
    if (itemCount <= 0 || columns <= 0) return emptyList()
    val rows = (itemCount + columns - 1) / columns
    return List(rows) { row -> (itemCount - row * columns).coerceIn(0, columns) }
}

/** [this] with both indices inside [rowSizes]; an empty row still holds item 0 so the cursor can move out of it. */
internal fun NavGridCursor.clamped(rowSizes: List<Int>): NavGridCursor {
    if (rowSizes.isEmpty()) return NavGridCursor()
    val row = this.row.coerceIn(0, rowSizes.lastIndex)
    val lastItem = (rowSizes[row] - 1).coerceAtLeast(0)
    return NavGridCursor(row = row, item = item.coerceIn(0, lastItem))
}

/**
 * One press of [direction] against the row sizes the surface draws. Anything other than Up, Down,
 * Left or Right is an edge: [navAction] already turned the user's bindings into these actions, and a
 * cursor has no meaning for Select, Back or Dismiss.
 */
internal fun NavGridCursor.step(
    direction: NavAction,
    rowSizes: List<Int>,
    keepItemAcrossRows: Boolean = true,
): NavGridStep {
    if (rowSizes.isEmpty()) return NavGridStep.AtEdge(direction)
    val cursor = clamped(rowSizes)
    return when (direction) {
        NavAction.Up -> if (cursor.row == 0) {
            NavGridStep.AtEdge(direction)
        } else {
            NavGridStep.Moved(cursor.moveToRow(cursor.row - 1, rowSizes, keepItemAcrossRows))
        }
        NavAction.Down -> if (cursor.row == rowSizes.lastIndex) {
            NavGridStep.AtEdge(direction)
        } else {
            NavGridStep.Moved(cursor.moveToRow(cursor.row + 1, rowSizes, keepItemAcrossRows))
        }
        NavAction.Left -> if (cursor.item == 0) {
            NavGridStep.AtEdge(direction)
        } else {
            NavGridStep.Moved(cursor.copy(item = cursor.item - 1))
        }
        NavAction.Right -> {
            val lastItem = (rowSizes[cursor.row] - 1).coerceAtLeast(0)
            if (cursor.item >= lastItem) {
                NavGridStep.AtEdge(direction)
            } else {
                NavGridStep.Moved(cursor.copy(item = cursor.item + 1))
            }
        }
        NavAction.Select, NavAction.Back, NavAction.Dismiss -> NavGridStep.AtEdge(direction)
    }
}

private fun NavGridCursor.moveToRow(target: Int, rowSizes: List<Int>, keepItem: Boolean): NavGridCursor {
    val lastItem = (rowSizes[target] - 1).coerceAtLeast(0)
    return NavGridCursor(
        row = target,
        item = if (keepItem) item.coerceIn(0, lastItem) else 0,
    )
}

/**
 * Hands the grid's arrows, select and edges to a surface that draws rows of cards.
 *
 * Runs in the preview phase, so a control inside a card that uses the arrows itself (a slider, a
 * text field) is not reached first only if it sits inside this subtree; callers put this on the page
 * root, as the Library does.
 *
 * [onEdge] answers a press that cannot move. It returns whether the surface took it; returning false
 * leaves the key to the layers above (the tab hand-off), which is how Up at the first row reaches the
 * bar across the top.
 */
@Composable
internal fun Modifier.navGridNavigation(
    cursor: () -> NavGridCursor,
    rowSizes: () -> List<Int>,
    keepItemAcrossRows: Boolean = true,
    onMove: (NavGridCursor) -> Unit,
    onSelect: () -> Boolean,
    onEdge: (NavAction) -> Boolean,
): Modifier = onPreviewKeyEvent { event: KeyEvent ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (val action = event.navAction()) {
        null -> false
        NavAction.Select -> onSelect()
        NavAction.Back, NavAction.Dismiss -> false
        else -> when (val step = cursor().step(action, rowSizes(), keepItemAcrossRows)) {
            is NavGridStep.Moved -> {
                onMove(step.cursor)
                true
            }
            // A direction the caller does not take is still a consumed edge when the grid exists, so
            // a held key cannot walk out of the page and flip focus somewhere else.
            is NavGridStep.AtEdge -> {
                val taken = onEdge(step.direction)
                taken || rowSizes().isNotEmpty()
            }
        }
    }
}

/** Remembers a grid cursor as Compose state, so a surface can keep it across recompositions. */
@Composable
internal fun rememberNavGridCursor(initial: NavGridCursor = NavGridCursor()): NavGridCursorState =
    remember { NavGridCursorState(initial) }

/** Compose state holder for [NavGridCursor]; mutate through [moveTo]. */
internal class NavGridCursorState(initial: NavGridCursor) {
    private val state = mutableStateOf(initial)

    val cursor: NavGridCursor get() = state.value

    fun moveTo(cursor: NavGridCursor) {
        state.value = cursor
    }
}
