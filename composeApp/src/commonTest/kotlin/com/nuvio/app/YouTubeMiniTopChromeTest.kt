package com.nuvio.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The YouTube mini player draws the nav bar itself, so its page must start where the main tab's
 * page does. The bar floats over the page whether it is pinned or hover-only: reserving a row for
 * it here left a dead band of empty space above the recommendations.
 */
class YouTubeMiniTopChromeTest {
    @Test
    fun aDrawnTopBarFloatsOverThePageLikeItDoesOnTheTabHost() {
        assertEquals(
            16.dp,
            youTubeMiniTopChromePadding(topBarDrawn = true, statusBarPadding = 0.dp),
        )
        assertEquals(
            32.dp + 16.dp,
            youTubeMiniTopChromePadding(topBarDrawn = true, statusBarPadding = 32.dp),
        )
    }

    @Test
    fun withoutATopBarTheTabKeepsItsOwnMargin() {
        // Null lets the tab use its default 24 dp, exactly as the sidebar layout does on the host.
        assertNull(youTubeMiniTopChromePadding(topBarDrawn = false, statusBarPadding = 24.dp))
    }
}
