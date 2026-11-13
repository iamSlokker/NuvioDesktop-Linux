package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeMiniPlayerVideoTest {
    @Test
    fun thePlayingVideoCardReturnsToItsWatchPage() {
        assertTrue(isYouTubeMiniPlayerVideo("abc", playingVideoId = "abc", browsing = true))
    }

    @Test
    fun anotherVideoCardOpensNormally() {
        assertFalse(isYouTubeMiniPlayerVideo("def", playingVideoId = "abc", browsing = true))
    }

    @Test
    fun withoutTheMiniPlayerThePlayingVideoCardStillOpens() {
        assertFalse(isYouTubeMiniPlayerVideo("abc", playingVideoId = "abc", browsing = false))
    }

    @Test
    fun noPlayingVideoNeverMatches() {
        assertFalse(isYouTubeMiniPlayerVideo("abc", playingVideoId = null, browsing = true))
    }
}
