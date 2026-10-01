package to.axolotl.cam.plates

enum class PlateFormat { GERMAN, GENERIC }

/**
 * A plate found in OCR text. [display] is the reading as seen (uppercase, separators unified) with `?` in every
 * position the OCR could not read with confidence or that only fits the format after an O↔0 / I↔1 / B↔8 swap;
 * [normalized] is the OCR's own uppercase alphanumerics and is never corrected.
 */
data class PlateMatch(val display: String, val normalized: String, val format: PlateFormat) {
    val uncertain: Boolean get() = '?' in display
}

/**
 * Plate candidate filter on OCR text. Pure Kotlin, no Android types. Input comes as two aligned strings: `shown`
 * has `?` for glyphs the OCR scored too low, `raw` keeps the OCR's guess for them (used for [PlateMatch.normalized]).
 */
object PlateText {
    // German format as specified (separators optional), '?' standing for one unreadable character.
    private val german = Regex("^[A-ZÄÖÜ?]{1,3}[- ]?[A-Z?]{1,2}[- ]?[0-9?]{1,4}[HE]?$")
    private val swaps = mapOf('O' to '0', '0' to 'O', 'I' to '1', '1' to 'I', 'B' to '8', '8' to 'B')
    private const val DASHES = "-‐‑‒–—"

    // ponytail: more than two unreadable or swapped characters in one reading is treated as noise, not as a plate.
    private const val MAX_UNSURE = 2

    // ponytail: windows of up to 4 OCR elements per line; two-line (motorbike) plates are not joined.
    private const val MAX_WINDOW = 4

    fun normalize(text: String): String = text.uppercase().filter(::isPlateChar)

    fun match(text: String): PlateMatch? = match(text, text)

    /**
     * Best plate reading, or null. Plates are uppercase, so a confidently read lowercase letter rejects the text.
     * Order: German (an unreadable glyph between city code and letters is taken as the seal first), German after
     * swaps (marked), generic EU (only without unreadable glyphs).
     */
    fun match(shown: String, raw: String): PlateMatch? {
        require(shown.length == raw.length) { "shown and raw must be aligned" }
        if (shown.any { it != '?' && it.isLowerCase() }) return null
        val (s, r) = clean(shown, raw)
        if (s.isEmpty()) return null
        for (p in sealCandidates(s)) {
            val (s2, r2) = clean(s.replaceRange(p, p + 1, " "), r.replaceRange(p, p + 1, " "))
            german(s2, r2)?.let { return it }
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
     * Unreadable glyphs that may be the seal (registration/inspection stickers between city code and letters,
     * which OCR reads as "8", "S", "&" …): only letters (or unreadable glyphs) before it, a letter right after it.
     */
    private fun sealCandidates(s: String) = s.indices.filter { p ->
        s[p] == '?' && p > 0 && s.substring(0, p).none { it.isDigit() } &&
            s.substring(p + 1).firstOrNull { it != ' ' && it != '-' }?.isLetter() == true
    }

    private fun german(s: String, r: String): PlateMatch? {
        val unknown = s.count { it == '?' }
        if (unknown > MAX_UNSURE) return null
        if (isGerman(s)) return PlateMatch(s, normalize(r), PlateFormat.GERMAN)
        val swapped = swapPositions(s, MAX_UNSURE - unknown) ?: return null
        return PlateMatch(s.mapIndexed { i, c -> if (i in swapped) '?' else c }.joinToString(""), normalize(r), PlateFormat.GERMAN)
    }

    private fun isGerman(s: String): Boolean {
        if (!german.matches(s)) return false
        val chars = s.filter { it != ' ' && it != '-' }
        val core = if (chars.last() in "HE" && chars[chars.length - 2].let { it.isDigit() || it == '?' }) chars.dropLast(1) else chars
        return core.length <= 8 // official limit: 8 characters plus an optional H/E suffix
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
     * Generic EU fallback: 2–3 blocks, letters and digits, 5–9 characters. Letter-only blocks are limited to
     * 3 characters (no EU format has longer letter groups), which rejects words such as "TEMPO 30".
     */
    private fun isGeneric(s: String): Boolean {
        val blocks = s.split(' ', '-')
        val n = normalize(s)
        return blocks.size in 2..3 && n.length in 5..9 &&
            n.any { it.isLetter() } && n.any { it.isDigit() } &&
            blocks.none { block -> block.length > 3 && block.all { it.isLetter() } }
    }

    private fun <T> List<T>.combinations(k: Int): Sequence<List<T>> =
        if (k == 0) sequenceOf(emptyList())
        else indices.asSequence().flatMap { i -> drop(i + 1).combinations(k - 1).map { listOf(this[i]) + it } }
}
