package com.nuvio.app.features.trailer

import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlin.test.Test
import kotlin.test.assertEquals

class TrailerSeparateVideoHeightsTest {
    @Test fun windowsAndLinuxPrefer1440pThen4kThen1080p() {
        val expected = when (DesktopHostOs.current) {
            DesktopHostOs.WINDOWS, DesktopHostOs.LINUX -> listOf(1440, 2160, 1080)
            else -> listOf(1080)
        }
        assertEquals(expected, TrailerExtractionPlatform.preferredSeparateVideoHeights)
    }
}
