package com.nuvio.app.core.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR symbol as a module grid.
 *
 * The symbol is encoded once and handed to the UI as plain modules, so the login screen can draw
 * it at any size, with its own quiet zone and corner rounding, without a bitmap or a re-encode per
 * frame. Nothing here is Compose- or Android-specific: the same matrix can be rendered by a canvas,
 * written to an image, or checked by a decoder test.
 */
class QrCodeMatrix internal constructor(
    val size: Int,
    private val modules: BooleanArray,
) {
    init {
        require(size > 0) { "QR size must be positive, was $size" }
        require(modules.size == size * size) {
            "QR matrix of $size modules needs ${size * size} cells, got ${modules.size}"
        }
    }

    fun isDark(column: Int, row: Int): Boolean = modules[row * size + column]
}

object QrCodeEncoder {
    /**
     * Encodes [content] at the smallest version that holds it.
     *
     * [ErrorCorrectionLevel.M] recovers ~15% of a damaged symbol, which is the usual choice for a
     * code displayed on a screen and read off a glossy phone camera; the TV app uses the same.
     */
    fun encode(
        content: String,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Result<QrCodeMatrix> = runCatching {
        require(content.isNotEmpty()) { "QR content must not be empty" }
        val encoded = Encoder.encode(content, errorCorrection)
        val matrix = encoded.matrix
        val size = matrix.width
        val modules = BooleanArray(size * size)
        for (row in 0 until size) {
            for (column in 0 until size) {
                modules[row * size + column] = matrix.get(column, row).toInt() == 1
            }
        }
        QrCodeMatrix(size, modules)
    }
}
