package to.axolotl.cam.plates

enum class PlateFormat { GERMAN, GENERIC }

/**
 * A plate found in OCR text. [display] is the reading as seen (uppercase, separators unified) with `?` in every
 * position the OCR could not read with confidence or that only fits the format after an O↔0 / I↔1 / B↔8 swap;
 * [normalized] is the OCR's own uppercase alphanumerics and is never corrected. [glyphDropped]: an unreadable glyph
 * was taken as the seal and left out, so the reading carries no confidence either.
 */
data class PlateMatch(
    val display: String,
    val normalized: String,
    val format: PlateFormat,
    val glyphDropped: Boolean = false,
) {
    val uncertain: Boolean get() = '?' in display
}

/**
 * Plate candidate filter on OCR text. Pure Kotlin, no Android types. Input comes as two aligned strings: `shown`
 * has `?` for glyphs the OCR scored too low, `raw` keeps the OCR's guess for them (used for [PlateMatch.normalized]).
 */
object PlateText {
    private val swaps = mapOf('O' to '0', '0' to 'O', 'I' to '1', '1' to 'I', 'B' to '8', '8' to 'B')
    private const val DASHES = "-‐‑‒–—"

    // ponytail: more than two unreadable or swapped characters in one reading is treated as noise, not as a plate.
    private const val MAX_UNSURE = 2

    // ponytail: windows of up to 4 OCR elements per line; two-line (motorbike) plates are not joined.
    private const val MAX_WINDOW = 4

    fun normalize(text: String): String = text.uppercase().filter(::isPlateChar)

    /**
     * Possible seal gap from glyph geometry (a letter as wide as it is high has the seal merged into it). Tried
     * as a separator and as nothing; German beats generic, otherwise the reading without the gap wins.
     */
    const val SEAL_GAP = '\uE000'

    fun match(text: String): PlateMatch? = match(text, text)

    /**
     * Best plate reading, or null. Plates are uppercase, so a confidently read lowercase letter rejects the text.
     * Order: German (an unreadable non-letter glyph between city code and letters is first taken as the seal),
     * German after swaps (marked), generic EU (only without unreadable glyphs).
     */
    fun match(shown: String, raw: String): PlateMatch? {
        require(shown.length == raw.length) { "shown and raw must be aligned" }
        if (SEAL_GAP !in shown) return matchPlain(shown, raw)
        val gap = matchPlain(shown.replace(SEAL_GAP, ' '), raw.replace(SEAL_GAP, ' '))
        val joined = matchPlain(shown.replace(SEAL_GAP.toString(), ""), raw.replace(SEAL_GAP.toString(), ""))
        return if (gap?.format == PlateFormat.GERMAN && joined?.format != PlateFormat.GERMAN) gap else joined ?: gap
    }

    private fun matchPlain(shown: String, raw: String): PlateMatch? {
        if (shown.any { it != '?' && it.isLowerCase() }) return null
        val (s, r) = clean(shown, raw)
        if (s.isEmpty()) return null
        for (p in sealCandidates(s, r)) {
            val (s2, r2) = clean(s.replaceRange(p, p + 1, " "), r.replaceRange(p, p + 1, " "))
            german(s2, r2)?.let { return it.copy(glyphDropped = true) }
        }
        german(s, r)?.let { return it }
        if ('?' !in s && isGeneric(s)) return PlateMatch(s, normalize(r), PlateFormat.GENERIC)
        return null
    }

    /** A plate inside one OCR line: [first]..[last] are indices into the line's elements. */
    data class Found(val first: Int, val last: Int, val match: PlateMatch)

    /**
     * Plates among the elements of one OCR line. Every window of consecutive elements is tried; overlapping hits
     * are resolved by format (German before generic), the window covering more elements, then certainty.
     */
    fun find(shown: List<String>, raw: List<String> = shown): List<Found> {
        val hits = buildList {
            for (first in shown.indices) {
                for (last in first until minOf(shown.size, first + MAX_WINDOW)) {
                    val s = shown.subList(first, last + 1).joinToString(" ")
                    val r = raw.subList(first, last + 1).joinToString(" ")
                    match(s, r)?.let { add(Found(first, last, it)) }
                }
            }
        }.sortedWith(compareBy({ it.match.format }, { it.first - it.last }, { it.match.uncertain }, { it.first }))
        val taken = BooleanArray(shown.size)
        return hits.filter { hit ->
            val range = hit.first..hit.last
            range.none { taken[it] }.also { free -> if (free) range.forEach { taken[it] = true } }
        }.sortedBy { it.first }
    }

    private fun isPlateChar(c: Char) = c in 'A'..'Z' || c in '0'..'9' || c == 'Ä' || c == 'Ö' || c == 'Ü'

    /** Uppercase; every run of other characters becomes one separator: '-' if it held a dash, else ' '. */
    private fun clean(shown: String, raw: String): Pair<String, String> {
        val s = StringBuilder()
        val r = StringBuilder()
        var pending: Char? = null
        for (i in shown.indices) {
            val c = shown[i].uppercaseChar()
            if (c == '?' || isPlateChar(c)) {
                if (pending != null && s.isNotEmpty()) {
                    s.append(pending)
                    r.append(pending)
                }
                pending = null
                s.append(c)
                r.append(if (c == '?') raw[i].uppercaseChar() else c)
            } else if (pending != '-') {
                pending = if (c in DASHES) '-' else ' '
            }
        }
        return s.toString() to r.toString()
    }

    /**
     * Unreadable glyphs that may be the seal (registration/inspection stickers between city code and letters):
     * only letters (or unreadable glyphs) before it, a letter right after it, and an OCR guess that is not a letter
     * (seals come out as "8", "3", "&" …). An unreadable glyph guessed as a letter may be a real letter and stays.
     */
    private fun sealCandidates(s: String, r: String) = s.indices.filter { p ->
        s[p] == '?' && p > 0 && !r[p].isLetter() && s.substring(0, p).none { it.isDigit() } &&
            s.substring(p + 1).firstOrNull { it != ' ' && it != '-' }?.isLetter() == true
    }

    private fun german(s: String, r: String): PlateMatch? {
        val unknown = s.count { it == '?' }
        if (unknown > MAX_UNSURE) return null
        if (isGerman(s)) return PlateMatch(s, normalize(r), PlateFormat.GERMAN)
        val swapped = swapPositions(s, MAX_UNSURE - unknown) ?: return null
        return PlateMatch(s.mapIndexed { i, c -> if (i in swapped) '?' else c }.joinToString(""), normalize(r), PlateFormat.GERMAN)
    }

    /**
     * German layout: district code (official list, see [GERMAN_DISTRICT_CODES]), a visible boundary (separators
     * and at most one unreadable glyph between letters: the seal), 1–2 letters, optional separator, 1–4 digits with
     * at least one read digit, optional H/E; at most 8 characters before the suffix, a boundary glyph not counted.
     * A joined "BMK 4821" is not accepted as text ("BUS 42", "RAST 500" read the same way).
     */
    private fun isGerman(s: String): Boolean {
        val core = if (s.length > 1 && s.last() in "HE" && s[s.length - 2].let { it.isDigit() || it == '?' }) s.dropLast(1) else s
        val chars = core.count { it != ' ' && it != '-' }
        return (1..minOf(4, core.length)).any { d ->
            val digits = core.takeLast(d)
            digits.all { it.isDigit() || it == '?' } && digits.any { it.isDigit() } &&
                boundaryGlyphs(core.dropLast(d).removeSuffix(" ").removeSuffix("-")).any { chars - it <= 8 }
        }
    }

    /** For every valid district|letters split of [head]: how many unreadable glyphs its boundary holds (0 or 1). */
    private fun boundaryGlyphs(head: String): List<Int> = buildList {
        for (i in 1 until head.length) {
            for (j in i until head.length - 1) {
                val run = head.substring(i, j + 1)
                val glyphs = run.count { it == '?' }
                if (run.any { it != ' ' && it != '-' && it != '?' } || glyphs > 1) break
                if (glyphs == 1 && (!head[i - 1].isLetter() || !head[j + 1].isLetter())) continue // seal sits between letters
                val letters = head.substring(j + 1)
                if (letters.length in 1..2 && letters.all { it in 'A'..'Z' || it == '?' } && isDistrict(head.substring(0, i))) {
                    add(glyphs)
                }
            }
        }
    }

    private fun isDistrict(code: String): Boolean = when {
        code.length !in 1..3 || code.any { !it.isLetter() && it != '?' } -> false
        '?' !in code -> code in GERMAN_DISTRICT_CODES
        else -> GERMAN_DISTRICT_CODES.any { known -> known.length == code.length && known.indices.all { code[it] == '?' || code[it] == known[it] } }
    }

    /**
     * Positions whose swap makes [s] German (fewest swaps, at most [budget]), or null. A swap must fit its block:
     * "WA 12345" is not read as "WA I2345", but "B-MK 482I" may be "B-MK 4821".
     */
    private fun swapPositions(s: String, budget: Int): Set<Int>? {
        val confusable = s.indices.filter { s[it] in swaps && fitsBlock(s, it, swaps.getValue(s[it])) }
        for (k in 1..minOf(budget, confusable.size)) {
            for (combo in confusable.combinations(k)) {
                val chars = s.toCharArray()
                combo.forEach { chars[it] = swaps.getValue(chars[it]) }
                if (isGerman(String(chars))) return combo.toSet()
            }
        }
        return null
    }

    /** False if the rest of [i]'s block is all digits and [swapped] is a letter, or all letters and it is a digit. */
    private fun fitsBlock(s: String, i: Int, swapped: Char): Boolean {
        val start = s.lastIndexOfAny(charArrayOf(' ', '-'), i) + 1
        val end = s.indexOfAny(charArrayOf(' ', '-'), i).let { if (it < 0) s.length else it }
        val others = s.substring(start, i) + s.substring(i + 1, end)
        return when {
            others.isEmpty() -> true
            others.all { it.isDigit() } -> swapped.isDigit()
            others.all { it.isLetter() } -> swapped.isLetter()
            else -> true
        }
    }

    /**
     * Generic EU fallback: 2–3 blocks, letters and digits, 5–9 characters, two-block readings at least 7 (the
     * two-block EU formats PL, I, E, UK all have 7, which rejects "BUS 42", "IN 2023"). Letter-only blocks are
     * limited to 3 characters (no EU format has longer letter groups), which rejects words such as "TEMPO 30".
     */
    private fun isGeneric(s: String): Boolean {
        val blocks = s.split(' ', '-')
        val n = normalize(s)
        return blocks.size in 2..3 && n.length in (if (blocks.size == 2) 7 else 5)..9 &&
            n.any { it.isLetter() } && n.any { it.isDigit() } &&
            blocks.none { block -> block.length > 3 && block.all { it.isLetter() } }
    }

    private fun <T> List<T>.combinations(k: Int): Sequence<List<T>> =
        if (k == 0) sequenceOf(emptyList())
        else indices.asSequence().flatMap { i -> drop(i + 1).combinations(k - 1).map { listOf(this[i]) + it } }
}
