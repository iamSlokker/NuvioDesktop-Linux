package com.nuvio.app.features.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Grid navigation for the on-screen keyboard. Every one of these is something the user would find
 * by pressing a direction twice and ending up somewhere wrong, which is not the kind of thing that
 * survives a code read.
 */
class OnScreenKeyboardTest {

    private val bottomRow = OskLayout.rows.lastIndex

    @Test
    fun `every key is reachable and labelled`() {
        OskLayout.rows.forEachIndexed { row, keys ->
            assertTrue(keys.isNotEmpty(), "row $row is empty")
            keys.forEachIndexed { column, key ->
                assertEquals(key, OskLayout.keyAt(OskSelection(row, column)))
                assertTrue(key.label.isNotBlank(), "key at $row/$column has no label")
            }
        }
    }

    @Test
    fun `the grid holds the letters and digits a search needs`() {
        val characters = OskLayout.rows
            .flatten()
            .filterIsInstance<OskKey.Character>()
            .map { it.lower }
            .toSet()
        ('a'..'z').forEach { assertTrue(it in characters, "missing letter $it") }
        ('0'..'9').forEach { assertTrue(it in characters, "missing digit $it") }
    }

    @Test
    fun `the bottom row carries every action exactly once`() {
        val actions = OskLayout.rows[bottomRow].filterIsInstance<OskKey.Action>().map { it.action }
        assertEquals(OskAction.entries.toSet(), actions.toSet())
        assertEquals(OskAction.entries.size, actions.size)
    }

    @Test
    fun `horizontal movement clamps at the edges instead of wrapping`() {
        // Wrapping from "1" round to "0" on a grid this size reads as a misfire, not a shortcut.
        val leftEdge = OskSelection(0, 0)
        assertEquals(leftEdge, OskLayout.move(leftEdge, OskDirection.Left))

        val rightEdge = OskSelection(0, OskLayout.rows[0].lastIndex)
        assertEquals(rightEdge, OskLayout.move(rightEdge, OskDirection.Right))
    }

    @Test
    fun `vertical movement stops at the top and bottom`() {
        val top = OskSelection(0, 3)
        assertEquals(top, OskLayout.move(top, OskDirection.Up))

        val bottom = OskSelection(bottomRow, 1)
        assertEquals(bottom, OskLayout.move(bottom, OskDirection.Down))
    }

    @Test
    fun `moving down a column lands on the key underneath`() {
        // Rows 0-3 are all the same width, so a vertical move there should not shift sideways at all.
        var selection = OskSelection(0, 4)
        repeat(3) {
            selection = OskLayout.move(selection, OskDirection.Down)
            assertEquals(4, selection.column, "column drifted on the way down")
        }
        assertEquals(3, selection.row)
    }

    @Test
    fun `dropping into the narrow bottom row maps across rather than clamping`() {
        // The bottom row has four keys against ten above it. Clamping would send everything past the
        // fourth column onto "Done" — the one key a stray press must not hit.
        val fromLeft = OskLayout.move(OskSelection(3, 0), OskDirection.Down)
        assertEquals(bottomRow, fromLeft.row)
        assertEquals(0, fromLeft.column)

        val fromRight = OskLayout.move(OskSelection(3, OskLayout.rows[3].lastIndex), OskDirection.Down)
        assertEquals(OskLayout.rows[bottomRow].lastIndex, fromRight.column)

        val fromMiddle = OskLayout.move(OskSelection(3, 4), OskDirection.Down)
        assertTrue(
            fromMiddle.column in 1..2,
            "the middle of the letter row should land mid-controls, not on Done (got ${fromMiddle.column})",
        )
    }

    @Test
    fun `coming back up from the bottom row stays in range`() {
        OskLayout.rows[bottomRow].indices.forEach { column ->
            val up = OskLayout.move(OskSelection(bottomRow, column), OskDirection.Up)
            assertEquals(bottomRow - 1, up.row)
            assertTrue(
                up.column in OskLayout.rows[bottomRow - 1].indices,
                "column ${up.column} is off the row",
            )
        }
    }

    @Test
    fun `a selection off the end of a row resolves to no key rather than crashing`() {
        // Rows are ragged, so a column valid on one row can be past the end of another.
        assertEquals(null, OskLayout.keyAt(OskSelection(bottomRow, 9)))
        assertEquals(null, OskLayout.keyAt(OskSelection(99, 0)))
        assertNotNull(OskLayout.keyAt(OskSelection(1, 0)))
    }

    @Test
    fun `caps changes the character a letter key produces but not a digit`() {
        val letter = OskLayout.rows[1].first() as OskKey.Character
        assertEquals('q', letter.character(caps = false))
        assertEquals('Q', letter.character(caps = true))

        val digit = OskLayout.rows[0].first() as OskKey.Character
        assertEquals('1', digit.character(caps = false))
        assertEquals('1', digit.character(caps = true))
    }

    @Test
    fun `the panel follows its own scale slider`() {
        assertEquals(1f, oskAppUiScale(0))
        assertEquals(1.25f, oskAppUiScale(25))
        assertEquals(1.5f, oskAppUiScale(50))
        assertEquals(2f, oskAppUiScale(100))
        assertEquals(0.5f, oskAppUiScale(-50))
        // The slider offers -50..100; anything beyond lands on its ends rather than on a huge panel.
        assertEquals(2f, oskAppUiScale(200))
        assertEquals(0.5f, oskAppUiScale(-200))
    }
}
