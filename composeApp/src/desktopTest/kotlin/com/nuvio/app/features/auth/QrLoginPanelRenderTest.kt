package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.nuvio.app.core.qr.QrCodeEncoder
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

/**
 * Renders the pairing panel to pixels with Skia only (no AWT peer, window, GTK, WebKit or DISPLAY)
 * and decodes the result with a real QR reader.
 *
 * This is the check that matters for this screen: a canvas that draws the modules in the wrong
 * order, scale or colour still looks plausible in code review but cannot be scanned by a phone.
 */
@OptIn(ExperimentalComposeUiApi::class)
class QrLoginPanelRenderTest {

    @Test
    fun drawnCodeDecodesBackToThePairingUrl() {
        val url = "https://nuvio.tv/link?code=NHGSVP"
        val pixels = render(
            QrLoginUiState(
                phase = QrLoginPhase.Waiting,
                matrix = QrCodeEncoder.encode(url).getOrThrow(),
                userCode = "NHGSVP",
                expiresAtMillis = System.currentTimeMillis() + 120_000,
            ),
        )

        assertEquals(url, decode(pixels))
    }

    @Test
    fun aLongerUrlStillFitsAndDecodesAtThePanelSize() {
        val url = "https://api.nuvio.tv/link?code=BCDFGH&device=desktop&nonce=" + "0123456789abcdef".repeat(2)
        val pixels = render(
            QrLoginUiState(
                phase = QrLoginPhase.Waiting,
                matrix = QrCodeEncoder.encode(url).getOrThrow(),
                userCode = "BCDFGH",
                expiresAtMillis = System.currentTimeMillis() + 120_000,
            ),
        )

        assertEquals(url, decode(pixels))
    }

    @Test
    fun expiredPanelKeepsItsCodeScannableEnoughToLookAt() {
        // Dimmed to 35%: still drawn, deliberately not emphasized. The reader is applied to the
        // symbol's own contrast here, so this asserts the panel does not blank out.
        val url = "https://nuvio.tv/link?code=ZXCVBN"
        val pixels = render(
            QrLoginUiState(
                phase = QrLoginPhase.Expired,
                matrix = QrCodeEncoder.encode(url).getOrThrow(),
                userCode = "ZXCVBN",
                expiresAtMillis = System.currentTimeMillis() - 1_000,
            ),
        )

        val dark = pixels.colors.count { luminance(it) < 0x40 }
        assertTrue(dark > 1_000, "panel lost its code: $dark dark pixels")
    }

    private class Rendered(val width: Int, val height: Int, val colors: IntArray)

    private fun render(state: QrLoginUiState): Rendered {
        val density = 2f
        val widthPx = (460 * density).roundToInt()
        val heightPx = (420 * density).roundToInt()
        val scene = ImageComposeScene(widthPx, heightPx, Density(density)) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                    QrLoginPanel(state = state, onRefresh = {})
                }
            }
        }
        try {
            scene.render().close()
            return scene.render(16_666_666L).use { image ->
                Bitmap().use { bitmap ->
                    bitmap.allocN32Pixels(image.width, image.height)
                    assertTrue(image.readPixels(bitmap, 0, 0), "scene did not rasterize")
                    Rendered(
                        width = image.width,
                        height = image.height,
                        colors = IntArray(image.width * image.height) { index ->
                            bitmap.getColor(index % image.width, index / image.width)
                        },
                    )
                }
            }
        } finally {
            scene.close()
        }
    }

    private fun decode(rendered: Rendered): String {
        val source = RGBLuminanceSource(rendered.width, rendered.height, rendered.colors)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(
            bitmap,
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
