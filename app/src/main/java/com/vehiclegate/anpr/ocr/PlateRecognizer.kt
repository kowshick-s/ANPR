package com.vehiclegate.anpr.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vehiclegate.anpr.core.IndianPlateParser
import com.vehiclegate.anpr.core.ParsedPlate
import com.vehiclegate.anpr.core.PlateLocator
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Runs on-device OCR (ML Kit, bundled Latin model) on a frame and returns every piece of
 * text that is a valid Indian number plate. Handles:
 *  - single-line plates ("TN 09 AB 1234"), with extra text around them on the same line
 *  - two-line plates (top "TN 09", bottom "AB 1234") by pairing vertically stacked lines
 *  - the "IND" strip on HSRP plates
 */
class PlateRecognizer(
    /** Needed to load the AI plate models from the app's assets. */
    private val context: android.content.Context? = null,
    /** Use the AI plate detector + AI plate reader (otherwise the classic plate finder only). */
    private val useAi: Boolean = false
) {

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
    fun recognize(bitmap: Bitmap, maxUpscale: Float = 2f, callback: (List<Candidate>) -> Unit) =
        recognizeRaw(bitmap, maxUpscale, joinAll = false) { c, _ -> callback(c) }

    /** Like [recognize] but also returns the raw OCR text (for the preview's debug strip). */
    fun recognizeRaw(bitmap: Bitmap, maxUpscale: Float, joinAll: Boolean, callback: (List<Candidate>, String) -> Unit) {
        // ML Kit wants characters ≥ ~16 px tall; upscale small regions of interest.
        val scale = if (bitmap.width < MIN_OCR_WIDTH) min(maxUpscale, MIN_OCR_WIDTH.toFloat() / bitmap.width) else 1f
        val input = if (scale > 1.01f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap

        fun done(result: List<Candidate>, raw: String) {
            if (input !== bitmap) input.recycle()
            callback(result, raw)
        }

        try {
            recognizer.process(InputImage.fromBitmap(input, 0))
                .addOnSuccessListener(callbackExecutor) { text ->
                    val cands = try { extract(text, scale, joinAll) } catch (e: Exception) { emptyList() }
                    done(cands, text.text.replace('\n', ' ').trim())
                }
                .addOnFailureListener(callbackExecutor) { done(emptyList(), "") }
                .addOnCanceledListener(callbackExecutor) { done(emptyList(), "") }
        } catch (e: Exception) {
            done(emptyList(), "")
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

    /** One plate region that was located, prepared and read – shown in the preview for checking. */
    class DebugCrop(val region: Rect, val image: Bitmap, val text: String, val plate: String?)

    class PlateResult(
        val candidates: List<Candidate>,
        /** Regions the locator found (source-bitmap coordinates). */
        val regions: List<Rect>,
        val debug: List<DebugCrop>,
        val usedTiles: Boolean
    )

    /**
     * Plate-first recognition – the main pipeline:
     *  1. [PlateLocator] finds plate-like regions (white plate + characters, or aligned text).
     *  2. Each region is cropped with margin, straightened by its measured tilt, enlarged so the
     *     plate is ~420 px wide, converted to high-contrast grayscale, and read by OCR.
     *     Tilted plates are read both straightened and as-is.
     *  3. If nothing is read and [tilesFallback] is set, the whole image + 2×2 zoomed tiles are read.
     * Box coordinates are in [bitmap] coordinates. Caller keeps [bitmap] until [callback].
     * [callback] runs once on a background thread.
     */
    fun recognizePlates(
        bitmap: Bitmap,
        tilesFallback: Boolean,
        keepDebug: Boolean = false,
        callback: (PlateResult) -> Unit
    ) {
        workExecutor.execute {
            val ai = if (useAi && context != null) PlateAi.get(context) else null
            var aiFound = false
            val regions: List<Pair<Rect, Float>> = try {
                val fromAi = ai?.let { aiRegions(it, bitmap) } ?: emptyList()
                if (fromAi.isNotEmpty()) { aiFound = true; fromAi } else locate(bitmap)
            } catch (e: Exception) { emptyList() }
            val found = HashMap<String, Candidate>()
            val debug = ArrayList<DebugCrop>()

            // AI plate reader: a second, independent read of every plate region (votes with ML Kit).
            if (ai != null) for ((r, _) in regions.take(MAX_PLATE_CANDIDATES)) {
                try {
                    val pad = Rect(r).apply { inset(-(r.width() * 0.05f).toInt(), -(r.height() * 0.08f).toInt()) }
                    if (!pad.intersect(0, 0, bitmap.width, bitmap.height)) continue
                    val crop = Bitmap.createBitmap(bitmap, pad.left, pad.top, pad.width(), pad.height())
                    val read = ai.read(crop)
                    val parsed = read?.let { IndianPlateParser.parse(it.first) }
                    if (keepDebug) {
                        val thumbH = 120
                        val tw = (crop.width * thumbH / crop.height.coerceAtLeast(1)).coerceIn(1, 480)
                        debug += DebugCrop(r, Bitmap.createScaledBitmap(crop, tw, thumbH, true), "AI: ${read?.first ?: "-"}", parsed?.formatted)
                    }
                    if (crop !== bitmap) crop.recycle()
                    if (read != null && parsed != null) {
                        val c = Candidate(parsed, (read.second * AI_READ_WEIGHT).coerceIn(0f, 1f), Rect(r))
                        val prev = found[parsed.compact]
                        if (prev == null || c.score > prev.score) found[parsed.compact] = c
                    }
                } catch (_: Exception) { }
            }

            val jobs = ArrayList<Pair<Rect, Bitmap>>()
            for ((idx, pair) in regions.withIndex()) {
                val (r, angle) = pair
                try {
                    jobs += r to PlateImage.prepare(bitmap, r, angle)
                    // second, un-rotated attempt only for the best two regions (keeps scans fast)
                    if (abs(angle) > 8f && idx < 2) jobs += r to PlateImage.prepare(bitmap, r, 0f)
                } catch (_: Exception) { }
            }

            fun finish() {
                if (found.isEmpty() && tilesFallback && !aiFound) {
                    val tileRegions = listOf(Rect(0, 0, bitmap.width, bitmap.height)) +
                        tiles(bitmap.width, bitmap.height, 2, 2)
                    recognizeRegions(bitmap, tileRegions) { t ->
                        callback(PlateResult(t, regions.map { it.first }, debug, usedTiles = true))
                    }
                } else {
                    callback(PlateResult(found.values.sortedByDescending { it.score },
                        regions.map { it.first }, debug, usedTiles = false))
                }
            }

            fun next(i: Int) {
                if (i >= jobs.size) { finish(); return }
                val (region, img) = jobs[i]
                recognizeRaw(img, maxUpscale = 1f, joinAll = true) { cands, raw ->
                    val best = cands.firstOrNull()
                    if (keepDebug) {
                        val thumbH = 120
                        val tw = (img.width * thumbH / img.height.coerceAtLeast(1)).coerceIn(1, 480)
                        debug += DebugCrop(region, Bitmap.createScaledBitmap(img, tw, thumbH, true), raw, best?.plate?.formatted)
                    }
                    img.recycle()
                    for (c in cands) {
                        val placed = c.copy(box = Rect(region))
                        val prev = found[c.plate.compact]
                        if (prev == null || placed.score > prev.score) found[c.plate.compact] = placed
                    }
                    next(i + 1)
                }
            }
            next(0)
        }
    }

    /**
     * AI plate detector on the whole search area; if it finds nothing and the area is large
     * (small, distant plates), again on 2×2 zoomed tiles. Returns padded boxes, best first.
     */
    private fun aiRegions(ai: PlateAi, bitmap: Bitmap): List<Pair<Rect, Float>> {
        var dets = ai.detect(bitmap)
        if (dets.isEmpty() && max(bitmap.width, bitmap.height) > 800) {
            val all = ArrayList<PlateAi.Detection>()
            for (t in tiles(bitmap.width, bitmap.height, 2, 2)) {
                val tile = Bitmap.createBitmap(bitmap, t.left, t.top, t.width(), t.height())
                ai.detect(tile).forEach { d -> all += PlateAi.Detection(Rect(d.box).apply { offset(t.left, t.top) }, d.score) }
                tile.recycle()
            }
            // drop duplicates from overlapping tiles
            dets = all.sortedByDescending { it.score }.fold(ArrayList()) { acc, d ->
                if (acc.none { Rect.intersects(it.box, d.box) }) acc += d
                acc
            }
        }
        return dets.take(MAX_PLATE_CANDIDATES).map { d ->
            val r = Rect(d.box).apply { inset(-(d.box.width() * 0.06f).toInt(), -(d.box.height() * 0.1f).toInt()) }
            r.intersect(0, 0, bitmap.width, bitmap.height)
            r to 0f
        }
    }

    /** Runs [PlateLocator] on a ≤1280-wide grayscale copy; returns regions in bitmap coordinates. */
    private fun locate(bitmap: Bitmap): List<Pair<Rect, Float>> {
        val scale = min(1f, 1280f / bitmap.width)
        val w = max(1, (bitmap.width * scale).toInt())
        val h = max(1, (bitmap.height * scale).toInt())
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return PlateLocator.locate(px, w, h, MAX_PLATE_CANDIDATES).map { c ->
            Rect((c.left / scale).toInt(), (c.top / scale).toInt(), (c.right / scale).toInt(), (c.bottom / scale).toInt()) to c.angle
        }
    }

    /** Releases the OCR model. In-flight requests complete with an empty result. */
    fun close() {
        recognizer.close()
    }

    private class LineInfo(val text: String, val conf: Float, val box: Rect)

    private fun extract(text: Text, scale: Float, joinAll: Boolean = false): List<Candidate> {
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

        // A crop that contains only one plate: read all its lines top-to-bottom as one plate.
        if (joinAll && lines.size in 1..4) {
            val ordered = lines.sortedBy { it.box.top }
            offer(ordered.joinToString("") { it.text }, ordered.minOf { it.conf },
                Rect(ordered[0].box).apply { ordered.forEach { l -> union(l.box) } })
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

        private const val MAX_PLATE_CANDIDATES = 4
        /** AI reader is not trained on Indian plates: its reads count a little less than ML Kit's. */
        private const val AI_READ_WEIGHT = 0.85f
        /** Locating + preparing crops (CPU work) off the main thread, one frame at a time per app. */
        private val workExecutor = Executors.newSingleThreadExecutor()

        /** Shared and never shut down, so late ML Kit callbacks after close() are still delivered safely. */
        private val callbackExecutor = Executors.newSingleThreadExecutor()
        private const val MIN_OCR_WIDTH = 1280
        /** Used if the recogniser does not report a confidence. */
        private const val DEFAULT_CONF = 0.7f
    }
}
