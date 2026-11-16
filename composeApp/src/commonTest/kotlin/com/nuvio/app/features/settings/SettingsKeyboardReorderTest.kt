package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsKeyboardReorderTest {

    @Test
    fun pickUpMoveDropCallsTheMoveCallbackOnce() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val state = SettingsKeyboardReorderState { from, to -> moves += from to to }
        state.itemCount = 3

        state.pickUp(1)
        assertEquals(1, state.pickedUpIndex)
        assertEquals(1, state.currentIndex)

        state.moveBy(-1)
        assertEquals(0, state.currentIndex, "Up moves one slot toward the top")
        state.moveBy(-1)
        assertEquals(0, state.currentIndex, "Up clamps at the first slot")
        state.moveBy(1)
        state.moveBy(1)
        state.moveBy(1)
        assertEquals(2, state.currentIndex, "Down clamps at the last slot")

        state.drop()
        assertEquals(listOf(1 to 2), moves, "drop calls the list's move callback with from and to")
        assertNull(state.pickedUpIndex, "drop clears the pick-up")
        assertNull(state.currentIndex)
    }

    @Test
    fun dropAtTheSameSlotDoesNotMove() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val state = SettingsKeyboardReorderState { from, to -> moves += from to to }
        state.itemCount = 3
        state.pickUp(0)
        state.drop()
        assertEquals(emptyList(), moves)
    }

    @Test
    fun escapeCancelsWithoutMoving() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val state = SettingsKeyboardReorderState { from, to -> moves += from to to }
        state.itemCount = 4
        state.pickUp(3)
        state.moveBy(-1)
        assertEquals(2, state.currentIndex)
        state.cancel()
        assertNull(state.pickedUpIndex)
        assertNull(state.currentIndex)
        assertEquals(emptyList(), moves, "cancel restores the original position and moves nothing")
    }
}
