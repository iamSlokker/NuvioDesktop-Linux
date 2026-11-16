package com.nuvio.app.features.settings

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Builds the real search index with every feature on. The index checks that every settings page is
 * registered and throws if one is not; Settings builds it on open, so a missing registration took
 * the whole window down the moment Settings was entered (it happened when Downloads merged into
 * the auto-downloads page).
 */
class SettingsSearchIndexBuildTest {
    @Test
    fun `search index builds with every feature enabled`() {
        val entries = runBlocking {
            buildSettingsSearchEntries(
                pluginsEnabled = true,
                downloadsEnabled = true,
                notificationsEnabled = true,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = true,
                checkForUpdatesAvailable = true,
            )
        }
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun `search index builds with optional features off`() {
        val entries = runBlocking {
            buildSettingsSearchEntries(
                pluginsEnabled = false,
                downloadsEnabled = false,
                notificationsEnabled = false,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = false,
                checkForUpdatesAvailable = false,
            )
        }
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun `volume control is searchable and targets its own row`() {
        val entries = runBlocking {
            buildSettingsSearchEntries(
                pluginsEnabled = true,
                downloadsEnabled = true,
                notificationsEnabled = true,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = true,
                checkForUpdatesAvailable = true,
            )
        }
        val entry = entries.firstOrNull { it.key == "desktop-volume-control" }
        assertNotNull(entry, "Volume Control is missing from the settings search index")
        assertEquals("Video & Audio", entry.page)
        assertTrue(entry.searchableText.contains("volume"), "searchable by 'volume'")
        val target = entry.target as? SettingsSearchTarget.Page
        assertNotNull(target, "the result must navigate to a page")
        assertEquals(SettingsPage.Playback, target.page)
        // The destination is the row's own anchor, not just the section: selecting the result has to
        // land on the switch. The page row uses the same settingsScrollAnchor(searchKey(...)).
        assertEquals(SettingsScrollAnchor.searchKey("desktop-volume-control"), target.anchor)
    }

    @Test
    fun `every mode gated home row is in the index`() {
        val entries = runBlocking {
            buildSettingsSearchEntries(
                pluginsEnabled = true,
                downloadsEnabled = true,
                notificationsEnabled = true,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = true,
                checkForUpdatesAvailable = true,
            )
        }
        // If a gated row is renamed in the index, the mode filter silently stops applying and the
        // result would land on a hidden row again. Keep both lists honest here.
        val gated = listOf(
            "home-adaptive-hero-auto-framing",
            "home-adaptive-hero-position",
            "home-adaptive-hero-height",
            "home-adaptive-ambient-background",
            "home-tv-row-transition",
            "home-tv-full-backdrop",
            "home-tv-row-dots",
            "home-tv-row-dots-anchor",
            "home-hero-trailer",
            "home-smooth-scrolling",
            "home-hover-preview",
        )
        val indexed = entries.map { it.key }.toSet()
        val missing = gated.filterNot { it in indexed }
        assertTrue(missing.isEmpty(), "mode-gated rows missing from the search index: $missing")
    }
}
