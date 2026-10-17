package com.nuvio.app.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import com.nuvio.app.core.build.PackagedResources
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.setup.setupModeScreenshotPainter
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image as SkiaImage

/** Actual production painters, raster Compose only: no AWT peer, window, GTK or DISPLAY. */
@OptIn(ExperimentalComposeUiApi::class)
class ReleaseArtworkRenderTest {
    private val densities = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

    private fun pixels(scene: ImageComposeScene, name: String): IntArray {
        scene.render().close()
        return scene.render(16_666_666L).use { image ->
            System.getProperty("nuvio.artwork.render.dir")?.let { directory ->
                File(directory).mkdirs()
                image.encodeToData()!!.use { File(directory, "$name.png").writeBytes(it.bytes) }
            }
            Bitmap().use { bitmap ->
                bitmap.allocN32Pixels(image.width, image.height)
                assertTrue(image.readPixels(bitmap, 0, 0))
                IntArray(image.width * image.height) { bitmap.getColor(it % image.width, it / image.width) }
            }
        }
    }

    @Test fun actualSetupResourceDecoderKeepsAllFourFullResolutionDimensions() {
        for (mode in listOf("basic", "adaptive", "adaptive_ambient", "tv")) {
            val data = checkNotNull(PackagedResources.open("setup/setup_mode_$mode.webp")).use { it.readBytes() }
            SkiaImage.makeFromEncoded(data).use { image ->
                assertEquals(1920, image.width)
                assertEquals(1082, image.height)
                assertTrue(image.isOpaque)
            }
        }
    }

    @Test fun realSetupPaintersRemainDistinctAndFitWizardBoundsAtNarrowDesktopAndScaledWidths() {
        val cases = densities.flatMap { density -> listOf(320, 640).map { it to density } } + listOf(1920 to 1f)
        for ((logicalWidth, density) in cases) {
            val hashes = mutableSetOf<Int>()
            for (mode in HomeDisplayMode.entries) {
                var bounds = Rect.Zero
                val width = (logicalWidth * density).roundToInt()
                val height = (width * 9f / 16).roundToInt()
                val scene = ImageComposeScene(width, height, Density(density)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).onGloballyPositioned { bounds = it.boundsInRoot() }) {
                        Image(setupModeScreenshotPainter(mode), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    }
                }
                try {
                    val result = pixels(scene, "setup-${mode.name}-$logicalWidth-$density")
                    assertTrue(abs(bounds.width - width) <= 1 && abs(bounds.height - height) <= 1)
                    assertTrue(result.count { it ushr 24 > 200 } > result.size * .98, "$mode decode/fill failed")
                    assertTrue(result.count { (it shr 16 and 255) > 140 && (it shr 8 and 255) > 140 && (it and 255) > 140 } > result.size * .005,
                        "$mode UI text missing")
                    assertTrue(hashes.add(result.contentHashCode()), "$mode preview is not distinguishable")
                } finally { scene.close() }
            }
            assertEquals(4, hashes.size)
        }
    }
}
