package com.vehiclegate.anpr.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vehiclegate.anpr.core.IndianPlateParser
import com.vehiclegate.anpr.core.ParsedPlate
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Runs on-device OCR (ML Kit, bundled Latin model) on a frame and returns every piece of
 * text that is a valid Indian number plate. Handles:
 *  - single-line plates ("TN 09 AB 1234"), with extra text around them on the same line
 *  - two-line plates (top "TN 09", bottom "AB 1234") by pairing vertically stacked lines
 *  - the "IND" strip on HSRP plates
 */
class PlateRecognizer {

    data class Candidate(
        val plate: ParsedPlate,
        /** OCR confidence 0..1 */
        val confidence: Float,
        /** Plate location in the coordinates of the bitmap passed to [recognize]. */
        val box: Rect
    ) {
        val score: Float get() = confidence * plate.formatScore
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Asynchronously recognises plates in [bitmap]. [callback] is always invoked exactly once,
     * on a background thread. The caller keeps ownership of [bitmap].
     */
    fun recognize(bitmap: Bitmap, maxUpscale: Float = 2f, callback: (List<Candidate>) -> Unit) {
        // ML Kit wants characters ≥ ~16 px tall; upscale small regions of interest.
        val scale = if (bitmap.width < MIN_OCR_WIDTH) min(maxUpscale, MIN_OCR_WIDTH.toFloat() / bitmap.width) else 1f
        val input = if (scale > 1.01f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap

        fun done(result: List<Candidate>) {
            if (input !== bitmap) input.recycle()
            callback(result)
        }

        try {
            recognizer.process(InputImage.fromBitmap(input, 0))
                .addOnSuccessListener(callbackExecutor) { text ->
                    val cands = try { extract(text, scale) } catch (e: Exception) { emptyList() }
                    done(cands)
                }
                .addOnFailureListener(callbackExecutor) { done(emptyList()) }
                .addOnCanceledListener(callbackExecutor) { done(emptyList()) }
        } catch (e: Exception) {
            done(emptyList())
        }
    }

    /**
     * Zoom-in OCR: crops each region of [bitmap], upscales it (up to 3×) so small plates get
     * readable character heights, runs OCR on each in turn and merges the results.
     * Box coordinates are returned in [bitmap] coordinates. Caller keeps ownership of [bitmap].
     * [callback] is invoked once, on a background thread.
     */
    fun recognizeRegions(bitmap: Bitmap, regions: List<Rect>, callback: (List<Candidate>) -> Unit) {
        val valid = regions.mapNotNull { r ->
            val c = Rect(r)
            if (!c.intersect(0, 0, bitmap.width, bitmap.height) || c.width() < 16 || c.height() < 16) null else c
        }
        val merged = HashMap<String, Candidate>()
        fun next(i: Int) {
            if (i >= valid.size) {
                callback(merged.values.sortedByDescending { it.score }); return
            }
            val r = valid[i]
            val isFull = r.width() == bitmap.width && r.height() == bitmap.height
            val crop = if (isFull) bitmap else try {
                Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
            } catch (e: Exception) { next(i + 1); return }
            recognize(crop, maxUpscale = 3f) { cands ->
                if (crop !== bitmap) crop.recycle()
                for (c in cands) {
                    val moved = c.copy(box = Rect(c.box).apply { offset(r.left, r.top) })
                    val prev = merged[c.plate.compact]
                    if (prev == null || moved.score > prev.score) merged[c.plate.compact] = moved
                }
                next(i + 1)
            }
        }
        next(0)
    }

    /** Releases the OCR model. In-flight requests complete with an empty result. */
    fun close() {
        recognizer.close()
    }

    private class LineInfo(val text: String, val conf: Float, val box: Rect)

    private fun extract(text: Text, scale: Float): List<Candidate> {
        val found = HashMap<String, Candidate>()
        fun offer(raw: String, conf: Float, box: Rect) {
            val parsed = IndianPlateParser.parse(raw) ?: return
            val c = Candidate(parsed, conf, unscale(box, scale))
            val prev = found[parsed.compact]
            if (prev == null || c.score > prev.score) found[parsed.compact] = c
        }

        val lines = ArrayList<LineInfo>()
        for (block in text.textBlocks) for (line in block.lines) {
            val lineBox = line.boundingBox ?: continue
            val lineConf = safeConf(line.confidence)
            lines += LineInfo(line.text, lineConf, lineBox)

            // Every contiguous run of words on the line (plate may share a line with other text).
            val els = line.elements
            for (i in els.indices) {
                val sb = StringBuilder()
                var box: Rect? = null
                var confSum = 0f
                for (j in i until min(els.size, i + 6)) {
                    val e = els[j]
                    sb.append(e.text)
                    confSum += safeConf(e.confidence)
                    val eb = e.boundingBox
                    box = when {
                        box == null -> eb?.let { Rect(it) }
                        eb != null -> Rect(box).apply { union(eb) }
                        else -> box
                    }
                    if (sb.length >= 6 && box != null) offer(sb.toString(), confSum / (j - i + 1), box)
                    if (sb.length > 13) break
                }
            }
        }

        // Two-line plates: a line directly above another, horizontally overlapping, similar height.
        val limited = if (lines.size > 40) lines.subList(0, 40) else lines
        for (a in limited) for (b in limited) {
            if (a === b) continue
            val ah = a.box.height().toFloat()
            val bh = b.box.height().toFloat()
            if (ah <= 0 || bh <= 0) continue
            if (b.box.top <= a.box.top) continue
            val gap = b.box.top - a.box.bottom
            if (gap > ah * 0.9f || gap < -ah * 0.4f) continue
            val ratio = ah / bh
            if (ratio < 0.6f || ratio > 1.7f) continue
            val overlap = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
            if (overlap < 0.4f * min(a.box.width(), b.box.width())) continue
            offer(a.text + b.text, min(a.conf, b.conf), Rect(a.box).apply { union(b.box) })
        }

        return found.values.sortedByDescending { it.score }
    }

    private fun safeConf(c: Float): Float = if (c.isNaN() || c <= 0f) DEFAULT_CONF else c.coerceAtMost(1f)

    private fun unscale(r: Rect, scale: Float): Rect =
        if (scale == 1f) r else Rect((r.left / scale).toInt(), (r.top / scale).toInt(),
            (r.right / scale).toInt(), (r.bottom / scale).toInt())

    companion object {
        /** cols×rows grid of overlapping tiles covering a w×h image (for zoom-in OCR). */
        fun tiles(w: Int, h: Int, cols: Int, rows: Int, overlap: Float = 0.25f): List<Rect> {
            val tw = (w / (cols - (cols - 1) * overlap)).toInt()
            val th = (h / (rows - (rows - 1) * overlap)).toInt()
            val out = ArrayList<Rect>()
            for (r in 0 until rows) for (c in 0 until cols) {
                val x = if (cols == 1) 0 else ((w - tw) * c / (cols - 1))
                val y = if (rows == 1) 0 else ((h - th) * r / (rows - 1))
                out += Rect(x, y, x + tw, y + th)
            }
            return out
        }

        /** Shared and never shut down, so late ML Kit callbacks after close() are still delivered safely. */
        private val callbackExecutor = Executors.newSingleThreadExecutor()
        private const val MIN_OCR_WIDTH = 1280
        /** Used if the recogniser does not report a confidence. */
        private const val DEFAULT_CONF = 0.7f
    }
}
