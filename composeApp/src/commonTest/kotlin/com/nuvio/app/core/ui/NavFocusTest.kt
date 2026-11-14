package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavFocusTest {
    @Test
    fun verticalMovementClampsAtEnds() {
        assertEquals(SidebarKeyAction.Move(0), sidebarKeyAction(Key.DirectionUp, 0, 4))
        assertEquals(SidebarKeyAction.Move(3), sidebarKeyAction(Key.DirectionDown, 3, 4))
        assertEquals(SidebarKeyAction.Move(2), sidebarKeyAction(Key.DirectionDown, 1, 4))
    }

    @Test
    fun upFromTheFirstTabReachesTheProfileAboveIt() {
        // The profile trigger is index -1, above Home at index 0, when the sidebar's minimum is -1.
        assertEquals(SidebarKeyAction.Move(-1), sidebarKeyAction(Key.DirectionUp, 0, 4, minIndex = -1))
        assertEquals(SidebarKeyAction.Move(-1), sidebarKeyAction(Key.DirectionUp, -1, 4, minIndex = -1))
    }

    @Test
    fun downFromTheProfileReturnsToHomeAndSelectOnItActivates() {
        assertEquals(SidebarKeyAction.Move(0), sidebarKeyAction(Key.DirectionDown, -1, 4, minIndex = -1))
        assertEquals(SidebarKeyAction.Activate, sidebarKeyAction(Key.Enter, -1, 4, minIndex = -1))
    }

    @Test
    fun withoutTheProfileRowUpStillStopsAtHome() {
        // The default minimum is 0, so the existing tab-only sidebar behaves as before.
        assertEquals(SidebarKeyAction.Move(0), sidebarKeyAction(Key.DirectionUp, 0, 4))
    }

    @Test
    fun selectActivatesAndDismissKeysLeave() {
        assertEquals(SidebarKeyAction.Activate, sidebarKeyAction(Key.Enter, 1, 4))
        assertEquals(SidebarKeyAction.Leave, sidebarKeyAction(Key.Escape, 1, 4))
        assertEquals(SidebarKeyAction.Leave, sidebarKeyAction(Key.DirectionRight, 1, 4))
    }

    @Test
    fun unrelatedKeysAndEmptySidebarAreIgnored() {
        assertNull(sidebarKeyAction(Key.A, 0, 4))
        assertNull(sidebarKeyAction(Key.Enter, 0, 0))
    }

    @Test
    fun dismissEntersSidebarOnlyWhenHomeIdleAndSidebarShown() {
        assertEquals(true, shouldEnterSidebarOnDismiss(true, false, true, false))
        assertEquals(false, shouldEnterSidebarOnDismiss(false, false, true, false))
        assertEquals(false, shouldEnterSidebarOnDismiss(true, true, true, false))
        assertEquals(false, shouldEnterSidebarOnDismiss(true, false, false, false))
    }

    @Test
    fun escWindowedFirstSuppressesDismissHandoff() {
        assertEquals(false, shouldEnterSidebarOnDismiss(true, false, true, true))
        assertEquals(true, shouldEnterSidebarOnDismiss(true, false, true, false))
    }

    @Test
    fun enterIsRefusedWithoutSidebar() {
        RootNavFocus.sidebarAvailable = false
        assertEquals(false, RootNavFocus.enterSidebar())
    }

    @Test
    fun leftLeavesTheRowForTheSidebarOnlyFromTheFirstCard() {
        // Left at the first card goes to the sidebar; anywhere else it steps back within the row.
        // moveItem clamps at zero, so without this the key would be swallowed at the first card.
        assertEquals(true, leftLeavesRowForSidebar(0))
        assertEquals(false, leftLeavesRowForSidebar(1))
        assertEquals(false, leftLeavesRowForSidebar(3))
        // An empty row reads as index 0, so Left there still offers the sidebar.
        assertEquals(true, leftLeavesRowForSidebar(-1))
    }

    @Test
    fun everyShownRowHasAnIndexForEveryVisibilityCombination() {
        // One entry per shown navigation tab (1..8), with and without the voice row: Settings always
        // takes the entry count and the voice row always takes the one after it.
        for (entryCount in 1..8) {
            for (voiceRowShown in listOf(false, true)) {
                for (musicRowShown in listOf(false, true)) {
                    val count = sidebarRowCount(entryCount, voiceRowShown, musicRowShown)
                    assertEquals(entryCount + 1 + (if (voiceRowShown) 1 else 0) + (if (musicRowShown) 1 else 0), count)

                    // Settings is in range whichever combination is on.
                    assertEquals(true, entryCount in 0 until count)
                    // The voice row exists exactly when it is drawn, and is the last row when it is.
                    val voice = sidebarVoiceRowIndex(entryCount, voiceRowShown)
                    assertEquals(if (voiceRowShown) entryCount + 1 else -1, voice)
                    // The album row follows Settings and the optional voice row, and is last.
                    val music = sidebarMusicRowIndex(entryCount, voiceRowShown, musicRowShown)
                    assertEquals(if (musicRowShown) entryCount + 1 + (if (voiceRowShown) 1 else 0) else -1, music)
                    if (musicRowShown) assertEquals(count - 1, music)
                    if (voiceRowShown && !musicRowShown) assertEquals(count - 1, voice)
                }
            }
        }
    }

    @Test
    fun arrowsStopAtTheEndsOfEveryRowSet() {
        for (entryCount in 1..8) {
            for (voiceRowShown in listOf(false, true)) {
                val count = sidebarRowCount(entryCount, voiceRowShown)
                val last = count - 1
                // The entry rows and Settings keep the old behaviour: clamp at both ends.
                assertEquals(SidebarKeyAction.Move(last), sidebarKeyAction(Key.DirectionDown, last, count))
                assertEquals(SidebarKeyAction.Move(last), sidebarKeyAction(Key.DirectionDown, count, count))
                assertEquals(SidebarKeyAction.Move(0), sidebarKeyAction(Key.DirectionUp, 0, count))
                // The profile row stays reachable above the first row and hands back to it.
                assertEquals(SidebarKeyAction.Move(-1), sidebarKeyAction(Key.DirectionUp, 0, count, minIndex = -1))
                assertEquals(SidebarKeyAction.Move(0), sidebarKeyAction(Key.DirectionDown, -1, count, minIndex = -1))
            }
        }
    }

    @Test
    fun enterActivatesEveryRowIncludingSettingsAndVoice() {
        for (entryCount in 1..8) {
            for (voiceRowShown in listOf(false, true)) {
                val count = sidebarRowCount(entryCount, voiceRowShown)
                // First entry, last entry, Settings, and the voice row when it is drawn.
                assertEquals(SidebarKeyAction.Activate, sidebarKeyAction(Key.Enter, 0, count))
                assertEquals(SidebarKeyAction.Activate, sidebarKeyAction(Key.Enter, count - 1, count))
                assertEquals(SidebarKeyAction.Activate, sidebarKeyAction(Key.Enter, entryCount, count))
                val voiceIndex = sidebarVoiceRowIndex(entryCount, voiceRowShown)
                if (voiceIndex >= 0) assertEquals(SidebarKeyAction.Activate, sidebarKeyAction(Key.Enter, voiceIndex, count))
                // Nothing to activate on an empty sidebar, so Enter stays with the content.
                assertNull(sidebarKeyAction(Key.Enter, 0, 0))
            }
        }
    }

    @Test
    fun escapeHandsOffOnEveryTabButHome() {
        // Home owns Escape (trailer, resume prompt, then the windowed-first rule), so it must not be
        // handled twice; every other tab hands it off, uniformly. Levels a screen closes itself
        // (Live TV's popup, the Music pages' selection) are spent in its preview handler first, so no
        // flag is needed to name those screens: whatever reaches here has nothing left to close.
        assertEquals(TabHandoffKey.Escape, tabHandoffKey(Key.Escape, homeSelected = false))
        assertNull(tabHandoffKey(Key.Escape, homeSelected = true))
    }

    @Test
    fun backHandsOffOnEveryTabIncludingHome() {
        // Back is the identifier two screens are switched to from here, so it is never left to the
        // screen, and Home handed its own Back/Dismiss over long ago.
        for (homeSelected in listOf(false, true)) {
            assertEquals(TabHandoffKey.Back, tabHandoffKey(Key.Backspace, homeSelected))
        }
    }

    @Test
    fun leftHandsOffOnEveryTab() {
        // A page releases a Left that moved focus nowhere (see arrowKeyFocusNavigation), and a page
        // that runs its own selection declines it at the first item, so a Left that gets this far has
        // nothing further left in the page and belongs to the navigation bar.
        for (homeSelected in listOf(false, true)) {
            assertEquals(TabHandoffKey.Left, tabHandoffKey(Key.DirectionLeft, homeSelected))
        }
    }

    @Test
    fun everyOtherKeyStaysWithTheScreen() {
        // Typing surface was already filtered before this point; the remaining keys are the screen's.
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionRight, Key.Enter, Key.A, Key.PageDown)) {
            for (homeSelected in listOf(false, true)) {
                assertNull(tabHandoffKey(key, homeSelected))
            }
        }
    }

    @Test
    fun upReachesTheTopBarOnlyWhileOneIsDrawn() {
        // Up is the top bar's Left: a page that still had a row above it moved into it and kept the
        // key, so one that arrives here is at its top and the bar is what is above it.
        assertEquals(TabHandoffKey.Up, tabHandoffKey(Key.DirectionUp, homeSelected = false, topBarAvailable = true))
        assertNull(tabHandoffKey(Key.DirectionUp, homeSelected = false, topBarAvailable = false))
        // Home included: its own handler declines Up at the top row rather than claiming it.
        assertEquals(TabHandoffKey.Up, tabHandoffKey(Key.DirectionUp, homeSelected = true, topBarAvailable = true))
    }

    @Test
    fun theTopBarWalksSidewaysAndLeavesDownwards() {
        assertEquals(TopBarKeyAction.Move(2), topBarKeyAction(Key.DirectionRight, 1, 4))
        assertEquals(TopBarKeyAction.Move(0), topBarKeyAction(Key.DirectionLeft, 1, 4))
        // The ends stay on the bar: an arrow there has nothing further to walk to.
        assertEquals(TopBarKeyAction.Move(3), topBarKeyAction(Key.DirectionRight, 3, 4))
        assertEquals(TopBarKeyAction.Move(0), topBarKeyAction(Key.DirectionLeft, 0, 4))
        assertEquals(TopBarKeyAction.Activate, topBarKeyAction(Key.Enter, 2, 4))
        assertEquals(TopBarKeyAction.Activate, topBarKeyAction(Key.NumPadEnter, 2, 4))
        // Down goes back into the page under the bar, and so do the keys that mean "out".
        assertEquals(TopBarKeyAction.Leave, topBarKeyAction(Key.DirectionDown, 2, 4))
        assertEquals(TopBarKeyAction.Leave, topBarKeyAction(Key.Escape, 2, 4))
        assertEquals(TopBarKeyAction.Leave, topBarKeyAction(Key.Backspace, 2, 4))
        // Up is not the bar's: there is nothing above it, and the bar itself consumes the key.
        assertNull(topBarKeyAction(Key.DirectionUp, 2, 4))
        assertNull(topBarKeyAction(Key.Enter, 0, 0))
    }

    @Test
    fun anEmptyFieldAsksTheBarAndOnlyFallsBackWhenThereIsNone() {
        // The field asks for the bar itself, because the tab host's hand-off stands aside for every
        // text field. With no bar on screen it lets go of the field instead, as it used to.
        val wasAvailable = RootNavFocus.sidebarAvailable
        try {
            RootNavFocus.sidebarAvailable = false
            var fellBack = 0
            assertFalse(dismissToSidebarOr { fellBack++ })
            assertEquals(1, fellBack)
            RootNavFocus.sidebarAvailable = true
            assertTrue(dismissToSidebarOr { fellBack++ })
            assertEquals(1, fellBack)
        } finally {
            RootNavFocus.sidebarAvailable = wasAvailable
        }
    }

    @Test
    fun aClosingScreenLeavesNoPendingHandoffWhenTheBarCannotTakeIt() {
        val wasAvailable = RootNavFocus.sidebarAvailable
        try {
            RootNavFocus.handoffPending = false
            RootNavFocus.sidebarAvailable = false
            assertFalse(RootNavFocus.enterSidebarAfterClosing())
            assertFalse(RootNavFocus.handoffPending)
            RootNavFocus.sidebarAvailable = true
            assertTrue(RootNavFocus.enterSidebarAfterClosing())
            assertTrue(RootNavFocus.handoffPending)
        } finally {
            RootNavFocus.handoffPending = false
            RootNavFocus.sidebarAvailable = wasAvailable
        }
    }

    @Test
    fun aVanishingRowPullsTheFocusedIndexBackInside() {
        // Eight entries plus Settings, then Games/Discover switched off: the cursor was on a row that
        // no longer exists and must land on the new last row.
        assertEquals(4, clampSidebarIndex(7, 5, -1))
        assertEquals(4, clampSidebarIndex(5, 5, -1))
        // The profile row above the first stays put, and an in-range row is untouched.
        assertEquals(-1, clampSidebarIndex(-1, 5, -1))
        assertEquals(3, clampSidebarIndex(3, 5, -1))
        // An empty sidebar leaves the cursor on the profile row rather than at a negative index.
        assertEquals(-1, clampSidebarIndex(0, 0, -1))
    }
}
