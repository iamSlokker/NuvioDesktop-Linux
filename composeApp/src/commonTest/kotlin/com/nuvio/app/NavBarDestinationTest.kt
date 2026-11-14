package com.nuvio.app

import com.nuvio.app.features.navbar.NavBarEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The navigation entries' descriptors are the single place a bar learns an entry's tab. These tests
 * pin the mapping the two bars used to spell out in separate `when`s, so adding a page cannot make
 * them disagree about which tab an icon opens.
 */
class NavBarDestinationTest {

    @Test
    fun everyEntryOpensTheTabItAlwaysDid() {
        val expected = mapOf(
            NavBarEntry.Home to AppScreenTab.Home,
            NavBarEntry.Search to AppScreenTab.Search,
            // Upstream 1.38.1's entry: it opens the Tonight picker over whichever content tab is
            // showing rather than a tab of its own.
            NavBarEntry.Tonight to null,
            NavBarEntry.Discover to AppScreenTab.Discover,
            NavBarEntry.Library to AppScreenTab.Library,
            NavBarEntry.LiveTv to AppScreenTab.LiveTv,
            NavBarEntry.YouTube to AppScreenTab.YouTube,
            NavBarEntry.Music to AppScreenTab.Music,
            NavBarEntry.Games to null,
        )

        assertEquals(NavBarEntry.entries.toSet(), expected.keys, "an entry is missing from this table")
        for ((entry, tab) in expected) {
            assertEquals(tab, navBarTab(entry), "$entry")
        }
    }

    @Test
    fun theTrailingSlotIsSettings() {
        assertEquals(AppScreenTab.Settings, navBarTab(null))
    }

    @Test
    fun noTwoEntriesOpenTheSameTab() {
        val tabs = NavBarEntry.entries.mapNotNull { it.destination.tab }
        assertEquals(tabs.size, tabs.toSet().size, "two entries point at the same tab: $tabs")
    }

    @Test
    fun everyEntryHasAGlyphAndAnOrderDescription() {
        for (entry in NavBarEntry.entries) {
            val destination = entry.destination
            assertTrue(destination.orderDescription.isNotBlank(), "$entry has no order description")
            when (val label = destination.label) {
                is NavBarLabel.Literal -> assertTrue(label.text.isNotBlank(), "$entry has a blank label")
                is NavBarLabel.Resource -> Unit
            }
        }
    }

    @Test
    fun searchIsLitByItsOverlayAndEverythingElseByItsTab() {
        for (entry in NavBarEntry.entries) {
            if (entry == NavBarEntry.Search) {
                assertTrue(entry.isDestinationSelected(AppScreenTab.Home, searchActive = true))
                assertFalse(entry.isDestinationSelected(AppScreenTab.Home, searchActive = false))
            } else {
                val tab = entry.destination.tab
                if (tab == null) {
                    assertFalse(entry.isDestinationSelected(AppScreenTab.Home, searchActive = false), "$entry")
                    assertFalse(entry.isDestinationSelected(AppScreenTab.Home, searchActive = true), "$entry")
                } else {
                    val other = if (tab == AppScreenTab.Home) AppScreenTab.Music else AppScreenTab.Home
                    assertTrue(entry.isDestinationSelected(tab, searchActive = false), "$entry")
                    assertFalse(entry.isDestinationSelected(other, searchActive = false), "$entry")
                }
            }
        }
    }

    @Test
    fun onlyThePickerAndGamesHaveNoTab() {
        val withoutTab = NavBarEntry.entries.filter { it.destination.tab == null }
        // Tonight (upstream 1.38.1) opens the picker over the current tab, Games opens a window.
        assertEquals(listOf(NavBarEntry.Tonight, NavBarEntry.Games), withoutTab)
        assertNull(navBarTab(NavBarEntry.Games))
        assertNull(navBarTab(NavBarEntry.Tonight))
    }
}
