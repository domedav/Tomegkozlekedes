package com.domedav.mavjegy.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

object BarcodeGenerator {

    enum class Type { AZTEC }

    /**
     * @param charset a kódolt bájtok karakterkészlete. Szerver-kód újrarenderelésnél
     *   ISO-8859-1 (bájt-hű körút az eredeti PDF417-hez; az UTF-8 ECI duplázná a
     *   128 feletti bájtokat a nyers streamben).
     */
    fun generate(content: String, type: Type, width: Int, height: Int, charset: String = "ISO-8859-1"): ImageBitmap {
        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to charset,
            EncodeHintType.MARGIN to 4
        )
        hints[EncodeHintType.ERROR_CORRECTION] = 50
        val matrix: BitMatrix = MultiFormatWriter().encode(
            content, BarcodeFormat.AZTEC, width, height, hints
        )
        val w = matrix.width
        val h = matrix.height
        // atlatszo hatter: csak a kod korul keskeny feher padding
        val q = (minOf(w, h) * 0.06f).toInt().coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(w + 2 * q, h + 2 * q, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        val padPx = q / 2
        // feher "papir" sav a kod mogott (kerekített)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
        }
        canvas.drawRoundRect(
            (q - padPx).toFloat(), (q - padPx).toFloat(),
            (w + q + padPx).toFloat(), (h + q + padPx).toFloat(),
            padPx.toFloat(), padPx.toFloat(), paint
        )
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, w, q, q, w, h)
        return bitmap.asImageBitmap()
    }
}
