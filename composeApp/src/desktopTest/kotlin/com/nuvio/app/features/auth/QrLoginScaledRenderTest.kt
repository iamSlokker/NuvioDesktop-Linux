package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
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
 * The pairing page after the 1.25x scale the options page applies, rendered with Skia and read back
 * with a real QR reader.
 *
 * Scaling a rendered Compose subtree is the part most likely to go quietly wrong, and a symbol drawn
 * at the wrong moment blurs into something a phone cannot read while still looking fine on screen.
 * The decoder settles that with pixels.
 */
@OptIn(ExperimentalComposeUiApi::class)
class QrLoginScaledRenderTest {

    private val url = "https://nuvio.tv/link?code=NHGSVP"

    private fun state() = QrLoginUiState(
        phase = QrLoginPhase.Waiting,
        matrix = QrCodeEncoder.encode(url).getOrThrow(),
        userCode = "NHGSVP",
        expiresAtMillis = System.currentTimeMillis() + 120_000,
    )

    @Test
    fun `the code is scannable unscaled`() {
        assertEquals(url, decode(renderPage(scale = 1f)))
    }

    @Test
    fun `the code is scannable at the page's 125 percent scale`() {
        assertEquals(url, decode(renderPage(scale = 1.25f)))
    }

    @Test
    fun `the scale changes what is drawn, not only the layout`() {
        val unscaled = renderPage(scale = 1f)
        val scaled = renderPage(scale = 1.25f)

        // Same card, same content: the scaled one covers materially more pixels. A graphicsLayer
        // that silently did nothing would leave these equal.
        assertTrue(
            scaled.brightPixels > unscaled.brightPixels * 1.3,
            "scaled ${scaled.brightPixels} vs unscaled ${unscaled.brightPixels}",
        )
    }

    private class PageRender(val width: Int, val height: Int, val colors: IntArray) {
        val brightPixels: Int
            get() = colors.count { (it and 0xffffff) != 0 }

        /** The tight box round what the card draws: white code, light type, the button. */
        val ink: Rect
            get() {
                var left = width
                var top = height
                var right = 0
                var bottom = 0
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        if ((colors[y * width + x] and 0xffffff) != 0) {
                            if (x < left) left = x
                            if (y < top) top = y
                            if (x > right) right = x
                            if (y > bottom) bottom = y
                        }
                    }
                }
                return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            }
    }

    private fun renderPage(scale: Float): PageRender {
        val density = 2f
        // Generous on purpose: the drawn card grows past its layout box at 1.25, and a frame that
        // cut it short would measure the frame instead of the scale.
        // The card lays itself out at the full width it is offered, and the scale is drawn from its
        // top-left corner — so at 1.25 up to a quarter of the frame's width and height are overflow
        // rather than content. Both are sized from the same 360dp the panel reports.
        val widthPx = (380 * density * (1f + 0.6f * (scale - 1f))).roundToInt()
        val heightPx = (420 * density * scale).roundToInt()
        val scene = ImageComposeScene(widthPx, heightPx, Density(density)) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.background(Color.Black).testTag("page")) {
                    ScaledLikeOptionsPage(scale) {
                        QrLoginPanel(state = state(), onRefresh = {})
                    }
                }
            }
        }
        try {
            scene.render().close()
            return scene.render(16_666_666L).use { image ->
                Bitmap().use { bitmap ->
                    bitmap.allocN32Pixels(image.width, image.height)
                    assertTrue(image.readPixels(bitmap, 0, 0), "scene did not rasterize")
                    PageRender(
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

    /**
     * The options page's wrapper without its window-dependent slop: fixed dp applied outside the
     * graphics layer (inside it, `dp` has no density and any sized modifier collapses to 0), then
     * the scale applied to what is drawn.
     */
    @Composable
    private fun ScaledLikeOptionsPage(scale: Float, content: @Composable () -> Unit) {
        Box(Modifier.width(340.dp * scale)) {
            Box(
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                },
            ) { content() }
        }
    }

    /**
     * Reads the symbol out of the page, not the page itself.
     *
     * The card is white-on-black inside a black page, which a global binarizer reads as one dark
     * blob; cropping to the symbol (found from the ink bounds, inset past the card's padding) gives
     * the reader the light quiet zone it needs.
     */
    private fun decode(rendered: PageRender): String {
        val ink = rendered.ink
        // The panel's white square is the top of the card: crop generously inside it.
        val left = (ink.left + ink.width * 0.18f).toInt().coerceAtLeast(0)
        val top = (ink.top + ink.height * 0.03f).toInt().coerceAtLeast(0)
        val right = (ink.right - ink.width * 0.18f).toInt().coerceAtMost(rendered.width - 1)
        val bottom = (top + (right - left)).coerceAtMost(rendered.height - 1)
        val cropWidth = right - left
        val cropHeight = bottom - top
        val crop = IntArray(cropWidth * cropHeight) { index ->
            rendered.colors[(top + index / cropWidth) * rendered.width + left + index % cropWidth]
        }
        val source = RGBLuminanceSource(cropWidth, cropHeight, crop)
        return try {
            QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(source)),
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).text
        } catch (e: com.google.zxing.NotFoundException) {
            val bw = rendered.width / 2
            val bh = rendered.height / 2
            val crop = IntArray(bw * bh) { i ->
                rendered.colors[(i / bw) * 2 * rendered.width + (i % bw) * 2]
            }
            // 2x downscale: the symbol is drawn ~2.5 px per module here, which the reader's grid
            // sampling prefers at a smaller size.
            QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bw, bh, crop))),
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).text
        }
    }
}
