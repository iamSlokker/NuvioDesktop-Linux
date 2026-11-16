package com.nuvio.app.features.home

/** What the TV Select key acts on while Home has focus. */
internal enum class HomeTvSelectTarget { ResumePrompt, ContinueWatchingHero, CatalogHero, Row }

/**
 * Picks the Select target in precedence order. The Continue Watching hero comes before the catalog
 * hero because it is the one on screen when it holds focus, so Select must act on what the backdrop
 * shows.
 */
internal fun homeTvSelectTarget(
    resumePromptOpen: Boolean,
    continueWatchingHeroFocused: Boolean,
    heroFocusable: Boolean,
    onHeroSection: Boolean,
): HomeTvSelectTarget = when {
    resumePromptOpen -> HomeTvSelectTarget.ResumePrompt
    continueWatchingHeroFocused -> HomeTvSelectTarget.ContinueWatchingHero
    heroFocusable && onHeroSection -> HomeTvSelectTarget.CatalogHero
    else -> HomeTvSelectTarget.Row
}
