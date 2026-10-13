package com.nuvio.app.features.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo_wordmark_htpc
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.Bitmap

/** Raster Compose only: no AWT peer, window, GTK, WebKit or DISPLAY connection. */
@OptIn(ExperimentalComposeUiApi::class)
class AuthWordmarkRenderTest {
    private data class Render(val bounds: Rect, val pixels: IntArray)

    private fun render(width: Int, density: Float, os: DesktopHostOs, legacy: Boolean = false): Render {
        var bounds = Rect.Zero
        val scene = ImageComposeScene(width, (180 * density).roundToInt(), Density(density)) {
            Column(
                Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val measured = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }
                    if (legacy) {
                        Image(painterResource(Res.drawable.app_logo_wordmark_htpc), null,
                            measured.fillMaxWidth(0.6f).height(48.dp), contentScale = ContentScale.Fit)
                    } else DesktopAuthWordmark(os, measured)
                }
            }
        }
        try {
            scene.render().close()
            return scene.render(16_666_666L).use { image ->
                System.getProperty("nuvio.logo.render.dir")?.let { directory ->
                    File(directory).mkdirs()
                    image.encodeToData()!!.use { data ->
                        File(directory, "${os.name}-${width}-${density}-${if (legacy) "before" else "after"}.png")
                            .writeBytes(data.bytes)
                    }
                }
                Bitmap().use { bitmap ->
                    bitmap.allocN32Pixels(image.width, image.height)
                    assertTrue(image.readPixels(bitmap, 0, 0))
                    Render(bounds, IntArray(image.width * image.height) { i -> bitmap.getColor(i % image.width, i / image.width) })
                }
            }
        } finally { scene.close() }
    }

    @Test fun linuxUsesAvailableWidthWithoutStretchingClippingOrLosingCenterAcrossScaleFactors() {
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) {
            for (width in listOf(360, 640, 1280, 1920, 2560)) {
                val result = render(width, density, DesktopHostOs.LINUX)
                val expectedWidth = min(width / density - 48f, 460f) * 0.6f * density
                assertTrue(abs(result.bounds.width - expectedWidth) <= 2f, "$width/$density: ${result.bounds}")
                assertTrue(abs(result.bounds.height - result.bounds.width * 344f / 1085f) <= 1f)
                assertTrue(abs(result.bounds.center.x - width / 2f) <= 1f, "Logo is not centered")
                assertTrue(result.bounds.left >= 0f && result.bounds.right <= width)
                assertTrue(result.pixels.count { (it and 0xffffff) != 0 } > 100, "Actual asset was not rendered")
            }
        }
    }

    @Test fun bazzite1080pHasAn88PixelIconInsteadOfTheUndersized48PixelImage() {
        val before = render(1920, 1f, DesktopHostOs.LINUX, legacy = true)
        val after = render(1920, 1f, DesktopHostOs.LINUX)
        assertEquals(48f, before.bounds.height)
        assertEquals(276f, after.bounds.width)
        assertTrue(after.bounds.height in 87f..88f)
        assertTrue(after.pixels.count { (it and 0xffffff) != 0 } >
            before.pixels.count { (it and 0xffffff) != 0 } * 2)
    }

    @Test fun windowsAndMacRetainTheExactPreviousLayoutAndPixels() {
        for (density in listOf(1f, 1.25f, 2f)) {
            val legacy = render(1280, density, DesktopHostOs.WINDOWS, legacy = true)
            for (os in listOf(DesktopHostOs.WINDOWS, DesktopHostOs.MACOS)) {
                val result = render(1280, density, os)
                assertEquals(legacy.bounds, result.bounds)
                assertTrue(legacy.pixels.contentEquals(result.pixels), "$os/$density changed")
            }
        }
    }
}
