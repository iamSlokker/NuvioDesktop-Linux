package com.nuvio.app.features.qualicache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The artwork correction must not invent capabilities or change highlight selection. */
class QualityBadgeDetectionTest {
    @Test fun resolutionRequiresBoth4kAndAnIdentifiedSource() {
        assertTrue(qualityHighlightsFor(listOf("4K"), QualiCacheSettings()).isEmpty())
        assertTrue(qualityHighlightsFor(listOf("1080P", "BLURAY"), QualiCacheSettings()).isEmpty())
        for (token in listOf("BLURAY", "REMUX"))
            assertEquals(listOf(QualityHighlight.FourKBluRay), qualityHighlightsFor(listOf("4K", token), QualiCacheSettings()))
        for (token in listOf("WEBDL", "WEBRIP"))
            assertEquals(listOf(QualityHighlight.FourKWeb), qualityHighlightsFor(listOf("4K", token), QualiCacheSettings()))
    }

    @Test fun eachAudioAndDynamicRangeLabelRequiresItsDetectedToken() {
        for ((token, highlight) in listOf("DV" to QualityHighlight.DolbyVision, "HDR10" to QualityHighlight.Hdr,
            "HDR10+" to QualityHighlight.Hdr, "ATMOS" to QualityHighlight.DolbyAtmos, "DTSX" to QualityHighlight.Dts)) {
            assertEquals(listOf(highlight), qualityHighlightsFor(listOf(token), QualiCacheSettings()))
        }
        assertTrue(qualityHighlightsFor(listOf("DTS", "SDR", "AAC"), QualiCacheSettings()).isEmpty())
    }

    @Test fun duplicateMixedTokensStillProduceAtMostThreeInExistingPrecedenceOrder() {
        assertEquals(listOf(QualityHighlight.FourKBluRay, QualityHighlight.DolbyVision, QualityHighlight.DolbyAtmos),
            qualityHighlightsFor(listOf("4K", "4k", " bluray ", "WEBDL", "DV", "HDR10", "ATMOS", "DTSX"), QualiCacheSettings()))
    }
}
