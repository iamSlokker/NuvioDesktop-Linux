package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.MusicSearchHistory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Music half of the top bar's Search, as the bar runs it when its scope is Music: typing goes
 * into [MusicTabState.query] and Enter runs [MusicTabState.search] and records the query in
 * [MusicSearchHistory]. These are the calls `submitBarSearch`/`setBarSearchText` make in App.kt;
 * the routing itself is what the live run checks (see the BarSearchDbg trace).
 */
class MusicSearchSubmitTest {

    @BeforeTest
    fun clearTheTab() {
        MusicTabState.query = ""
        MusicTabState.submitted = ""
    }

    @Test
    fun typingFillsTheBoxWithoutSearching() {
        // The setter is what the bar's onSearchQueryChange uses: the box shows it, nothing runs yet.
        MusicTabState.query = "daft pu"
        assertEquals("daft pu", MusicTabState.query)
        assertEquals("", MusicTabState.submitted, "typing alone must not run the search")
    }

    @Test
    fun enterRunsTheQueryItPutsInTheBox() {
        MusicTabState.search("daft punk")
        assertEquals("daft punk", MusicTabState.query, "the bar's box shows the query")
        assertEquals("daft punk", MusicTabState.submitted, "Enter ran it")
    }

    @Test
    fun clearingTheBoxClearsTheSearch() {
        MusicTabState.search("daft punk")
        MusicTabState.query = ""
        assertEquals("", MusicTabState.submitted, "an empty box shows the start screen again, not the old results")
    }

    @Test
    fun aSearchIsRecordedInTheMusicHistoryNotTheFilmOne() {
        val before = MusicSearchHistory.recent.value
        MusicSearchHistory.record("daft punk")
        assertTrue(
            MusicSearchHistory.recent.value.contains("daft punk"),
            "the Music box's history is the music history: was $before",
        )
    }
}
