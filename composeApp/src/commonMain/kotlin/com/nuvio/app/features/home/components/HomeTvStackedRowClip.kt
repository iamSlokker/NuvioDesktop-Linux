package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Linux TV Mode stacked rows: the rows layer starts at [rowListTopDp], which is one fade height above
 * the row band. Rows scrolled above that line are clipped out, so they cannot be hit, and the layer
 * fades in over [rowBandFadeDp] so the band edge is soft rather than a hard cut.
 */
internal fun Modifier.stackedRowListFrame(rowListTopDp: Float, rowBandFadeDp: Float): Modifier =
    padding(top = rowListTopDp.dp)
        .clipToBounds()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(0f to Color.Transparent, 1f to Color.Black),
                    startY = 0f,
                    endY = rowBandFadeDp.dp.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
