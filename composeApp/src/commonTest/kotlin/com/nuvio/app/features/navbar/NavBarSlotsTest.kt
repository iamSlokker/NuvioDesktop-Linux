package com.nuvio.app.features.navbar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavBarSlotsTest {

    @Test
    fun floatingBarPutsTheAlbumCoverAfterTheLastEntry() {
        val slots = FloatingBarSlotLayout(entryCount = 5, musicShown = true)

        assertEquals(5, slots.musicIndex)
        assertEquals(6, slots.profileIndex)
        assertEquals(7, slots.settingsIndex)
        assertEquals(8, slots.slotCount)
    }

    @Test
    fun floatingBarClosesTheGapWhenNothingIsPlaying() {
        val slots = FloatingBarSlotLayout(entryCount = 5, musicShown = false)

        assertEquals(-1, slots.musicIndex)
        assertEquals(5, slots.profileIndex)
        assertEquals(6, slots.settingsIndex)
        assertEquals(7, slots.slotCount)
    }

    @Test
    fun floatingBarGivesEveryDrawnSlotItsOwnIndex() {
        for (entryCount in 0..8) {
            for (musicShown in listOf(true, false)) {
                val slots = FloatingBarSlotLayout(entryCount, musicShown)
                val drawn = buildList {
                    addAll(0 until entryCount)
                    if (slots.musicIndex >= 0) add(slots.musicIndex)
                    add(slots.profileIndex)
                    add(slots.settingsIndex)
                }

                assertEquals(drawn.toSet().size, drawn.size, "slots must not overlap: $drawn")
                assertEquals((0 until slots.slotCount).toList(), drawn.sorted())
                assertTrue(slots.slotCount >= drawn.size)
            }
        }
    }

    @Test
    fun settingsBarPutsCalendarAfterTheEntriesAndTheAlbumCoverAfterCalendar() {
        val slots = SettingsBarSlotLayout(entryCount = 5, musicShown = true)

        assertEquals(5, slots.calendarIndex)
        assertEquals(6, slots.musicIndex)
        assertEquals(7, slots.slotCount)
    }

    @Test
    fun settingsBarKeepsCalendarInPlaceWhenNothingIsPlaying() {
        val slots = SettingsBarSlotLayout(entryCount = 5, musicShown = false)

        assertEquals(5, slots.calendarIndex)
        assertEquals(-1, slots.musicIndex)
        assertEquals(6, slots.slotCount)
    }

    @Test
    fun settingsBarGivesEveryDrawnSlotItsOwnIndex() {
        for (entryCount in 0..8) {
            for (musicShown in listOf(true, false)) {
                val slots = SettingsBarSlotLayout(entryCount, musicShown)
                val drawn = buildList {
                    addAll(0 until entryCount)
                    add(slots.calendarIndex)
                    if (slots.musicIndex >= 0) add(slots.musicIndex)
                }

                assertEquals(drawn.toSet().size, drawn.size, "slots must not overlap: $drawn")
                assertEquals((0 until slots.slotCount).toList(), drawn.sorted())
                assertTrue(slots.slotCount >= drawn.size)
            }
        }
    }
}
