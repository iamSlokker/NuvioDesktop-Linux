package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AmoledAppearanceTest {
    @Test
    fun savedSelectorWins() {
        assertEquals(AmoledAppearance.Off, migratedAmoledMode("Off", true))
        assertEquals(AmoledAppearance.Amoled, migratedAmoledMode("Amoled", false))
        assertEquals(AmoledAppearance.Black, migratedAmoledMode("Black", false))
    }

    @Test
    fun oldBooleanMigratesToAmoledNotBlack() {
        assertEquals(AmoledAppearance.Amoled, migratedAmoledMode(null, true))
        assertEquals(AmoledAppearance.Off, migratedAmoledMode(null, false))
        assertEquals(AmoledAppearance.Off, migratedAmoledMode(null, null))
    }

    @Test
    fun unknownSelectorFallsBackToTheOldBoolean() {
        assertEquals(AmoledAppearance.Amoled, migratedAmoledMode("Garbage", true))
        assertEquals(AmoledAppearance.Off, migratedAmoledMode("Garbage", null))
    }
}
