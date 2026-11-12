package com.vehiclegate.anpr.core

/**
 * A plate string that has been validated (and, where needed, corrected) against
 * the Indian registration-number format.
 *
 * @param compact     e.g. "TN09AB1234" – used for matching / storage
 * @param formatted   e.g. "TN 09 AB 1234" – used for display
 * @param formatScore 0..1, how cleanly the raw OCR text fitted the format
 *                    (1.0 = no corrections, lower = OCR characters had to be swapped)
 * @param corrections number of characters that were changed (O→0, 8→B, …)
 */
data class ParsedPlate(
    val compact: String,
    val formatted: String,
    val formatScore: Float,
    val corrections: Int
)

/**
 * Validates OCR text against Indian number-plate formats and fixes the classic
 * OCR confusions position-by-position (a "0" where a letter must be becomes "D"/"O",
 * an "8" where a letter must be becomes "B", a "B" where a digit must be becomes "8", …).
 *
 * Supported formats:
 *  - Standard:  SS DD LLL NNNN   e.g. TN 09 AB 1234, KA 01 A 1234, DL 3 CAB 1234
 *               (state code 2 letters, district 1–2 digits, series 0–3 letters, number 1–4 digits)
 *  - Bharat:    YY BH NNNN L(L)  e.g. 22 BH 1234 AA
 *  - HSRP "IND" prefix that OCR often reads together with the plate is stripped.
 */
object IndianPlateParser {

    val STATE_CODES: Set<String> = setOf(
        "AN", "AP", "AR", "AS", "BR", "CH", "CG", "DD", "DL", "DN", "GA", "GJ", "HR", "HP",
        "JK", "JH", "KA", "KL", "LA", "LD", "MP", "MH", "MN", "ML", "MZ", "NL", "OD", "OR",
        "PY", "PB", "RJ", "SK", "TN", "TS", "TG", "TR", "UP", "UK", "UA", "WB"
    )

    /** Digit that OCR produced where a LETTER is required → plausible letters. */
    private val DIGIT_TO_LETTERS: Map<Char, String> = mapOf(
        '0' to "DOQ", '1' to "ILT", '2' to "Z", '4' to "A", '5' to "S",
        '6' to "G", '7' to "T", '8' to "B", '3' to "B"
    )

    /** Letter that OCR produced where a DIGIT is required → plausible digit. */
    private val LETTER_TO_DIGIT: Map<Char, Char> = mapOf(
        'O' to '0', 'D' to '0', 'Q' to '0', 'U' to '0', 'C' to '0',
        'I' to '1', 'L' to '1', 'J' to '1',
        'Z' to '2', 'A' to '4', 'S' to '5', 'G' to '6', 'T' to '7', 'B' to '8', 'E' to '8'
    )

    /** Letter↔letter confusions, only used to rescue an invalid state code (TM → TN). */
    private val STATE_LETTER_CONFUSIONS: Map<Char, String> = mapOf(
        'M' to "NH", 'N' to "MH", 'H' to "NM", 'V' to "U", 'U' to "V",
        'K' to "X", 'C' to "G", 'G' to "C", 'P' to "RB", 'R' to "P", 'I' to "T", 'O' to "D"
    )

    private const val MIN_SCORE = 0.45f

    /** Default states seen at the site (Telangana: TS and the newer TG). Editable in Settings. */
    const val DEFAULT_PREFERRED = "TN,AP,TS,TG,KL,KA"

    /** States most vehicles at this site come from – preferred when correcting OCR mistakes. */
    @Volatile
    var preferredStates: Set<String> = parseStateList(DEFAULT_PREFERRED)

    /**
     * Strict mode (the app's default): only plates from [preferredStates] are accepted, and the
     * plate must have the full modern format – 2-digit district and 4-digit number. Misreads like
     * "CH 8 A 06" or "BR 9 046" are rejected instead of being logged.
     */
    @Volatile
    var strict: Boolean = false

    fun parseStateList(text: String): Set<String> =
        text.uppercase().split(',', ' ', ';', '/').map { it.trim() }.filter { it in STATE_CODES }.toSet()

    fun normalize(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    /** Returns the best interpretation of [raw] as an Indian plate, or null if it isn't one. */
    fun parse(raw: String): ParsedPlate? {
        val s = normalize(raw)
        val variants = linkedSetOf(s)
        // HSRP plates carry "IND" on the left; OCR often glues it on (also as "1ND"/"LND").
        for (prefix in listOf("IND", "1ND", "LND", "INO", "IN")) {
            if (s.startsWith(prefix) && s.length - prefix.length >= 6) variants += s.substring(prefix.length)
        }
        return variants.mapNotNull { parseNormalized(it) }.maxByOrNull { it.formatScore }
    }

    private fun parseNormalized(s: String): ParsedPlate? {
        if (s.length !in 6..11) return null
        var best: ParsedPlate? = null
        fun consider(p: ParsedPlate?) {
            if (p != null && (best == null || p.formatScore > best!!.formatScore)) best = p
        }

        // Standard format – try every split of the string into the four segments.
        for (d in intArrayOf(2, 1)) {
            for (n in intArrayOf(4, 3, 2, 1)) {
                val seriesLen = s.length - 2 - d - n
                if (seriesLen !in 0..3) continue
                consider(fitStandard(s, d, seriesLen, n))
            }
        }
        // Bharat series.
        if (s.length == 9 || s.length == 10) consider(fitBharat(s))

        return best?.takeIf { it.formatScore >= MIN_SCORE }
    }

    private fun fitStandard(s: String, districtLen: Int, seriesLen: Int, numberLen: Int): ParsedPlate? {
        var cost = 0
        val (state, stateCost) = fixState(s[0], s[1]) ?: return null
        cost += stateCost
        if (strict) {
            if (preferredStates.isNotEmpty() && state !in preferredStates) return null
            if (districtLen != 2 || numberLen != 4) return null
        }

        var i = 2
        val district = StringBuilder()
        repeat(districtLen) { val (c, k) = asDigit(s[i++]) ?: return null; district.append(c); cost += k }
        val series = StringBuilder()
        repeat(seriesLen) { val (c, k) = asLetter(s[i++]) ?: return null; series.append(c); cost += k }
        val number = StringBuilder()
        repeat(numberLen) { val (c, k) = asDigit(s[i++]) ?: return null; number.append(c); cost += k }

        if (district.all { it == '0' }) return null
        if (number.all { it == '0' }) return null

        var score = 1f - 0.1f * cost
        // States rarely seen at this site are trusted a little less.
        if (preferredStates.isNotEmpty() && state !in preferredStates) score -= 0.1f
        if (districtLen == 1) score -= 0.08f
        if (numberLen < 4) score -= 0.12f * (4 - numberLen)
        if (seriesLen == 0) score -= 0.05f
        // "I" and "O" are not issued in series letters – most likely a misread.
        if (series.any { it == 'I' || it == 'O' }) score -= 0.1f

        val compact = "$state$district$series$number"
        val formatted = listOf(state, district.toString(), series.toString(), number.toString())
            .filter { it.isNotEmpty() }.joinToString(" ")
        return ParsedPlate(compact, formatted, score.coerceIn(0f, 1f), cost)
    }

    private fun fitBharat(s: String): ParsedPlate? {
        var cost = 0
        val year = StringBuilder()
        for (i in 0..1) { val (c, k) = asDigit(s[i]) ?: return null; year.append(c); cost += k }
        val b = if (s[2] == 'B') 'B' else if (s[2] == '8' || s[2] == '3') { cost++; 'B' } else return null
        val h = if (s[3] == 'H') 'H' else if (s[3] == 'N' || s[3] == 'M') { cost++; 'H' } else return null
        val number = StringBuilder()
        for (i in 4..7) { val (c, k) = asDigit(s[i]) ?: return null; number.append(c); cost += k }
        val suffix = StringBuilder()
        for (i in 8 until s.length) { val (c, k) = asLetter(s[i]) ?: return null; suffix.append(c); cost += k }
        val score = 1f - 0.1f * cost
        return ParsedPlate("$year$b$h$number$suffix", "$year $b$h $number $suffix", score.coerceIn(0f, 1f), cost)
    }

    private fun asDigit(c: Char): Pair<Char, Int>? = when {
        c.isDigit() -> c to 0
        else -> LETTER_TO_DIGIT[c]?.let { it to 1 }
    }

    private fun asLetter(c: Char): Pair<Char, Int>? = when {
        c in 'A'..'Z' -> c to 0
        else -> DIGIT_TO_LETTERS[c]?.let { it[0] to 1 }
    }

    private fun letterOptions(c: Char): List<Pair<Char, Int>> {
        val out = ArrayList<Pair<Char, Int>>()
        if (c in 'A'..'Z') {
            out += c to 0
            STATE_LETTER_CONFUSIONS[c]?.forEach { out += it to 1 }
        } else {
            DIGIT_TO_LETTERS[c]?.forEach { out += it to 1 }
        }
        return out
    }

    /** Picks the cheapest valid state code reachable from the two OCR chars. */
    /**
     * Picks the state code for the two OCR chars. States in [preferredStates] (the ones actually
     * seen at this site) win over others when they are at most one correction more expensive, and
     * may be reached by replacing one unreadable character ("KH" → KA, "IN" → TN).
     */
    private fun fixState(a: Char, b: Char): Pair<String, Int>? {
        val preferred = preferredStates
        var best: Pair<String, Int>? = null
        var bestPreferred: Pair<String, Int>? = null
        for ((x, cx) in letterOptions(a)) for ((y, cy) in letterOptions(b)) {
            val code = "$x$y"
            val cost = cx + cy
            if (code !in STATE_CODES) continue
            if (best == null || cost < best.second) best = code to cost
            if (code in preferred && (bestPreferred == null || cost < bestPreferred.second)) bestPreferred = code to cost
        }
        if (bestPreferred == null) {
            // one character matches a preferred state exactly, the other is unreadable
            // only when exactly one preferred state fits – "KH" could be KA or KL, so no guess
            val fits = preferred.filter { it.length == 2 && ((it[0] == a) xor (it[1] == b)) }
            if (fits.size == 1) bestPreferred = fits[0] to 2
        }
        val bp = bestPreferred
        return when {
            bp != null && (best == null || bp.second <= best.second + 1) -> bp
            else -> best
        }
    }
}

/** Classic edit distance – used to decide whether two reads are the same vehicle. */
fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}
