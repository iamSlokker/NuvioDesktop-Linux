package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Compose stores colour alpha at 8 bits, so alpha reads are good to 1/255. */
private const val ALPHA_TOLERANCE = 0.005f

/**
 * The dithered gradient's stop cap. The shader's loop bound is a compile-time constant, so a ramp
 * denser than [NuvioScrimRamp.MAX_STOPS] cannot be handed to it as written. It used to be refused at
 * construction, which took the whole UI down with an unredrawable dialog; it is resampled into the
 * cap instead.
 */
class NuvioDitheredGradientStopsTest {
    /** Where [denseRamp] reaches full opacity, 5/8 of the way down it. */
    private val denseRampEnd = 0.625f

    /**
     * The shape a dense producer makes: a smoothstep sampled at 17 points up to [denseRampEnd], then
     * held solid to the end of the ramp.
     */
    private fun denseRamp(): List<Pair<Float, Color>> {
        val samples = (0..16).map { i ->
            val t = i / 16f
            (denseRampEnd * t) to Color.Black.copy(alpha = t * t * (3f - 2f * t))
        }
        return samples + (1f to Color.Black)
    }

    /** The ramp's own alpha at [position], read straight off its piecewise-linear curve. */
    private fun rampAlphaAt(ramp: List<Pair<Float, Color>>, position: Float): Float {
        if (position <= ramp.first().first) return ramp.first().second.alpha
        for (i in 0 until ramp.size - 1) {
            val (loPos, loColor) = ramp[i]
            val (hiPos, hiColor) = ramp[i + 1]
            if (position > hiPos) continue
            val span = hiPos - loPos
            val t = if (span <= 0f) 1f else (position - loPos) / span
            return loColor.alpha + (hiColor.alpha - loColor.alpha) * t
        }
        return ramp.last().second.alpha
    }

    @Test
    fun aDenseRampIsResampledIntoTheCapRatherThanRefused() {
        val capped = cappedToShaderStops(denseRamp())
        assertEquals(NuvioScrimRamp.MAX_STOPS, capped.size)
    }

    @Test
    fun theDitheredGradientAcceptsADenseRamp() {
        // The regression itself: this threw "dithered gradients take 1..6 stops", and pressing OK on
        // the dialog re-threw it on the next recomposition, so the window never drew again.
        nuvioDitheredVerticalGradient(denseRamp().toTypedArray())
    }

    @Test
    fun aRampThatAlreadyFitsIsPassedThroughUntouched() {
        val stops = listOf(
            0f to Color.Black,
            0.5f to Color.Black.copy(alpha = 0.5f),
            1f to Color.Black.copy(alpha = 0f),
        )
        assertSame(stops, cappedToShaderStops(stops))
    }

    @Test
    fun resamplingKeepsBothEndpointsAndTheSpanBetweenThem() {
        val original = denseRamp()
        val capped = cappedToShaderStops(original)
        assertEquals(original.first().first, capped.first().first, 1e-6f)
        assertEquals(original.first().second.alpha, capped.first().second.alpha, ALPHA_TOLERANCE)
        assertEquals(original.last().first, capped.last().first, 1e-6f)
        assertEquals(original.last().second.alpha, capped.last().second.alpha, ALPHA_TOLERANCE)
        assertTrue(capped.map { it.first }.zipWithNext().all { (a, b) -> b > a }, "positions must ascend")
    }

    @Test
    fun resampledStopsReadTheRampsOwnAlphaAtTheirPositions() {
        val original = denseRamp()
        cappedToShaderStops(original).forEachIndexed { index, (position, color) ->
            assertEquals(
                rampAlphaAt(original, position),
                color.alpha,
                ALPHA_TOLERANCE,
                "stop $index sits at $position",
            )
        }
    }

    @Test
    fun theEasedShapeSurvivesResampling() {
        // The samples land on the curve they were taken from, not just on its endpoints.
        val eased = cappedToShaderStops(denseRamp()).filter { it.first <= denseRampEnd }
        eased.forEachIndexed { index, (position, color) ->
            val t = position / denseRampEnd
            assertEquals(t * t * (3f - 2f * t), color.alpha, 0.02f, "stop $index sits at $position")
        }
    }

    @Test
    fun aSolidTailStaysSolidThroughResampling() {
        // Dropping the surplus stops instead of resampling would end the fade at the sixth stop and
        // hold its alpha across the rest of the ramp; the flat run at the end has to survive.
        val capped = cappedToShaderStops(denseRamp())
        assertEquals(1f, capped[capped.size - 2].second.alpha, ALPHA_TOLERANCE)
        assertEquals(1f, capped.last().second.alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun blendingFollowsPremultipliedSpaceAsTheShaderDoes() {
        // A colour faded out to a transparent stop keeps its own channels: interpolating straight
        // component-wise instead would pull it halfway towards the transparent stop's channels,
        // while the shader mixes premultiplied values and so does not.
        val ramp = List(8) { i -> (i * 0.1f) to Color.Red } + (1f to Color.Blue.copy(alpha = 0f))
        val capped = cappedToShaderStops(ramp)
        assertEquals(NuvioScrimRamp.MAX_STOPS, capped.size)
        capped.forEach { (position, color) ->
            assertEquals(1f, color.red, 1e-4f, "red at $position")
            assertEquals(0f, color.blue, 1e-4f, "blue at $position")
        }
        assertEquals(0f, capped.last().second.alpha, ALPHA_TOLERANCE)
    }
}
