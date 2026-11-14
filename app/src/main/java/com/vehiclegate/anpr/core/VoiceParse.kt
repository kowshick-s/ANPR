package com.vehiclegate.anpr.core

import java.util.Locale

/**
 * Turns what staff said into a plate number, token / name and model.
 *
 * Works with English and colloquial Tamil, in Tamil script or romanised ("Tanglish"), mixed freely:
 *   "TN 23 AU 5973 token 29 Activa"
 *   "டி என் இருபத்தி மூணு ஏ யு அஞ்சு ஒம்போது ஏழு மூணு டோக்கன் பதினொன்னு"
 *   "tn irubathi moonu a u fifty nine seventy three token pathinonu"
 * Numbers are read as written digits, English words or Tamil words (colloquial or formal); letters as
 * letters or their spoken names ("ay", "bee", "டி", "என்"). Speech recognisers return several
 * alternatives: [parse] picks the first one that contains a valid plate.
 */
object VoiceParse {

    data class Spoken(
        val plate: ParsedPlate?,
        /** Token number or name (whatever followed "token" / "name", or a lone number) */
        val who: String?,
        /** Best model matches, best first */
        val models: List<String>,
        val heard: String
    )

    /** Parse the recogniser's alternatives; the first with a valid plate wins (else the first). */
    fun parse(alternatives: List<String>, knownModels: List<String>): Spoken? {
        if (alternatives.isEmpty()) return null
        val parsed = alternatives.map { parseOne(it, knownModels) }
        return parsed.firstOrNull { it.plate != null } ?: parsed.first()
    }

    /** Spoken search: a plate, a token number or a name. */
    fun searchText(alternatives: List<String>): String {
        if (alternatives.isEmpty()) return ""
        for (a in alternatives) {
            val toks = tokenize(a)
            plateIn(toks)?.let { return it.formatted }
        }
        val toks = tokenize(alternatives.first())
        val nums = toks.filterIsInstance<Tok.Num>()
        if (nums.isNotEmpty() && toks.none { it is Tok.Word && it.latin.length > 2 && it.kind == null }) {
            return nums.joinToString("") { it.digits }
        }
        return alternatives.first().trim()
    }

    /** Model names that best match what was said (best first). */
    fun matchModels(spoken: String, knownModels: List<String>, max: Int = 6): List<String> {
        val words = tokenize(spoken).mapNotNull {
            when (it) {
                is Tok.Word -> it.latin
                is Tok.Num -> it.digits
                is Tok.Letter -> it.primary.lowercase(Locale.US)
            }
        }.map { simp(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val joined = words.joinToString("")
        return knownModels.map { m ->
            val mw = m.lowercase(Locale.US).split(' ', '-', '+').map { simp(it) }.filter { it.isNotEmpty() }
            var score = 0.0
            for (w in mw) {
                val best = words.maxOf { similarity(it, w) }
                if (best >= 0.75) score += best * w.length
                else if (w.length >= 3 && joined.contains(w)) score += 0.8 * w.length
            }
            m to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    // ------------------------------------------------------------------ parsing

    fun parseOne(raw: String, knownModels: List<String>): Spoken {
        val toks = tokenize(raw)
        // keywords split the sentence: [plate …] token/name … model …
        var who: String? = null
        var modelPart = ArrayList<Tok>()
        val platePart = ArrayList<Tok>()
        var mode = 0 // 0 plate, 1 token/name, 2 model
        val whoWords = ArrayList<Tok>()
        for (t in toks) {
            if (t is Tok.Word && t.kind != null) {
                mode = when (t.kind) { "token", "name" -> 1; "model" -> 2; else -> mode }
                continue
            }
            when (mode) {
                0 -> platePart += t
                1 -> whoWords += t
                else -> modelPart += t
            }
        }
        if (whoWords.isNotEmpty()) {
            val nums = whoWords.filterIsInstance<Tok.Num>()
            who = if (nums.isNotEmpty() && whoWords.all { it is Tok.Num }) nums.joinToString("") { it.digits }
            else {
                // name: words after "name"; a trailing model word goes to the model
                val text = whoWords.joinToString(" ") { it.original }
                text
            }
        }
        val plate = plateIn(platePart)
        // words around the plate that are not part of it may be the model (e.g. "TN 23 AU 5973 Activa")
        val leftovers = if (modelPart.isEmpty()) platePart.filterIsInstance<Tok.Word>() else modelPart
        var models = matchModels(leftovers.joinToString(" ") { it.original }, knownModels)
        // a name may have swallowed the model ("name Ravi Activa")
        if (models.isEmpty() && who != null && whoWords.any { it is Tok.Word }) {
            val m = matchModels(whoWords.joinToString(" ") { it.original }, knownModels)
            if (m.isNotEmpty()) {
                models = m
                val first = m.first().lowercase(Locale.US).split(' ').map { simp(it) }
                val keep = whoWords.filter { w -> w !is Tok.Word || first.none { similarity(simp(w.latin), it) >= 0.75 } }
                who = keep.joinToString(" ") { it.original }.ifBlank { null }
            }
        }
        // a lone number after the plate (no keyword) = token
        if (who == null && plate != null) {
            val run = platePart.filter { it !is Tok.Word }
            val all = run.joinToString("") { it.chars() }
            val idx = all.indexOf(plate.compact)
            if (idx >= 0) {
                val tail = all.substring(idx + plate.compact.length)
                if (tail.isNotEmpty() && tail.length <= 4 && tail.all { it.isDigit() }) who = tail
            }
        }
        return Spoken(plate, who?.trim()?.ifBlank { null }, models, raw)
    }

    /** Best valid plate inside the letters/numbers that were said. */
    private fun plateIn(toks: List<Tok>): ParsedPlate? {
        val run = toks.filter { it !is Tok.Word || it.latin.length <= 2 && it.latin.all { c -> c.isLetter() } }
            .flatMap { if (it is Tok.Word) it.latin.uppercase(Locale.US).map { c -> Tok.Letter(c.toString(), null, c.toString()) } else listOf(it) }
        if (run.isEmpty()) return null
        var best: ParsedPlate? = null
        fun consider(s: String) {
            val p = IndianPlateParser.parse(s) ?: return
            val b = best
            if (b == null || p.compact.length > b.compact.length ||
                (p.compact.length == b.compact.length && p.formatScore > b.formatScore)) best = p
        }
        for (variant in letterVariants(run)) {
            // whole run, then every sub-run (filler like "number", stray words around it)
            for (i in variant.indices) for (j in variant.length downTo i + 6) consider(variant.substring(i, j))
        }
        return best
    }

    /** Tamil letter names are ambiguous (டி = T or D, பி = B or P): try the alternatives too. */
    private fun letterVariants(run: List<Tok>): List<String> {
        var out = listOf("")
        var ambiguous = 0
        for (t in run) {
            out = if (t is Tok.Letter && t.alt != null && ambiguous < 4) {
                ambiguous++
                out.flatMap { listOf(it + t.primary, it + t.alt) }
            } else out.map { it + t.chars() }
        }
        return out
    }

    // ------------------------------------------------------------------ tokens

    sealed class Tok {
        abstract val original: String
        abstract fun chars(): String
        class Num(val digits: String, override val original: String) : Tok() { override fun chars() = digits }
        class Letter(val primary: String, val alt: String?, override val original: String) : Tok() { override fun chars() = primary }
        /** [latin] = romanised form; [kind] = keyword ("token", "name", "model") */
        class Word(val latin: String, val kind: String?, override val original: String) : Tok() { override fun chars() = "" }
    }

    fun tokenize(raw: String): List<Tok> {
        var s = " " + raw.lowercase(Locale.US) + " "
        s = s.replace(Regex("[.,:;!?/\\\\()\\[\\]'\"_-]"), " ")
        for ((k, v) in PHRASES) s = s.replace(k, v)
        // split letters from digits ("tn23" → "tn 23") and Tamil from Latin
        s = s.replace(Regex("(?<=[0-9])(?=[^0-9\\s])|(?<=[^0-9\\s])(?=[0-9])"), " ")
        s = s.replace(Regex("(?<=[\\u0B80-\\u0BFF])(?=[a-z])|(?<=[a-z])(?=[\\u0B80-\\u0BFF])"), " ")
        val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val out = ArrayList<Tok>()
        var i = 0
        var repeat = 1
        while (i < words.size) {
            val w = words[i]
            val latin = if (isTamil(w)) translit(w) else w
            val sw = simp(latin)
            // "double five" / "triple zero"
            if (sw in DOUBLE) { repeat = 2; i++; continue }
            if (sw in TRIPLE) { repeat = 3; i++; continue }
            val kw = KEYWORDS.entries.firstOrNull { (k, _) -> k.any { simp(it) == sw || (sw.length >= 4 && similarity(simp(it), sw) >= 0.8) } }?.value
            if (kw != null) { out += Tok.Word(latin, kw, w); i++; continue }
            if (w.all { it.isDigit() }) { out += Tok.Num(w.repeat(repeat), w); repeat = 1; i++; continue }

            // numbers: tens (+ unit in the same or next word)
            val num = number(sw)
            if (num != null) {
                var value = num
                var orig = w
                if (num in 20..90 && num % 10 == 0 && i + 1 < words.size) {
                    val nextLatin = words[i + 1].let { if (isTamil(it)) translit(it) else it }
                    val unit = number(simp(nextLatin))
                    if (unit != null && unit in 1..9) { value += unit; orig += " " + words[i + 1]; i++ }
                }
                out += Tok.Num(value.toString().repeat(repeat), orig); repeat = 1; i++; continue
            }
            // a tens stem joined with a unit ("irubathimoonu", "இருபத்திமூணு", "இருபத்தொன்னு")
            val joined = joinedNumber(sw)
            if (joined != null) { out += Tok.Num(joined.toString().repeat(repeat), w); repeat = 1; i++; continue }
            // a colloquial number the recogniser split into two words ("pathi nonu", "iru bathu")
            if (i + 1 < words.size && sw.length >= 2 && !w.all { it.isDigit() }) {
                val next = words[i + 1]
                if (!next.all { it.isDigit() }) {
                    val two = simp(latin + (if (isTamil(next)) translit(next) else next))
                    val v = NUMBERS[two] ?: joinedNumber(two) ?: number(two)?.takeIf { two.length >= 5 }
                    if (v != null && simp(next).let { number(it) == null || it.length <= 2 }) {
                        var value = v
                        var used = 2
                        // "iru bathi moonu" → 23
                        if (v in 20..90 && v % 10 == 0 && i + 2 < words.size) {
                            val after = words[i + 2].let { if (isTamil(it)) translit(it) else it }
                            val unit = number(simp(after))
                            if (unit != null && unit in 1..9) { value += unit; used = 3 }
                        }
                        out += Tok.Num(value.toString().repeat(repeat), words.subList(i, i + used).joinToString(" "))
                        repeat = 1; i += used; continue
                    }
                }
            }

            // letters: Tamil letter names (possibly run together: "டிஎன்"), English letter names
            if (isTamil(w)) {
                val letters = tamilLetters(w)
                if (letters != null) {
                    for (l in letters) repeat(repeat) { out += Tok.Letter(l.first, l.second, w) }
                    repeat = 1; i++; continue
                }
            } else {
                val l = LETTER_NAMES[w]
                if (l != null) { repeat(repeat) { out += Tok.Letter(l, null, w) }; repeat = 1; i++; continue }
                if (w.length == 1 && w[0].isLetter()) { repeat(repeat) { out += Tok.Letter(w.uppercase(Locale.US), null, w) }; repeat = 1; i++; continue }
            }
            out += Tok.Word(latin, null, w)
            repeat = 1
            i++
        }
        return out
    }

    private fun isKeyword(w: String) = KEYWORDS.keys.any { k -> k.any { it == w } }

    private fun isTamil(w: String) = w.any { it in '஀'..'௿' }

    // ------------------------------------------------------------------ numbers

    /** A whole number word (English / Tamil, any spelling the recogniser may use). */
    private fun number(sw: String): Int? {
        if (sw.isEmpty()) return null
        NUMBERS[sw]?.let { return it }
        var best: Int? = null
        var bestSim = 0.0
        for ((k, v) in NUMBERS) {
            if (k.length < 3 || sw.length < 3) continue
            val sim = similarity(k, sw)
            val need = if (k.length >= 6) 0.8 else 0.85
            if (sim >= need && sim > bestSim) { bestSim = sim; best = v }
        }
        return best
    }

    private fun joinedNumber(sw: String): Int? {
        for ((stem, tens) in TENS_STEMS) {
            if (sw.length > stem.length + 1 && sw.startsWith(stem)) {
                var rest = sw.substring(stem.length)
                // "irupattonu": the stem's last consonant is often doubled into the unit
                val unit = number(rest) ?: number(rest.trimStart('i', 'u', 't'))
                if (unit != null && unit in 1..9) return tens + unit
            }
        }
        return null
    }

    /** Spelling-insensitive form: Tamil has no voiced/unvoiced difference and romanisation varies. */
    fun simp(s: String): String {
        var t = s.lowercase(Locale.US)
        t = t.replace("zh", "l").replace("th", "t").replace("dh", "t").replace("ch", "s").replace("sh", "s")
            .replace("ph", "f").replace("kh", "k").replace("gh", "k")
        val sb = StringBuilder()
        for (c in t) {
            val m = when (c) {
                'd' -> 't'; 'b' -> 'p'; 'g' -> 'k'; 'c', 'q' -> 'k'; 'j', 'z' -> 's'; 'w' -> 'v'; 'y' -> 'i'
                'h' -> ' '; 'e' -> 'e'; else -> c
            }
            if (m == ' ' || !(m.isLetterOrDigit())) continue
            if (sb.isNotEmpty() && sb.last() == m) continue // collapse doubles (aa → a, tt → t)
            sb.append(m)
        }
        // a leading "i"/"y" glide is often dropped or added ("yezhu" / "ezhu")
        return sb.toString().let { if (it.length > 3 && it.startsWith("i") && it[1] in "e") it.substring(1) else it }
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = maxOf(a.length, b.length)
        if (m == 0) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / m
    }

    // ------------------------------------------------------------------ Tamil script

    /** Rough romanisation of Tamil script (for numbers, keywords and model names). */
    fun translit(w: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val cons = CONSONANTS[c]
            if (cons != null) {
                val next = w.getOrNull(i + 1)
                when {
                    next == '்' -> { sb.append(cons); i += 2 }            // virama: no vowel
                    next != null && SIGNS.containsKey(next) -> { sb.append(cons).append(SIGNS[next]); i += 2 }
                    else -> { sb.append(cons).append('a'); i++ }
                }
                continue
            }
            VOWELS[c]?.let { sb.append(it) } ?: run { if (c.isLetterOrDigit() && c !in '஀'..'௿') sb.append(c) }
            if (c == 'ஃ') sb.append("f")                                 // ஃ (as in எஃப்)
            i++
        }
        return sb.toString()
    }

    /** "டிஎன்" → T(D) N; null if the word is not made of letter names. */
    private fun tamilLetters(w: String): List<Pair<String, String?>>? {
        val out = ArrayList<Pair<String, String?>>()
        var rest = w
        while (rest.isNotEmpty()) {
            val hit = TAMIL_LETTERS.keys.filter { rest.startsWith(it) }.maxByOrNull { it.length } ?: return null
            out += TAMIL_LETTERS.getValue(hit)
            rest = rest.substring(hit.length)
        }
        return out.ifEmpty { null }
    }

    private val CONSONANTS = mapOf(
        'க' to "k", 'ங' to "ng", 'ச' to "s", 'ஞ' to "nj", 'ட' to "t", 'ண' to "n", 'த' to "th", 'ந' to "n",
        'ப' to "p", 'ம' to "m", 'ய' to "y", 'ர' to "r", 'ல' to "l", 'வ' to "v", 'ழ' to "zh", 'ள' to "l",
        'ற' to "r", 'ன' to "n", 'ஜ' to "j", 'ஷ' to "sh", 'ஸ' to "s", 'ஹ' to "h"
    )
    private val VOWELS = mapOf(
        'அ' to "a", 'ஆ' to "aa", 'இ' to "i", 'ஈ' to "ee", 'உ' to "u", 'ஊ' to "oo", 'எ' to "e", 'ஏ' to "e",
        'ஐ' to "ai", 'ஒ' to "o", 'ஓ' to "o", 'ஔ' to "au"
    )
    private val SIGNS = mapOf(
        'ா' to "aa", 'ி' to "i", 'ீ' to "ee", 'ு' to "u", 'ூ' to "oo",
        'ெ' to "e", 'ே' to "e", 'ை' to "ai", 'ொ' to "o", 'ோ' to "o", 'ௌ' to "au"
    )

    /** Spoken English letter names in Tamil script. Ambiguous ones carry the alternative. */
    private val TAMIL_LETTERS: Map<String, Pair<String, String?>> = mapOf(
        "ஏ" to ("A" to null), "எ" to ("A" to null), "பி" to ("B" to "P"), "பீ" to ("P" to "B"), "சி" to ("C" to null),
        "சீ" to ("C" to null), "டி" to ("T" to "D"), "டீ" to ("T" to "D"), "ஈ" to ("E" to null), "இ" to ("E" to null),
        "எஃப்" to ("F" to null), "எப்" to ("F" to null), "ஜி" to ("G" to null), "ஜீ" to ("G" to null),
        "எச்" to ("H" to null), "ஹெச்" to ("H" to null), "ஐ" to ("I" to null), "ஜே" to ("J" to null),
        "கே" to ("K" to null), "எல்" to ("L" to null), "எம்" to ("M" to null), "என்" to ("N" to null),
        "ஓ" to ("O" to null), "க்யூ" to ("Q" to null), "கியூ" to ("Q" to null), "ஆர்" to ("R" to null),
        "எஸ்" to ("S" to null), "யு" to ("U" to null), "யூ" to ("U" to null), "வி" to ("V" to null),
        "வீ" to ("V" to null), "டபிள்யூ" to ("W" to null), "டபுள்யூ" to ("W" to null), "எக்ஸ்" to ("X" to null),
        "ஒய்" to ("Y" to null), "வை" to ("Y" to null), "இசட்" to ("Z" to null), "ஜெட்" to ("Z" to null),
        "ஸெட்" to ("Z" to null)
    )

    private val LETTER_NAMES = mapOf(
        "ay" to "A", "ae" to "A", "eh" to "A", "bee" to "B", "be" to "B", "see" to "C", "sea" to "C", "si" to "C",
        "dee" to "D", "di" to "D", "ee" to "E", "ef" to "F", "eff" to "F", "gee" to "G", "ji" to "G",
        "aitch" to "H", "ech" to "H", "edge" to "H", "eye" to "I", "jay" to "J", "je" to "J", "kay" to "K",
        "ke" to "K", "el" to "L", "yel" to "L", "em" to "M", "yem" to "M", "en" to "N", "yen" to "N",
        "pee" to "P", "pe" to "P", "queue" to "Q", "cue" to "Q", "kyu" to "Q", "are" to "R", "aar" to "R",
        "ar" to "R", "es" to "S", "ess" to "S", "tee" to "T", "ti" to "T", "you" to "U", "yu" to "U",
        "vee" to "V", "vi" to "V", "ex" to "X", "eks" to "X", "why" to "Y", "vai" to "Y", "zed" to "Z",
        "zee" to "Z", "jed" to "Z"
    )

    /** Multi-word phrases replaced before splitting. */
    private val PHRASES = listOf(
        " tamil nadu " to " t n ", " tamilnadu " to " t n ", " தமிழ்நாடு " to " t n ", " தமிழ் நாடு " to " t n ",
        " karnataka " to " k a ", " கர்நாடகா " to " k a ", " kerala " to " k l ", " கேரளா " to " k l ",
        " andhra " to " a p ", " andhra pradesh " to " a p ", " ஆந்திரா " to " a p ",
        " telangana " to " t s ", " தெலுங்கானா " to " t s ",
        " double u " to " w ", " double you " to " w ",
        " number " to " ", " நம்பர் " to " ", " vandi " to " ", " வண்டி " to " "
    )

    private val DOUBLE = listOf("double", "dabul", "dobul", "டபுள்", "டபிள்").map { simp(if (isTamil(it)) translit(it) else it) }.toSet()
    private val TRIPLE = listOf("triple", "tripple", "டிரிபிள்", "ட்ரிபிள்").map { simp(if (isTamil(it)) translit(it) else it) }.toSet()

    /** Keywords that start the token/name or model part. */
    private val KEYWORDS: Map<List<String>, String> = mapOf(
        listOf("token", "tokan", "tokken", "டோக்கன்", "டோக்கென்") to "token",
        listOf("name", "naam", "per", "peru", "peyar", "பேர்", "பேரு", "பெயர்") to "name",
        listOf("model", "modal", "maadal", "மாடல்", "மாடெல்") to "model"
    ).mapKeys { (k, _) -> k.map { if (isTamil(it)) translit(it) else it } }

    /** Number words → value. Keys are in [simp] form; built from English + colloquial and formal Tamil. */
    private val NUMBERS: Map<String, Int> = buildMap {
        fun add(v: Int, vararg ws: String) { for (w in ws) put(simp(if (isTamil(w)) translit(w) else w), v) }
        // English
        add(0, "zero", "oh", "nil"); add(1, "one"); add(2, "two"); add(3, "three", "tree")
        add(4, "four"); add(5, "five"); add(6, "six"); add(7, "seven"); add(8, "eight"); add(9, "nine")
        add(10, "ten"); add(11, "eleven"); add(12, "twelve"); add(13, "thirteen"); add(14, "fourteen")
        add(15, "fifteen"); add(16, "sixteen"); add(17, "seventeen"); add(18, "eighteen"); add(19, "nineteen")
        add(20, "twenty"); add(30, "thirty"); add(40, "forty", "fourty"); add(50, "fifty"); add(60, "sixty")
        add(70, "seventy"); add(80, "eighty"); add(90, "ninety")
        // Tamil – colloquial first, then formal; romanised and script
        add(0, "saipar", "sifar", "cipher", "zeero", "jeero", "சைபர்", "ஜீரோ", "சீரோ", "பூஜ்யம்", "பூஜ்ஜியம்")
        add(1, "onnu", "onu", "ondru", "onru", "ஒன்னு", "ஒண்ணு", "ஒன்று")
        add(2, "rendu", "rentu", "irandu", "ரெண்டு", "இரண்டு")
        add(3, "moonu", "munu", "moondru", "மூணு", "மூனு", "மூன்று")
        add(4, "naalu", "nalu", "naangu", "நாலு", "நான்கு")
        add(5, "anju", "anchu", "ainthu", "அஞ்சு", "ஐந்து")
        add(6, "aaru", "aru", "ஆறு")
        add(7, "ezhu", "yezhu", "elu", "ஏழு")
        add(8, "ettu", "yettu", "எட்டு")
        add(9, "ombodhu", "ombathu", "onbathu", "ombadhu", "ஒம்போது", "ஒம்பது", "ஒன்பது")
        add(10, "pathu", "padhu", "பத்து")
        add(11, "pathinonnu", "pathinonu", "padhinonnu", "pathinondru", "பதினொன்னு", "பதினொண்ணு", "பதினொன்று")
        add(12, "pannendu", "panniranu", "pannirendu", "pannirandu", "பன்னெண்டு", "பன்னிரெண்டு", "பன்னிரண்டு")
        add(13, "pathimoonu", "padhimoonu", "pathimoondru", "பதிமூணு", "பதிமூன்று")
        add(14, "pathinaalu", "padhinaalu", "pathinaangu", "பதினாலு", "பதினான்கு")
        add(15, "pathinanju", "padhinanju", "pathinainthu", "பதினஞ்சு", "பதினைந்து")
        add(16, "pathinaaru", "padhinaaru", "பதினாறு")
        add(17, "pathinezhu", "padhinezhu", "pathinelu", "பதினேழு")
        add(18, "pathinettu", "padhinettu", "பதினெட்டு")
        add(19, "pathombodhu", "pathombathu", "patthombadhu", "pathonbathu", "பத்தொம்போது", "பத்தொன்பது")
        add(20, "irubathu", "irupathu", "இருபது"); add(30, "muppathu", "muppadhu", "முப்பது")
        add(40, "naappathu", "narpathu", "naapadhu", "நாப்பது", "நாற்பது")
        add(50, "ambathu", "aimbathu", "ambadhu", "அம்பது", "ஐம்பது")
        add(60, "arubathu", "arupathu", "அறுபது"); add(70, "ezhubathu", "elupathu", "எழுபது")
        add(80, "embathu", "enpathu", "எம்பது", "எண்பது"); add(90, "thonnooru", "thonnuru", "தொண்ணூறு")
        // tens as said before a unit ("irubathi moonu")
        add(20, "irubathi", "irupathi", "irubath", "இருபத்தி", "இருபத்து"); add(30, "muppathi", "முப்பத்தி", "முப்பத்து")
        add(40, "naappathi", "நாப்பத்தி", "நாற்பத்து"); add(50, "ambathi", "அம்பத்தி", "ஐம்பத்து")
        add(60, "arubathi", "அறுபத்தி", "அறுபத்து"); add(70, "ezhubathi", "எழுபத்தி", "எழுபத்து")
        add(80, "embathi", "எம்பத்தி", "எண்பத்து"); add(90, "thonnoothi", "தொண்ணூத்தி", "தொண்ணூற்று")
    }

    private val TENS_STEMS: List<Pair<String, Int>> = listOf(
        "irupat" to 20, "irubat" to 20, "இருபத்" to 20, "muppat" to 30, "முப்பத்" to 30,
        "naappat" to 40, "narpat" to 40, "நாப்பத்" to 40, "நாற்பத்" to 40, "ambat" to 50, "aimbat" to 50,
        "அம்பத்" to 50, "ஐம்பத்" to 50, "arupat" to 60, "அறுபத்" to 60, "elupat" to 70, "ezhupat" to 70,
        "எழுபத்" to 70, "empat" to 80, "enpat" to 80, "எம்பத்" to 80, "எண்பத்" to 80,
        "tonnoot" to 90, "thonnoot" to 90, "தொண்ணூத்" to 90, "தொண்ணூற்" to 90
    ).map { (k, v) -> simp(if (isTamil(k)) translit(k) else k) to v }.sortedByDescending { it.first.length }
}
