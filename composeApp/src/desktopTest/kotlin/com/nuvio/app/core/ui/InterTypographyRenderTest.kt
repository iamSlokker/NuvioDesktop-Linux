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
import com.nuvio.app.features.player.desktop.nativePlayerFontAssets
import com.nuvio.app.features.player.desktop.nativePlayerFontFaces
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

/** Raster-only Compose and font decoding: no AWT peer, JNI player, WebKit or display connection. */
@OptIn(ExperimentalComposeUiApi::class)
class InterTypographyRenderTest {
    private val hashes = listOf(
        "40d692fce188e4471e2b3cba937be967878f631ad3ebbbdcd587687c7ebe0c82",
        "78a843fade9d4612a5567302fb595b56976eb5fcebf4fea5a5912d638bafcde3",
        "288316099b1e0a47a4716d159098005eef7c0066921f34e3200393dbdb01947f",
    )

    private fun resource(path: String): ByteArray = assertNotNull(javaClass.getResourceAsStream(path), path)
        .use { it.readBytes() }

    @Test
    fun packagedComposeAndHudFontsAreThePinnedInterWeightsWithCompleteLicense() {
        assertEquals(3, nativePlayerFontAssets.size)
        nativePlayerFontAssets.forEachIndexed { index, font ->
            val bytes = resource(font.resourcePath)
            assertEquals(hashes[index], MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            Data.makeFromBytes(bytes).use { data ->
                assertNotNull(FontMgr.default.makeFromData(data)).use { typeface ->
                    assertTrue(typeface.familyName.startsWith("Inter"), typeface.familyName)
                    assertEquals(listOf(400, 600, 700)[index], typeface.fontStyle.weight)
                    assertTrue(typeface.getStringGlyphs("Nuvio Playback Subtitles 0123456789").all { it.toInt() != 0 })
                }
            }
        }
        val licenseRoot = "/composeResources/nuvio.composeapp.generated.resources/files/licenses/Inter/"
        assertTrue(resource(licenseRoot + "LICENSE.txt").decodeToString().contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(resource(licenseRoot + "NOTICE.txt").decodeToString().contains("Inter 4.1"))
        listOf("regular", "semibold", "bold").forEach { weight ->
            assertNull(javaClass.getResourceAsStream("/composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_$weight.ttf"))
        }
    }

    @Test
    fun hudCssUsesTheSameThreePackagedFontsWithoutLoadingTheNativePlayer() {
        val faces = nativePlayerFontFaces()
        assertEquals(3, Regex("@font-face").findAll(faces).count())
        nativePlayerFontAssets.forEach { font ->
            assertTrue(faces.contains("url(\"fonts/${font.filename}\")"))
            assertTrue(faces.contains("font-weight: ${font.cssWeight};"))
            assertTrue(resource(font.resourcePath).isNotEmpty())
        }
        val css = resource("/player-ui/controls.css").decodeToString()
            .replace("/* __NUVIO_PLAYER_FONT_FACES__ */", faces)
        assertFalse(css.contains("__NUVIO_PLAYER_FONT_FACES__"))
        assertTrue(css.contains("\"Nuvio Inter\""))
        assertTrue(resource("/player-ui/controls.js").decodeToString().contains("Nuvio Inter"))
        assertFalse(css.contains("JetBrains"))
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
    fun missingSystemFontFallsBackToIdenticalBundledInterLayoutAndPixels() {
        for (density in listOf(1f, 1.25f, 1.5f, 1.75f, 2f)) {
            val bundled = render(360, density)
            val missing = render(360, density, "Nuvio nonexistent font 02d66e")
            assertContentEquals(bundled.pixels, missing.pixels)
            assertEquals(bundled.layouts.map { it.size }, missing.layouts.map { it.size })
        }
    }
}
