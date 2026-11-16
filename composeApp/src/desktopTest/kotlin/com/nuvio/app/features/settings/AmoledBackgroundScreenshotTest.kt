package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import com.nuvio.app.core.ui.LocalPureBlackBackground
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AMOLED's page backdrop must be genuinely black. The shared sky background used to paint a night
 * wash and two radial glows even when the theme asked for #000000; this renders it and reads the
 * pixels back, and writes a PNG for a visual check at build/test-screenshots.
 */
@OptIn(ExperimentalTestApi::class)
class AmoledBackgroundScreenshotTest {
    @Test
    fun amoledSkyBackgroundIsPureBlack() = runComposeUiTest {
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalPureBlackBackground provides true) {
                Box(Modifier.fillMaxSize().settingsSkyBackground(Color.Black, Color(0xFF9ECAFF)))
            }
        }
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        val dir = File("build/test-screenshots").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, "amoled-background.png"))
        // Corners and centre: no wash and no glows may leave a non-black pixel.
        listOf(
            0 to 0,
            image.width - 1 to 0,
            image.width / 2 to image.height / 2,
        ).forEach { (x, y) ->
            val argb = image.getRGB(x, y)
            assertEquals(0, argb and 0xFFFFFF, "AMOLED background is not #000000 at $x,$y")
        }
    }
}
