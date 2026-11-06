package com.vehiclegate.anpr.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Finds likely number-plate regions in a grayscale frame BEFORE OCR, so the OCR can work on a
 * small, straightened, enlarged crop instead of the whole picture. Indian plates are bright
 * (white / yellow) rectangles with dark characters; two-wheeler plates are small, two-line and
 * often tilted.
 *
 * Two complementary cues:
 *  1. Bright-plate regions: connected bright areas (brighter than their surroundings) of plate
 *     shape that contain ≥2 dark character-like blobs. Their pixel moments give the tilt angle.
 *  2. Text lines: ≥3 dark character blobs of similar height, aligned and closely spaced
 *     (catches plates whose background is not clearly brighter, e.g. at night / IR).
 *
 * Pure Kotlin (no Android) – unit-testable on the JVM. Runs in ~20–60 ms on a 1280×720 frame.
 */
object PlateLocator {

    data class Candidate(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        /** Clockwise tilt of the plate in degrees (rotate by -angle to straighten). */
        val angle: Float,
        val score: Float,
        val chars: Int,
        val source: String
    ) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private class Blob(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int, val count: Int,
        val sx: Double, val sy: Double, val sxx: Double, val syy: Double, val sxy: Double
    ) {
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
    }

    /**
     * @param gray luminance 0..255, row-major, [w]×[h]
     * @param maxCandidates best regions to return, highest score first
     */
    fun locate(gray: IntArray, w: Int, h: Int, maxCandidates: Int = 6): List<Candidate> {
        val integral = integral(gray, w, h)

        // 1. Character-like dark blobs (dark text on a lighter local background).
        val darkMean = localMean(integral, w, h, 12)
        val dark = BooleanArray(w * h) { gray[it] < darkMean[it] - 12 }
        val maxCharH = max(80, h / 4)
        val chars = components(dark, w, h, eightWay = true).filter { b ->
            val ar = b.w.toFloat() / b.h
            val fill = b.count.toFloat() / (b.w * b.h)
            b.h in 5..maxCharH && ar in 0.12f..1.1f && fill >= 0.15f
        }

        val out = ArrayList<Candidate>()

        // 2. Bright plate-shaped regions containing characters.
        val brightMean = localMean(integral, w, h, 30)
        val bright = BooleanArray(w * h) { gray[it] > brightMean[it] + 18 && gray[it] > 90 }
        for (b in components(bright, w, h, eightWay = false)) {
            if (b.w < 18 || b.h < 10 || b.w > w * 0.6 || b.h > h * 0.5) continue
            val ar = b.w.toFloat() / b.h
            if (ar < 0.7f || ar > 6f) continue
            val fill = b.count.toFloat() / (b.w * b.h)
            if (fill < 0.3f) continue
            val inside = chars.count { c ->
                c.x0 >= b.x0 - 2 && c.y0 >= b.y0 - 2 && c.x1 <= b.x1 + 2 && c.y1 <= b.y1 + 2 &&
                    c.h >= b.h * 0.12f && c.h <= b.h * 0.75f
            }
            if (inside < 2) continue
            val score = min(1f, inside / 6f) * 0.6f + min(1f, fill) * 0.2f + 0.2f
            out += Candidate(b.x0, b.y0, b.x1 + 1, b.y1 + 1, tilt(b), score, inside, "plate")
        }

        // 3. Aligned character groups (text lines), merged into two-line plates when stacked.
        val lines = textLines(chars)
        val used = BooleanArray(lines.size)
        for (i in lines.indices) {
            if (used[i]) continue
            var l = lines[i]
            for (j in lines.indices) {
                if (i == j || used[j]) continue
                val m = lines[j]
                val hMax = max(l.charH, m.charH)
                val vGap = max(m.y0, l.y0) - min(m.y1, l.y1)
                val overlap = min(l.x1, m.x1) - max(l.x0, m.x0)
                if (l.y1 - l.y0 <= l.charH * 1.6f && m.y1 - m.y0 <= m.charH * 1.6f &&
                    vGap < hMax * 1.0f && vGap > -hMax * 0.3f && overlap > 0.4f * min(l.x1 - l.x0, m.x1 - m.x0) &&
                    abs(l.charH - m.charH) < 0.5f * hMax
                ) {
                    l = Line(min(l.x0, m.x0), min(l.y0, m.y0), max(l.x1, m.x1), max(l.y1, m.y1), l.n + m.n, max(l.charH, m.charH))
                    used[j] = true
                }
            }
            // A plate is at most two text lines tall and has ≤ ~14 characters.
            if (l.n in 4..14 && (l.y1 - l.y0) <= l.charH * 3.2f && (l.x1 - l.x0) <= w * 0.35f) {
                val score = min(1f, l.n / 9f) * 0.6f + 0.15f
                out += Candidate(l.x0, l.y0, l.x1, l.y1, 0f, score, l.n, "text")
            }
        }

        // 4. Non-maximum suppression, best first.
        val sorted = out.sortedByDescending { it.score }
        val keep = ArrayList<Candidate>()
        for (c in sorted) {
            if (keep.none { iou(it, c) > 0.3f || contains(it, c) }) keep += c
            if (keep.size >= maxCandidates) break
        }
        return keep
    }

    // ------------------------------------------------------------------ helpers

    private class Line(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val n: Int, val charH: Int)

    private fun textLines(chars: List<Blob>): List<Line> {
        val n = chars.size
        if (n == 0) return emptyList()
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        // sort by x so the inner loop can stop early
        val idx = chars.indices.sortedBy { chars[it].x0 }
        for (a in idx.indices) {
            val ca = chars[idx[a]]
            for (b in a + 1 until idx.size) {
                val cb = chars[idx[b]]
                val hm = max(ca.h, cb.h)
                if (cb.x0 - ca.x1 > hm * 1.0f) {
                    if (cb.x0 - ca.x1 > maxOf(ca.h, 80)) break
                    continue
                }
                if (abs(ca.h - cb.h) > 0.4f * hm) continue
                if (abs((ca.y0 + ca.y1) / 2f - (cb.y0 + cb.y1) / 2f) > 0.6f * hm) continue
                parent[find(idx[a])] = find(idx[b])
            }
        }
        return chars.indices.groupBy { find(it) }.values.filter { it.size >= 3 }.map { g ->
            val bs = g.map { chars[it] }
            Line(bs.minOf { it.x0 }, bs.minOf { it.y0 }, bs.maxOf { it.x1 } + 1, bs.maxOf { it.y1 } + 1,
                bs.size, bs.map { it.h }.sorted()[bs.size / 2])
        }
    }

    /** Major-axis angle of a blob from its second moments, folded into −45°..45°. */
    private fun tilt(b: Blob): Float {
        val n = b.count.toDouble()
        val mx = b.sx / n
        val my = b.sy / n
        val mu20 = b.sxx / n - mx * mx
        val mu02 = b.syy / n - my * my
        val mu11 = b.sxy / n - mx * my
        var a = Math.toDegrees(0.5 * atan2(2 * mu11, mu20 - mu02)).toFloat()
        if (a > 45f) a -= 90f
        if (a < -45f) a += 90f
        return a
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toFloat() * ih
        return inter / (a.width * a.height + b.width * b.height - inter)
    }

    private fun contains(a: Candidate, b: Candidate) =
        b.left >= a.left && b.top >= a.top && b.right <= a.right && b.bottom <= a.bottom

    private fun integral(g: IntArray, w: Int, h: Int): LongArray {
        val ii = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += g[y * w + x]
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row
            }
        }
        return ii
    }

    private fun localMean(ii: LongArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val stride = w + 1
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val s = ii[y1 * stride + x1] - ii[y0 * stride + x1] - ii[y1 * stride + x0] + ii[y0 * stride + x0]
                out[y * w + x] = (s / ((y1 - y0) * (x1 - x0))).toInt()
            }
        }
        return out
    }

    private fun components(mask: BooleanArray, w: Int, h: Int, eightWay: Boolean): List<Blob> {
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val out = ArrayList<Blob>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var cnt = 0
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                cnt++
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
                sx += x; sy += y; sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (!eightWay && dx != 0 && dy != 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (mask[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
                }
            }
            out += Blob(x0, y0, x1, y1, cnt, sx, sy, sxx, syy, sxy)
        }
        return out
    }
}
