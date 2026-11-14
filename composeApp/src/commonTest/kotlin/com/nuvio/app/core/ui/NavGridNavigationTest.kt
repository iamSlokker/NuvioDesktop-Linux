package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cursor the Library's grid and section lists use. These tests pin the movement rules the
 * Library spelled out by hand: clamp at the ends, a partial last grid row pins to its own last card,
 * and an edge is reported rather than silently swallowed so the surface can decide what it means.
 */
class NavGridNavigationTest {

    private fun moved(cursor: NavGridCursor, direction: NavAction, rows: List<Int>, keep: Boolean = true): NavGridCursor {
        val step = cursor.step(direction, rows, keep)
        check(step is NavGridStep.Moved) { "expected a move, got $step" }
        return step.cursor
    }

    private fun edge(cursor: NavGridCursor, direction: NavAction, rows: List<Int>, keep: Boolean = true): NavAction {
        val step = cursor.step(direction, rows, keep)
        check(step is NavGridStep.AtEdge) { "expected an edge, got $step" }
        return step.direction
    }

    @Test
    fun uniformGridWrapsIntoRowsOfTheColumnCount() {
        assertEquals(listOf(5, 5, 2), uniformGridRowSizes(itemCount = 12, columns = 5))
        assertEquals(listOf(3), uniformGridRowSizes(itemCount = 3, columns = 5))
        assertEquals(emptyList(), uniformGridRowSizes(itemCount = 0, columns = 5))
        assertEquals(emptyList(), uniformGridRowSizes(itemCount = 12, columns = 0))
    }

    @Test
    fun downAndUpMoveARow() {
        val rows = listOf(5, 5, 2)
        assertEquals(NavGridCursor(1, 2), moved(NavGridCursor(0, 2), NavAction.Down, rows))
        assertEquals(NavGridCursor(0, 2), moved(NavGridCursor(1, 2), NavAction.Up, rows))
    }

    @Test
    fun theColumnIsKeptAcrossRowsAndPinnedInAPartialRow() {
        val rows = listOf(5, 5, 2)
        // Column 4 in a full row survives; the two-card last row pins it to its last card.
        assertEquals(NavGridCursor(2, 1), moved(NavGridCursor(1, 4), NavAction.Down, rows))
        // Coming back up lands in column 1, not the column 4 left behind: the pinned position is the
        // cursor's truth, so the journey is symmetric and a column can never be restored from memory.
        assertEquals(NavGridCursor(1, 1), moved(NavGridCursor(2, 1), NavAction.Up, rows))
    }

    @Test
    fun separateShelvesResetTheItemOnARowChange() {
        val rows = listOf(10, 3)
        assertEquals(NavGridCursor(1, 0), moved(NavGridCursor(0, 7), NavAction.Down, rows, keep = false))
        assertEquals(NavGridCursor(0, 0), moved(NavGridCursor(1, 2), NavAction.Up, rows, keep = false))
    }

    @Test
    fun leftAndRightClampWithinTheRow() {
        val rows = listOf(3, 3)
        assertEquals(NavGridCursor(0, 1), moved(NavGridCursor(0, 0), NavAction.Right, rows))
        assertEquals(NavGridCursor(0, 1), moved(NavGridCursor(0, 2), NavAction.Left, rows))
    }

    @Test
    fun everyEdgeIsReportedNotSwallowed() {
        val rows = listOf(3, 3)
        assertEquals(NavAction.Up, edge(NavGridCursor(0, 1), NavAction.Up, rows))
        assertEquals(NavAction.Down, edge(NavGridCursor(1, 1), NavAction.Down, rows))
        assertEquals(NavAction.Left, edge(NavGridCursor(0, 0), NavAction.Left, rows))
        assertEquals(NavAction.Right, edge(NavGridCursor(0, 2), NavAction.Right, rows))
    }

    @Test
    fun aSingleCardRowIsAnEdgeOnBothSides() {
        val rows = listOf(1)
        assertEquals(NavAction.Left, edge(NavGridCursor(), NavAction.Left, rows))
        assertEquals(NavAction.Right, edge(NavGridCursor(), NavAction.Right, rows))
        assertEquals(NavAction.Up, edge(NavGridCursor(), NavAction.Up, rows))
        assertEquals(NavAction.Down, edge(NavGridCursor(), NavAction.Down, rows))
    }

    @Test
    fun anEmptyGridIsAnEdgeInEveryDirection() {
        for (direction in listOf(NavAction.Up, NavAction.Down, NavAction.Left, NavAction.Right)) {
            assertEquals(direction, edge(NavGridCursor(), direction, emptyList()))
        }
    }

    @Test
    fun selectBackAndDismissAreNotGridMoves() {
        for (direction in listOf(NavAction.Select, NavAction.Back, NavAction.Dismiss)) {
            assertEquals(direction, edge(NavGridCursor(), direction, listOf(3)))
        }
    }

    @Test
    fun aCursorLeftBehindByAShrinkingListIsClamped() {
        // The grid shrank under a focused cursor (a filter changed, a section emptied).
        assertEquals(NavGridCursor(1, 1), NavGridCursor(9, 9).clamped(listOf(5, 2)))
        assertEquals(NavGridCursor(0, 0), NavGridCursor(3, 4).clamped(emptyList()))
    }

    @Test
    fun aKeyHeldAtAnEdgeNeverLeavesTheGrid() {
        // Walking Right repeatedly ends on the last card of the row, and one more press is an edge.
        var cursor = NavGridCursor()
        repeat(10) {
            cursor = (cursor.step(NavAction.Right, listOf(4, 4)) as? NavGridStep.Moved)?.cursor ?: cursor
        }
        assertEquals(NavGridCursor(0, 3), cursor)
        assertEquals(NavAction.Right, edge(cursor, NavAction.Right, listOf(4, 4)))
    }
}
