package com.nuvio.app.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.trailer_play_button
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.Bitmap

/** Actual desktop resource/painter rendering; no AWT peer, player, WebKit or display. */
@OptIn(ExperimentalComposeUiApi::class)
class ArtworkResourceRenderTest {
    private data class Render(val width: Int, val size: Int, val density: Float, val pixels: IntArray) {
        fun alpha(x: Float, y: Float): Int {
            val px = ((8 + size * x / 24) * density).toInt().coerceIn(0, width - 1)
            val py = ((8 + size * y / 24) * density).toInt().coerceIn(0, width - 1)
            return pixels[py * width + px] ushr 24
        }
    }

    private fun render(icon: AppIconResource?, size: Int, density: Float): Render {
        val side = ((size + 16) * density).roundToInt()
        val scene = ImageComposeScene(side, side, Density(density)) {
            Box(Modifier.padding(8.dp)) {
                val painter = if (icon == null) painterResource(Res.drawable.trailer_play_button)
                    else appIconPainter(icon)
                Image(painter, null, Modifier.size(size.dp), colorFilter = ColorFilter.tint(Color.White))
            }
        }
        try {
            scene.render().close()
            return scene.render(16_666_666L).use { image ->
                System.getProperty("nuvio.artwork.render.dir")?.let { directory ->
                    File(directory).mkdirs()
                    image.encodeToData()!!.use { data ->
                        File(directory, "${icon?.name ?: "Trailer"}-$size-$density.png").writeBytes(data.bytes)
                    }
                }
                Bitmap().use { bitmap ->
                    bitmap.allocN32Pixels(image.width, image.height)
                    assertTrue(image.readPixels(bitmap, 0, 0))
                    Render(side, size, density, IntArray(side * side) { i -> bitmap.getColor(i % side, i / side) })
                }
            }
        } finally { scene.close() }
    }

    @Test fun allSevenProductionPaintersRenderAndTintAtDesktopSizesAndScales() {
        for (icon in AppIconResource.entries + listOf(null)) {
            for (size in listOf(18, 24, 34)) {
                for (density in listOf(1f, 1.25f, 1.5f, 1.75f, 2f)) {
                    val result = render(icon, size, density)
                    val ink = result.pixels.filter { it ushr 24 > 32 }
                    assertTrue(ink.size > size * size * density * density * 0.08, "$icon/$size/$density invisible")
                    assertTrue(ink.size < size * size * density * density * 0.9, "$icon became a solid box")
                    // Exercise the same monochrome tint applied by player/detail Icon consumers.
                    assertTrue(ink.all { it and 0xffffff == 0xffffff }, "$icon does not preserve tint")
                    assertEquals(0, result.pixels.first(), "$icon paints outside its bounds")
                    assertEquals(0, result.pixels.last(), "$icon paints outside its bounds")
                }
            }
        }
    }

    @Test fun actualGlyphsPreservePlayPauseFrameSpeakerCaptionsAndPlusSemantics() {
        fun rendered(icon: AppIconResource?) = render(icon, 96, 1f)
        val pause = rendered(AppIconResource.PlayerPause)
        assertTrue(pause.alpha(6f, 12f) > 240)
        assertTrue(pause.alpha(18f, 12f) > 240)
        assertEquals(0, pause.alpha(12f, 12f))
        val play = rendered(AppIconResource.PlayerPlay)
        assertTrue(play.alpha(7f, 12f) > 240)
        assertEquals(0, play.alpha(20f, 4f))
        val frame = rendered(AppIconResource.PlayerAspectRatio)
        assertTrue(frame.alpha(3f, 12f) > 240)
        assertEquals(0, frame.alpha(12f, 12f))
        val speaker = rendered(AppIconResource.PlayerAudioFilled)
        assertTrue(speaker.alpha(12f, 7.5f) > 240)
        assertEquals(0, speaker.alpha(14f, 7.5f))
        val captions = rendered(AppIconResource.PlayerSubtitles)
        assertTrue(captions.alpha(1f, 12f) > 240)
        assertEquals(0, captions.alpha(12f, 12f))
        val plus = rendered(AppIconResource.LibraryAddPlus)
        assertTrue(plus.alpha(12f, 12f) > 240)
        assertEquals(0, plus.alpha(6f, 6f))
        val trailer = rendered(null)
        assertEquals(0, trailer.alpha(11f, 12f))
        assertTrue(trailer.alpha(4f, 12f) > 240)
    }
}
