package com.nuvio.app.features.home

/**
 * Controls what content [HomeScreen] shows in its catalog area.
 *
 * [Normal]  — standard home catalog rows + CW + collections.
 * [Search]  — search text input replaces the catalog area; CW/collections hidden.
 *             [autoFocusCount] increments each time the tab is re-selected so the
 *             composable can auto-focus the text field each time.
 * [Library] — library sections replace the catalog area; CW/collections hidden.
 * [Discover] — discovery rows replace the catalog area; CW/collections hidden. Everything else
 *              (hero, TV mode, poster style, scroll restoration) behaves exactly as in [Normal] —
 *              Discover is Home with different rows in it, which is why it shares this renderer.
 */
sealed class HomeContentMode {
    object Normal : HomeContentMode()
    data class Search(val autoFocusCount: Int = 0) : HomeContentMode()
    object Library : HomeContentMode()
    object Discover : HomeContentMode()
    data class Catalogs(
        val key: String,
        val sections: List<HomeCatalogSection>,
    ) : HomeContentMode()
}

/**
 * Short, stable name for telemetry lines. Deliberately not `toString()`: [Catalogs] carries its
 * whole section list, which would put a catalog dump in every log line.
 */
internal fun HomeContentMode.telemetryName(): String = when (this) {
    is HomeContentMode.Normal -> "home"
    is HomeContentMode.Search -> "search"
    is HomeContentMode.Library -> "library"
    is HomeContentMode.Discover -> "discover"
    is HomeContentMode.Catalogs -> "catalog"
}

/** Which screen's focus a held hero title belongs to; Search's re-focus counter is not part of it. */
internal fun HomeContentMode.heroHoldKey(): String = when (this) {
    is HomeContentMode.Catalogs -> "catalog:$key"
    else -> telemetryName()
}

/**
 * Browse pages that keep Home's TV Mode stacked rows.
 *
 * Search and catalog screens keep the one-row-at-a-time immersive shelf: their result sets and
 * filter headers are different enough that stacking them would be a separate layout change.
 * Home, Discover and Library share the same catalog geometry and row-jump dots.
 */
internal fun HomeContentMode.usesTvStackedRows(): Boolean = when (this) {
    is HomeContentMode.Normal,
    is HomeContentMode.Discover,
    is HomeContentMode.Library,
    -> true
    else -> false
}
