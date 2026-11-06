package com.vehiclegate.anpr.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/** Turns a small, tilted, low-contrast plate region into an image OCR can read. */
object PlateImage {

    /** Target plate width after enlarging – characters end up ~35–60 px tall. */
    private const val TARGET_PLATE_W = 420f
    private const val TARGET_PLATE_H = 130f
    private const val MAX_SCALE = 6f
    private const val MAX_OUT = 1400
    private const val PAD = 0.35f

    /**
     * Crops [region] (+35 % margin) from [src], rotates it by −[angleDeg] (straightening a plate
     * tilted clockwise by angleDeg), enlarges it, and stretches contrast in grayscale.
     */
    fun prepare(src: Bitmap, region: Rect, angleDeg: Float): Bitmap {
        val rw = max(4, region.width())
        val rh = max(4, region.height())
        var scale = max(TARGET_PLATE_W / rw, TARGET_PLATE_H / rh).coerceIn(1f, MAX_SCALE)
        val outW0 = rw * (1 + 2 * PAD) * scale
        val outH0 = rh * (1 + 2 * PAD) * scale
        val shrink = min(1f, MAX_OUT / max(outW0, outH0))
        scale *= shrink
        val outW = max(16, (outW0 * shrink).toInt())
        val outH = max(16, (outH0 * shrink).toInt())

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(128, 128, 128))
        val m = Matrix().apply {
            postTranslate(-region.exactCenterX(), -region.exactCenterY())
            postRotate(-angleDeg)
            postScale(scale, scale)
            postTranslate(outW / 2f, outH / 2f)
        }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        enhance(out)
        return out
    }

    /** Grayscale + contrast stretch (2nd–98th percentile of the central area) + light sharpening. */
    private fun enhance(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h)
        val hist = IntArray(256)
        val cx0 = w / 5; val cx1 = w - w / 5; val cy0 = h / 5; val cy1 = h - h / 5
        for (i in px.indices) {
            val p = px[i]
            val v = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            g[i] = v
            val x = i % w; val y = i / w
            if (x in cx0 until cx1 && y in cy0 until cy1) hist[v]++
        }
        val total = hist.sum().coerceAtLeast(1)
        var acc = 0; var lo = 0; var hi = 255
        for (v in 0..255) { acc += hist[v]; if (acc >= total * 0.02) { lo = v; break } }
        acc = 0
        for (v in 255 downTo 0) { acc += hist[v]; if (acc >= total * 0.02) { hi = v; break } }
        val range = max(24, hi - lo)

        // unsharp mask (3×3 box blur) to recover edges lost in upscaling
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var sum = 0; var n = 0
            for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= h) continue
                for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= w) continue; sum += g[yy * w + xx]; n++ } }
            val sharp = g[i] + (g[i] - sum / n) * 2 / 5 // mild – strong sharpening turned 8 into E
            val v = ((sharp - lo) * 255 / range).coerceIn(0, 255)
            px[i] = Color.rgb(v, v, v)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
