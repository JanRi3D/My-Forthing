package to.axolotl.cam.plates

enum class PlateFormat { GERMAN, GENERIC }

/**
 * A plate found in OCR text. [display] is the reading as seen (uppercase, separators unified) with `?` in every
 * position that only fits the plate format after an O↔0 / I↔1 / B↔8 swap; [normalized] is the reading's uppercase
 * alphanumerics and is never corrected.
 */
data class PlateMatch(val display: String, val normalized: String, val format: PlateFormat) {
    val uncertain: Boolean get() = '?' in display
}

/** Plate candidate filter on OCR text. Pure Kotlin, no Android types. */
object PlateText {
    // German format as specified; separators between the groups are optional.
    private val german = Regex("^[A-ZÄÖÜ]{1,3}[- ]?[A-Z]{1,2}[- ]?[0-9]{1,4}[HE]?$")
    private val swaps = mapOf('O' to '0', '0' to 'O', 'I' to '1', '1' to 'I', 'B' to '8', '8' to 'B')

    // ponytail: more than two confusable characters in one reading is treated as noise, not as a plate.
    private const val MAX_SWAPS = 2

    // ponytail: windows of up to 4 OCR elements per line; two-line (motorbike) plates are not joined.
    private const val MAX_WINDOW = 4

    fun normalize(text: String): String = text.uppercase().filter(::isPlateChar)

    /** Best plate reading of [raw], or null. Order: German as read, German after swaps (marked), generic EU. */
    fun match(raw: String): PlateMatch? {
        val clean = clean(raw)
        if (clean.isEmpty()) return null
        val normalized = normalize(clean)
        if (isGerman(clean)) return PlateMatch(clean, normalized, PlateFormat.GERMAN)
        uncertainPositions(clean)?.let { positions ->
            val display = clean.mapIndexed { i, c -> if (i in positions) '?' else c }.joinToString("")
            return PlateMatch(display, normalized, PlateFormat.GERMAN)
        }
        if (isGeneric(clean)) return PlateMatch(clean, normalized, PlateFormat.GENERIC)
        return null
    }

    /** A plate inside one OCR line: [first]..[last] are indices into the line's elements. */
    data class Found(val first: Int, val last: Int, val match: PlateMatch)

    /**
     * Plates among the elements of one OCR line. Every window of consecutive elements is tried; overlapping hits
     * are resolved by format (German before generic), certainty, then the window covering more elements.
     */
    fun find(elements: List<String>): List<Found> {
        val hits = buildList {
            for (first in elements.indices) {
                for (last in first until minOf(elements.size, first + MAX_WINDOW)) {
                    match(elements.subList(first, last + 1).joinToString(" "))?.let { add(Found(first, last, it)) }
                }
            }
        }.sortedWith(
            compareBy<Found>({ it.match.format }, { it.match.uncertain }, { it.first - it.last }, { it.first }),
        )
        val taken = BooleanArray(elements.size)
        return hits.filter { hit ->
            val range = hit.first..hit.last
            (range.none { taken[it] }).also { free -> if (free) range.forEach { taken[it] = true } }
        }.sortedBy { it.first }
    }

    private fun isPlateChar(c: Char) = c in 'A'..'Z' || c in '0'..'9' || c == 'Ä' || c == 'Ö' || c == 'Ü'

    /** Uppercase; every run of other characters becomes one separator: '-' if it held a dash, else ' '. */
    internal fun clean(raw: String): String = buildString {
        var pending: Char? = null
        for (c in raw.uppercase()) {
            if (isPlateChar(c)) {
                if (pending != null && isNotEmpty()) append(pending)
                pending = null
                append(c)
            } else if (pending != '-') {
                pending = if (c == '-' || c in "‐‑‒–—") '-' else ' '
            }
        }
    }

    private fun isGerman(clean: String): Boolean {
        if (!german.matches(clean)) return false
        val n = normalize(clean)
        val core = if (n.last() in "HE" && n[n.length - 2].isDigit()) n.dropLast(1) else n
        return core.length <= 8 // official limit: 8 characters plus an optional H/E suffix
    }

    /**
     * Positions whose swap makes [clean] German (fewest swaps), or null if no ≤ [MAX_SWAPS] swap does. A swap must
     * fit its block: "WA 12345" is not read as "WA I2345", but "B-MK 482I" may be "B-MK 4821".
     */
    private fun uncertainPositions(clean: String): Set<Int>? {
        val confusable = clean.indices.filter { clean[it] in swaps && fitsBlock(clean, it, swaps.getValue(clean[it])) }
        for (k in 1..minOf(MAX_SWAPS, confusable.size)) {
            for (combo in confusable.combinations(k)) {
                val chars = clean.toCharArray()
                combo.forEach { chars[it] = swaps.getValue(chars[it]) }
                if (isGerman(String(chars))) return combo.toSet()
            }
        }
        return null
    }

    /** False if the rest of [i]'s block is all digits and [swapped] is a letter, or all letters and it is a digit. */
    private fun fitsBlock(clean: String, i: Int, swapped: Char): Boolean {
        val start = clean.lastIndexOfAny(charArrayOf(' ', '-'), i) + 1
        val end = clean.indexOfAny(charArrayOf(' ', '-'), i).let { if (it < 0) clean.length else it }
        val others = clean.substring(start, i) + clean.substring(i + 1, end)
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
    private fun isGeneric(clean: String): Boolean {
        val blocks = clean.split(' ', '-')
        val n = normalize(clean)
        return blocks.size in 2..3 && n.length in 5..9 &&
            n.any { it.isLetter() } && n.any { it.isDigit() } &&
            blocks.none { block -> block.length > 3 && block.all { it.isLetter() } }
    }

    private fun <T> List<T>.combinations(k: Int): Sequence<List<T>> =
        if (k == 0) sequenceOf(emptyList())
        else indices.asSequence().flatMap { i -> drop(i + 1).combinations(k - 1).map { listOf(this[i]) + it } }
}
