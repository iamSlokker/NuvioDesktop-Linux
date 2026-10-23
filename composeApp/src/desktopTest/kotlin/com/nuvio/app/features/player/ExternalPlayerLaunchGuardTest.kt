package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalPlayerLaunchGuardTest {
    private var now = 1_000_000L
    private val guard = ExternalPlayerLaunchGuard(windowMillis = 30_000L, nowMillis = { now })
    private val source = "https://example.test/episode.mkv"

    @Test
    fun aDoubleClickOnTheSameSourceIsIgnoredWhileItsPlayerRuns() {
        guard.launched(source) { true }
        now += 4_000
        assertTrue(guard.isRepeat(source))
    }

    @Test
    fun anotherSourceAlwaysLaunches() {
        guard.launched(source) { true }
        assertFalse(guard.isRepeat("https://example.test/other.mkv"))
    }

    @Test
    fun aClosedPlayerOrAHandoffLetsTheSourceOpenAgain() {
        guard.launched(source) { false }
        assertFalse(guard.isRepeat(source))
    }

    @Test
    fun theSameSourceOpensAgainOnceTheWindowHasPassed() {
        guard.launched(source) { true }
        now += 30_000
        assertFalse(guard.isRepeat(source))
    }

    @Test
    fun nothingLaunchedYetIsNeverARepeat() {
        assertFalse(guard.isRepeat(source))
    }
}
