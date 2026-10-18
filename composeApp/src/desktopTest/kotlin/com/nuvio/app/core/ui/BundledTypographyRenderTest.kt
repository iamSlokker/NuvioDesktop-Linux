package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

/** Raster-only Compose and font decoding: no AWT peer, JNI player, WebKit or display connection. */
@OptIn(ExperimentalComposeUiApi::class)
class BundledTypographyRenderTest {
    private fun resource(path: String): ByteArray = assertNotNull(javaClass.getResourceAsStream(path), path)
        .use { it.readBytes() }

    /** Upstream's bundled UI face, the same three weights the theme and the native HUD load. */
    @Test
    fun bundledFontsAreJetBrainsSansAtTheThemeWeights() {
        listOf("regular" to 400, "semibold" to 600, "bold" to 700).forEach { (name, weight) ->
            val bytes = resource("/composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_$name.ttf")
            Data.makeFromBytes(bytes).use { data ->
                assertNotNull(FontMgr.default.makeFromData(data)).use { typeface ->
                    assertTrue(typeface.familyName.startsWith("JetBrains Sans"), typeface.familyName)
                    assertEquals(weight, typeface.fontStyle.weight)
                    assertTrue(typeface.getStringGlyphs("Nuvio Playback Subtitles 0123456789").all { it.toInt() != 0 })
                }
            }
        }
        val css = resource("/player-ui/controls.css").decodeToString()
        assertTrue(css.contains("\"Nuvio JetBrains Sans\""))
        assertTrue(resource("/player-ui/controls.js").decodeToString().contains("Nuvio JetBrains Sans"))
    }

    private data class Render(val pixels: IntArray, val layouts: List<TextLayoutResult>)

    private fun render(widthDp: Int, density: Float, family: String = ""): Render {
        val layouts = arrayOfNulls<TextLayoutResult>(4)
        val scene = ImageComposeScene((widthDp * density).roundToInt(), (280 * density).roundToInt(), Density(density)) {
            NuvioTheme(appTheme = AppTheme.WHITE, appFontFamilyName = family) {
                Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                    listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold).forEachIndexed { index, weight ->
                        Text("Playback 01:23:45", Modifier.fillMaxWidth(), color = Color.White,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = weight,
                            onTextLayout = { layouts[index] = it })
                    }
                    Text("Subtitle language and playback settings remain readable when the window is narrow.",
                        Modifier.fillMaxWidth(), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        onTextLayout = { layouts[3] = it })
                }
            }
        }
        try {
            scene.render().close()
            return scene.render(16_666_666L).use { image ->
                Bitmap().use { bitmap ->
                    bitmap.allocN32Pixels(image.width, image.height)
                    assertTrue(image.readPixels(bitmap, 0, 0))
                    Render(IntArray(image.width * image.height) { i -> bitmap.getColor(i % image.width, i / image.width) },
                        layouts.map { assertNotNull(it) })
                }
            }
        } finally { scene.close() }
    }

    @Test
    fun sharedThemeRendersRegularSemiboldBoldAndWrappingAtAllDesktopScales() {
        for (density in listOf(1f, 1.25f, 1.5f, 1.75f, 2f)) {
            for (width in listOf(240, 360, 960)) {
                val result = render(width, density)
                assertTrue(result.pixels.count { (it and 0xffffff) != 0 } > 100)
                result.layouts.forEach { layout ->
                    assertFalse(layout.hasVisualOverflow, "$width/$density: ${layout.layoutInput.text}")
                    assertFalse(layout.didOverflowWidth)
                }
                result.layouts.take(3).forEach { assertEquals(1, it.lineCount) }
                if (width == 240) assertTrue(result.layouts.last().lineCount > 1)
            }
        }
    }

    @Test
    fun missingSystemFontFallsBackToIdenticalBundledLayoutAndPixels() {
        for (density in listOf(1f, 1.25f, 1.5f, 1.75f, 2f)) {
            val bundled = render(360, density)
            val missing = render(360, density, "Nuvio nonexistent font 02d66e")
            assertContentEquals(bundled.pixels, missing.pixels)
            assertEquals(bundled.layouts.map { it.size }, missing.layouts.map { it.size })
        }
    }
}
