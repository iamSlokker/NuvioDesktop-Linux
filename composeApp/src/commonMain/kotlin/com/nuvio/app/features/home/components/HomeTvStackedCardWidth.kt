package com.nuvio.app.features.home.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Landscape Continue Watching / Up Next / Upcoming card width for the Linux TV Mode stacked rows.
 *
 * Derivation: the TV card width is 210 × (180/110) × 1.2 = 412.36 dp. × 0.75 = 309.27 dp,
 * rounded to the nearest 5 dp = 310 dp. Height stays 16:9 of this width (the card's aspect ratio).
 */
internal const val HOME_TV_STACKED_LANDSCAPE_CARD_WIDTH_DP = 310f

/**
 * Set only by the stacked TV rows; null everywhere else, so the existing card width is used.
 * Read by ContinueWatchingCard in place of the shelf-derived width.
 */
internal val LocalHomeTvStackedContinueWatchingCardWidth: ProvidableCompositionLocal<Dp?> =
    staticCompositionLocalOf { null }

internal fun homeTvStackedContinueWatchingCardWidth(stackedRowsEnabled: Boolean): Dp? =
    if (stackedRowsEnabled) HOME_TV_STACKED_LANDSCAPE_CARD_WIDTH_DP.dp else null

/**
 * Landscape card width for a home row: the stacked Continue Watching width when the stacked rows
 * provide one, otherwise the row's own derived width. Catalog, Discover and collection rows use this
 * so every landscape row in stacked Linux TV Mode matches Continue Watching.
 */
internal fun homeTvStackedLandscapeRowWidth(stackedWidth: Dp?, derivedWidth: Dp): Dp =
    stackedWidth ?: derivedWidth
