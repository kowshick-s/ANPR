package com.vehiclegate.anpr.core

import java.util.Locale

/** One reading on a capture card: a plate and how much evidence the cameras gave for it. */
data class Reading(val compact: String, val formatted: String, val score: Float, val count: Int)

/**
 * Waiting-list logic: camera reads are merged into capture cards (one card per vehicle even when
 * OCR reads the same plate several different ways), and the counter search ranking.
 */
object CaptureLogic {

    /** "TN23BR7627=TN 23 BR 7627=4.120=7;…" → readings (best first). */
    fun decode(s: String): List<Reading> = s.split(';').mapNotNull { part ->
        val f = part.split('=')
        if (f.size < 4 || f[0].isBlank()) null
        else Reading(f[0], f[1], f[2].toFloatOrNull() ?: 0f, f[3].toIntOrNull() ?: 1)
    }.sortedByDescending { it.score }

    fun encode(list: List<Reading>): String = list.joinToString(";") {
        "${it.compact}=${it.formatted}=${String.format(Locale.US, "%.3f", it.score)}=${it.count}"
    }

    /**
     * Two reads are of the same vehicle if they differ in at most 2 characters, or end in the same
     * 4-digit number (OCR usually gets the digits right and confuses the letters).
     */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 6 && b.length >= 6 && levenshtein(a, b) <= 2) return true
        val da = a.takeLastWhile { it.isDigit() }
        val db = b.takeLastWhile { it.isDigit() }
        return da.length >= 4 && da == db
    }

    /**
     * Weaker link, only used for reads close in time: the numbers share 3 digits in a row
     * (a shifted or partial read: "0597" and "5973" share "597").
     */
    fun related(a: String, b: String): Boolean {
        val da = a.takeLastWhile { it.isDigit() }
        val db = b.takeLastWhile { it.isDigit() }
        if (da.length < 3 || db.length < 3) return false
        for (i in 0..da.length - 3) if (db.contains(da.substring(i, i + 3))) return true
        return false
    }

    /** Adds [r] to the readings: same plate → evidence adds up. Best first. */
    fun merge(list: List<Reading>, r: Reading): List<Reading> {
        val out = list.toMutableList()
        val i = out.indexOfFirst { it.compact == r.compact }
        if (i >= 0) out[i] = out[i].copy(score = out[i].score + r.score, count = out[i].count + r.count)
        else out += r
        return out.sortedByDescending { it.score }.take(MAX_READINGS)
    }

    /**
     * Counter search. Lower = better match, null = no match.
     * Digits only (e.g. "12") → an exact token comes first, then tokens starting with it,
     * then plates ending / containing it, then names containing it.
     */
    fun rank(query: String, plate: String, who: String?, model: String?): Int? {
        val q = query.trim()
        if (q.isEmpty()) return 0
        val qc = q.uppercase(Locale.US).filter { it.isLetterOrDigit() }
        val w = who?.trim().orEmpty()
        if (qc.isNotEmpty() && qc.all { it.isDigit() }) {
            return when {
                w == qc -> 0
                w.startsWith(qc) && w.all { it.isDigit() } -> 1
                plate.endsWith(qc) -> 2
                plate.contains(qc) -> 3
                w.contains(qc) -> 4
                else -> null
            }
        }
        return when {
            qc.isNotEmpty() && plate == qc -> 0
            qc.isNotEmpty() && plate.contains(qc) -> 1
            w.equals(q, ignoreCase = true) -> 1
            w.contains(q, ignoreCase = true) -> 2
            model?.contains(q, ignoreCase = true) == true -> 3
            else -> null
        }
    }

    private const val MAX_READINGS = 6
}
