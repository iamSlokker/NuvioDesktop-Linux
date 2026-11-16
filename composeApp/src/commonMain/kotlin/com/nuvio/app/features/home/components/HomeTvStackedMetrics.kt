package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.dp

/**
 * Window-proportional geometry for the Linux TV Mode stacked-rows Home, matching the Nuvio TV
 * proportions. Everything is a fraction of the window, except the floors and clamps noted below.
 * Card sizes are deliberately not here: they stay fixed at their current scale.
 */
data class HomeTvStackedMetrics(
    /** Hero overlay height used to place the row band (53% of the window, with a 300 dp floor). */
    val heroHeightDp: Float,
    /** Height of the backdrop artwork and its black bottom fade: the full window height. */
    val backdropHeightDp: Float,
    /** Left/right padding for both the hero text and the rows: 0.5% of the window width. */
    val sidePaddingDp: Float,
    /**
     * Card-width input: the former 5.4% section inset. Kept separate from [sidePaddingDp] so the
     * tighter content inset does not widen the cards.
     */
    val cardInsetDp: Float,
    /** Vertical pitch of each stacked row (27% of the window height). */
    val rowPitchDp: Float,
    /** Gap between the metadata column and the bottom of the hero. */
    val contentBottomPaddingDp: Float,
    /** Scale for the hero's synopsis, genre line and footer text. Follows the synopsis line height and the GUI scale. */
    val textScale: Float,
    /** Hero logo width: 21% of the window width. The logo's height follows its aspect ratio. */
    val logoWidthDp: Float,
    /** Gap from the bottom edge of the rendered logo to the top of the metadata line: 10% of the window height. */
    val logoMetadataGapDp: Float,
    /**
     * Top of the row band: the lower of the hero backdrop's bottom edge and 58% of the window, so
     * the first row never sits behind the backdrop. The backdrop bottom comes from
     * [immersiveHeroBackdropHeight], so the two cannot drift apart.
     */
    val rowBandTopDp: Float,
    /** Top of the hero text column: 15% of the window height, clear of the sidebar's profile icon. */
    val contentTopDp: Float,
    /** Height of the hero text column: from its top down to the row band, less a gap above the band. */
    val contentHeightDp: Float,
    /** Height of the soft fade where the rows layer dissolves into the hero, above the band top. */
    val rowBandFadeDp: Float,
    /** Top of the rows layer: the band top less the fade. Rows scrolled above this are hidden and cannot be hit. */
    val rowListTopDp: Float,
    /** Left edge of the hero text: 0.5% of the window width, measured from the rail's right edge. */
    val heroTextLeftDp: Float,
)

/** Synopsis line height as a fraction of window width: 47 px of a 2576 px reference. */
internal const val HOME_TV_STACKED_SYNOPSIS_LINE_HEIGHT_FRACTION = 0.01824f

/** Theme bodyLarge line height, the base the hero text scale is measured against. */
internal const val HOME_TV_STACKED_BODY_LARGE_LINE_HEIGHT_DP = 22f

internal const val HOME_TV_STACKED_LOGO_WIDTH_FRACTION = 0.24f

/**
 * The UI scale the stacked layout was authored at. The logo and the row pitch follow the GUI scale
 * setting, so they keep the same size relative to the cards (which are dp-sized, and so do scale)
 * instead of staying at a fixed pixel size: at 70% the logo looked oversized next to the smaller
 * rows, and the row band left an empty gap tall enough for another row. 125% is left exactly as
 * it was. The pitch is a floor only: it is there to keep the band's rows on the same rhythm, and
 * it must sit just above a row's own height (header band + card + 12 dp), or the leftover shows up
 * as dead space between rows that grows the more rows are on screen.
 */
internal const val HOME_TV_STACKED_UI_SCALE_REFERENCE = 1.25f

/**
 * Gap from the bottom edge of the rendered logo to the top of the metadata line, as a fraction of
 * the window height. There is no reserved logo slot: the logo takes its own height.
 */
/**
 * Gap under the rendered logo, in dp. It used to be 10% of the window height, so it changed with
 * the window and with each logo's own aspect ratio: the metadata line sat at a different height on
 * every title. A fixed gap keeps the title/logo, genre line and synopsis in the same relationship
 * across movies, shows and episodes, and scales with the UI setting like the text around it.
 */
internal const val HOME_TV_STACKED_LOGO_METADATA_GAP_DP = 14f

/**
 * Multiplier on the stacked hero text scale: the synopsis and metadata are 10% smaller than the
 * scale the GUI scale setting gives. The GUI scale itself is untouched.
 */
internal const val HOME_TV_STACKED_TEXT_SCALE_MULTIPLIER = 0.9f

/** The first row band starts no higher than this fraction of the window height. */
internal const val HOME_TV_STACKED_ROW_BAND_MIN_FRACTION = 0.58f

/** The hero text column starts this far down the window. */
internal const val HOME_TV_STACKED_CONTENT_TOP_FRACTION = 0.13f

/** Gap kept between the hero text column and the first row band. */
internal const val HOME_TV_STACKED_CONTENT_ROW_GAP_DP = 12f

/** Gap between the award glyph and the row band below it. */
internal const val HOME_TV_STACKED_MEDAL_BAND_GAP_DP = 16f

/**
 * Top of an award glyph in the stacked layout. The whole glyph sits above the row band, with
 * [HOME_TV_STACKED_MEDAL_BAND_GAP_DP] of clear art under it: overlapping the band put it under the
 * row list, which is drawn after the hero, so the medal disappeared.
 */
internal fun homeTvStackedMedalTopDp(rowBandTopDp: Float, diameterDp: Float): Float =
    rowBandTopDp - diameterDp - HOME_TV_STACKED_MEDAL_BAND_GAP_DP

/** Fade height where the rows layer dissolves into the hero. */
internal const val HOME_TV_STACKED_ROW_BAND_FADE_DP = 24f

/** Left inset of the hero text and the rows, as a fraction of the content width. */
internal const val HOME_TV_STACKED_SIDE_PADDING_FRACTION = 0.005f

/** The former section inset, now used only for card width. */
internal const val HOME_TV_STACKED_CARD_INSET_FRACTION = 0.054f

/** Synopsis lines the stacked hero shows before the rest is scrolled into view. */
internal const val HOME_TV_STACKED_SYNOPSIS_MAX_LINES = 5

/** Fewest synopsis lines the stacked hero will show before it drops a metadata row. */
internal const val HOME_TV_STACKED_SYNOPSIS_MIN_LINES = 2

/**
 * The shared Hero height setting is authored for the Adaptive hero (default 1.25). The stacked
 * hero follows the same value, normalised so 125% is the existing default, but keeps a floor and a
 * ceiling where the rows stay usable. The ceiling reaches the 175% end of the slider: at the top
 * the artwork ends just above the bottom edge and the first row still fits.
 */
internal fun tvStackedHeroHeightMultiplier(settingMultiplier: Float): Float =
    (settingMultiplier / 1.25f).coerceIn(0.85f, 1.40f)

/** The row pitch fraction of the window height, at the authored UI scale. */
internal const val HOME_TV_STACKED_ROW_PITCH_FRACTION = 0.27f

internal fun homeTvStackedMetrics(
    widthDp: Float,
    heightDp: Float,
    guiScale: Float = 1f,
    heroHeightMultiplier: Float = 1f,
): HomeTvStackedMetrics {
    val heroHeightDp = (heightDp * 0.53f * heroHeightMultiplier).coerceAtLeast(300f)
    val contentBottomPaddingDp = (heightDp * 0.025f).coerceIn(12f, 28f)
    val backdropBottomDp = immersiveHeroBackdropHeight(
        heroHeight = heroHeightDp.dp,
        immersiveContentBottomPadding = contentBottomPaddingDp.dp,
    ).value
    val rowBandTopDp = maxOf(heightDp * HOME_TV_STACKED_ROW_BAND_MIN_FRACTION, backdropBottomDp)
    val contentTopDp = heightDp * HOME_TV_STACKED_CONTENT_TOP_FRACTION
    return HomeTvStackedMetrics(
        heroHeightDp = heroHeightDp,
        backdropHeightDp = heightDp,
        sidePaddingDp = widthDp * HOME_TV_STACKED_SIDE_PADDING_FRACTION,
        cardInsetDp = widthDp * HOME_TV_STACKED_CARD_INSET_FRACTION,
        rowPitchDp = heightDp * HOME_TV_STACKED_ROW_PITCH_FRACTION * (guiScale / HOME_TV_STACKED_UI_SCALE_REFERENCE),
        contentBottomPaddingDp = contentBottomPaddingDp,
        textScale = HOME_TV_STACKED_SYNOPSIS_LINE_HEIGHT_FRACTION * widthDp / HOME_TV_STACKED_BODY_LARGE_LINE_HEIGHT_DP * guiScale *
            HOME_TV_STACKED_TEXT_SCALE_MULTIPLIER,
        logoWidthDp = widthDp * HOME_TV_STACKED_LOGO_WIDTH_FRACTION *
            (guiScale / HOME_TV_STACKED_UI_SCALE_REFERENCE),
        logoMetadataGapDp = HOME_TV_STACKED_LOGO_METADATA_GAP_DP * guiScale,
        rowBandTopDp = rowBandTopDp,
        contentTopDp = contentTopDp,
        contentHeightDp = (rowBandTopDp - contentTopDp - HOME_TV_STACKED_CONTENT_ROW_GAP_DP).coerceAtLeast(0f),
        rowBandFadeDp = HOME_TV_STACKED_ROW_BAND_FADE_DP,
        rowListTopDp = rowBandTopDp - HOME_TV_STACKED_ROW_BAND_FADE_DP,
        heroTextLeftDp = widthDp * HOME_TV_STACKED_SIDE_PADDING_FRACTION,
    )
}

/**
 * Top of the metadata line in the stacked hero: the bottom of the rendered logo plus the logo-to-
 * metadata gap. [logoHeightDp] is the logo's rendered height, or the title's when there is no logo;
 * it is zero until the logo has measured, so there is no reserved slot.
 */
internal fun homeTvStackedMetadataTopDp(metrics: HomeTvStackedMetrics, logoHeightDp: Float): Float =
    metrics.contentTopDp + logoHeightDp + metrics.logoMetadataGapDp

/** Which optional hero rows fit below the logo, and how many synopsis lines remain. */
internal data class HomeTvStackedHeroFit(
    val showRatings: Boolean,
    val showFooter: Boolean,
    val synopsisLines: Int,
)

/**
 * Row heights in the stacked hero column, in units of the synopsis line. These are estimates of
 * the theme's nominal line boxes, shared with the fit tests so a geometry change and the
 * expectations written against it cannot drift apart unnoticed.
 */
internal const val STACKED_GENRE_ROW_LINES = 1.1f
internal const val STACKED_RATINGS_ROW_LINES = 0.95f
internal const val STACKED_FOOTER_ROW_LINES = 1f
internal const val HOME_TV_STACKED_ROW_SPACER_DP = 10f

/**
 * Decides which optional rows the stacked hero keeps. Rows drop in order (footer first, then
 * ratings) until the metadata fits with at least [minLines] synopsis lines. Only then does the
 * synopsis give up lines, and never below [minLines]. The logo, the gap and the metadata line are
 * never dropped.
 *
 * The column runs: logo, gap, metadata line (genre row), a spacer, synopsis, then ratings and the
 * footer. [metadataTopDp] is where the metadata line starts (see [homeTvStackedMetadataTopDp]);
 * [limitDp] is the bottom of the text column. [lineHeightDp] is the synopsis line height.
 */
internal fun homeTvStackedHeroFit(
    metadataTopDp: Float,
    limitDp: Float,
    lineHeightDp: Float,
    hasGenreLine: Boolean,
    hasRatings: Boolean,
    hasFooter: Boolean,
    hasSynopsis: Boolean,
    maxLines: Int = HOME_TV_STACKED_SYNOPSIS_MAX_LINES,
    minLines: Int = HOME_TV_STACKED_SYNOPSIS_MIN_LINES,
): HomeTvStackedHeroFit {
    // Above the synopsis: the metadata line, then the spacer that separates it from the synopsis.
    var synopsisTopDp = metadataTopDp
    if (hasGenreLine) synopsisTopDp += STACKED_GENRE_ROW_LINES * lineHeightDp
    if (hasSynopsis) synopsisTopDp += HOME_TV_STACKED_ROW_SPACER_DP
    val candidates = listOf(
        hasRatings to hasFooter,
        hasRatings to false,
        false to false,
    )
    for ((ratings, footer) in candidates) {
        // Below the synopsis: ratings, then the footer.
        var extras = 0f
        if (ratings) extras += HOME_TV_STACKED_ROW_SPACER_DP + STACKED_RATINGS_ROW_LINES * lineHeightDp
        if (footer) extras += HOME_TV_STACKED_ROW_SPACER_DP + STACKED_FOOTER_ROW_LINES * lineHeightDp
        val available = limitDp - synopsisTopDp - extras
        val lines = if (!hasSynopsis) {
            maxLines
        } else {
            (available / lineHeightDp).toInt().coerceAtMost(maxLines)
        }
        val fits = if (!hasSynopsis) available >= 0f else lines >= minLines
        if (fits) {
            return HomeTvStackedHeroFit(ratings, footer, lines.coerceAtLeast(minLines).coerceAtMost(maxLines))
        }
    }
    // Nothing fits even with every optional row dropped: keep the minimum synopsis and let it run
    // long rather than hide the synopsis entirely.
    return HomeTvStackedHeroFit(showRatings = false, showFooter = false, synopsisLines = minLines)
}
