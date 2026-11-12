package com.nuvio.app.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform presentation of the approved HTPC wordmark; the source artwork is unchanged. */
@Composable
internal expect fun AuthWordmark(modifier: Modifier = Modifier)

/**
 * The same wordmark for a page with a fixed height budget: it is scaled to fit the box the caller
 * gives it instead of deciding its own size.
 *
 * [widthFraction] is how much of that box's width the lockup may use. The default keeps the small
 * centred lockup above the sign-in cards; the page's brand column passes 1f so the box it sizes is
 * the logo itself.
 *
 * Separate from [AuthWordmark] on purpose. That one's sizing is pinned by pixel tests on every
 * platform, and the sign-in page's need (a short, centred lockup above a fixed-height layout) is
 * not a reason to move those numbers.
 */
@Composable
internal expect fun CompactAuthWordmark(
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.45f,
)
