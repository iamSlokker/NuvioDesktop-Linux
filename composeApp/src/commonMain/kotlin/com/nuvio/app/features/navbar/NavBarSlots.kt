package com.nuvio.app.features.navbar

/*
 * Where the utility slots of the two bars drawn across the top of the app sit, once the navigation
 * entries are drawn. One place computes them so the draw loop, the pointer handlers and the arrow
 * keys cannot disagree about which slot is the album cover, the avatar or the gear.
 *
 * The floating bar (tablet and desktop tabs) draws its entries, then the album cover while music
 * plays, then the profile avatar, then Settings. The Settings screen's own row draws its entries,
 * then Calendar, then the album cover while music plays: no avatar and no gear, because Settings is
 * the page that row belongs to.
 */

/** The floating top bar's slots: entries, the optional album cover, the avatar, then Settings. */
internal data class FloatingBarSlotLayout(
    val entryCount: Int,
    val musicShown: Boolean,
) {
    init {
        require(entryCount >= 0) { "entryCount must not be negative: $entryCount" }
    }

    /** The album cover, or -1 when nothing is playing and the slot is not drawn. */
    val musicIndex: Int get() = if (musicShown) entryCount else -1

    /** The avatar. It keeps its place whether or not the album cover is drawn. */
    val profileIndex: Int get() = entryCount + if (musicShown) 1 else 0

    val settingsIndex: Int get() = profileIndex + 1

    /** One per entry plus a slot each for the avatar and Settings, and the album cover while shown. */
    val slotCount: Int get() = settingsIndex + 1
}

/** The Settings screen's row: entries, Calendar, then the optional album cover. */
internal data class SettingsBarSlotLayout(
    val entryCount: Int,
    val musicShown: Boolean,
) {
    init {
        require(entryCount >= 0) { "entryCount must not be negative: $entryCount" }
    }

    /** Calendar, a pushed route rather than a tab, so it has no entry of its own to take. */
    val calendarIndex: Int get() = entryCount

    /** The album cover, or -1 when nothing is playing and the slot is not drawn. */
    val musicIndex: Int get() = if (musicShown) entryCount + 1 else -1

    val slotCount: Int get() = entryCount + 1 + if (musicShown) 1 else 0
}
