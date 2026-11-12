package com.nuvio.app.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trips the encoder through zxing's own decoder: a grid that decodes back to the input is the
 * only proof that the module extraction (and therefore the pixels the login screen draws) is right.
 */
class QrCodeMatrixTest {

    @Test
    fun decodesBackToTheContent() {
        val content = "https://nuvio.tv/link?code=NHGSVP"
        val matrix = QrCodeEncoder.encode(content).getOrThrow()
        assertEquals(content, decode(matrix))
    }

    @Test
    fun decodesLongerPairingUrl() {
        val content = "https://api.nuvio.tv/link?code=BCDFGH&device=desktop&nonce=" + "a".repeat(32)
        val matrix = QrCodeEncoder.encode(content).getOrThrow()
        assertEquals(content, decode(matrix))
    }

    @Test
    fun gridIsSquareAndFinderPatternsSitInTheCorners() {
        val matrix = QrCodeEncoder.encode("https://nuvio.tv/link").getOrThrow()
        assertTrue(matrix.size >= 21, "size ${matrix.size}")
        // Every finder pattern is 7x7: dark ring, light ring, dark 3x3 centre, then a light separator.
        for ((x, y) in listOf(0 to 0, matrix.size - 7 to 0, 0 to matrix.size - 7)) {
            assertTrue(matrix.isDark(x, y), "finder ring at $x,$y")
            assertTrue(matrix.isDark(x + 6, y + 6), "finder ring corner at $x,$y")
            assertFalse(matrix.isDark(x + 1, y + 1), "finder light ring at $x,$y")
            assertTrue(matrix.isDark(x + 3, y + 3), "finder centre at $x,$y")
            if (x + 7 < matrix.size) assertFalse(matrix.isDark(x + 7, y), "separator right of $x,$y")
            if (y + 7 < matrix.size) assertFalse(matrix.isDark(x, y + 7), "separator below $x,$y")
        }
    }

    @Test
    fun emptyContentIsRejectedRatherThanProducingABlankCode() {
        assertTrue(QrCodeEncoder.encode("").isFailure)
    }

    private fun decode(matrix: QrCodeMatrix): String {
        val quietZone = 4
        val side = matrix.size + quietZone * 2
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (row in 0 until matrix.size) {
            for (column in 0 until matrix.size) {
                if (matrix.isDark(column, row)) {
                    pixels[(row + quietZone) * side + column + quietZone] = 0xFF000000.toInt()
                }
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }
}
