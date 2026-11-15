package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicMediaKeyGateTest {
    @Test
    fun theSameKeyTwiceInsideTheWindowActsOnce() {
        assertTrue(MusicMediaKeyGate.claim("duplicate-playpause", nowMs = 1_000L))
        assertFalse(MusicMediaKeyGate.claim("duplicate-playpause", nowMs = 1_200L))
        assertTrue(MusicMediaKeyGate.claim("duplicate-playpause", nowMs = 1_400L))
    }

    @Test
    fun differentKeysInsideTheWindowBothAct() {
        assertTrue(MusicMediaKeyGate.claim("duplicate-rewind", nowMs = 2_000L))
        assertTrue(MusicMediaKeyGate.claim("duplicate-forward", nowMs = 2_100L))
    }
}
