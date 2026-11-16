package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTvSelectTargetTest {
    private fun target(
        resumePromptOpen: Boolean = false,
        continueWatchingHeroFocused: Boolean = false,
        heroFocusable: Boolean = true,
        onHeroSection: Boolean = true,
    ) = homeTvSelectTarget(resumePromptOpen, continueWatchingHeroFocused, heroFocusable, onHeroSection)

    @Test
    fun resumePromptWinsOverEverything() {
        assertEquals(
            HomeTvSelectTarget.ResumePrompt,
            target(resumePromptOpen = true, continueWatchingHeroFocused = true),
        )
    }

    @Test
    fun continueWatchingHeroIsSelectedWhenItIsShowing() {
        // The regression: the CW hero is on screen, so Select must not fall through to a catalog item.
        assertEquals(HomeTvSelectTarget.ContinueWatchingHero, target(continueWatchingHeroFocused = true))
        assertEquals(
            HomeTvSelectTarget.ContinueWatchingHero,
            target(continueWatchingHeroFocused = true, heroFocusable = false, onHeroSection = false),
        )
    }

    @Test
    fun catalogHeroNeedsAFocusableHeroOnTheHeroSection() {
        assertEquals(HomeTvSelectTarget.CatalogHero, target())
        assertEquals(HomeTvSelectTarget.Row, target(heroFocusable = false))
        assertEquals(HomeTvSelectTarget.Row, target(onHeroSection = false))
    }
}
