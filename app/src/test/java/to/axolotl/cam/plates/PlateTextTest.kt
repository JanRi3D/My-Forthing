package to.axolotl.cam.plates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlateTextTest {
    @Test
    fun `normalize keeps uppercase alphanumerics and umlauts only`() {
        assertThat(PlateText.normalize("b-mk 4821")).isEqualTo("BMK4821")
        assertThat(PlateText.normalize(" tü·AB:12 ")).isEqualTo("TÜAB12")
        assertThat(PlateText.normalize("%_?")).isEmpty()
    }

    @Test
    fun `german plates are certain as read`() {
        listOf("B-MK 4821", "B MK 4821", "HH-JK 553", "M AB 123H", "B-MK 4821E", "TÜ-AB 12", "BMK4821").forEach {
            val match = PlateText.match(it)
            assertThat(match?.format).isEqualTo(PlateFormat.GERMAN)
            assertThat(match?.uncertain).isFalse()
        }
        assertThat(PlateText.match("b mk 4821")).isEqualTo(PlateMatch("B MK 4821", "BMK4821", PlateFormat.GERMAN))
        assertThat(PlateText.match("B – MK  4821.")?.display).isEqualTo("B-MK 4821")
    }

    @Test
    fun `more than 8 characters before the suffix is not german`() {
        assertThat(PlateText.match("ABC-DE 1234")?.format).isEqualTo(PlateFormat.GENERIC)
        assertThat(PlateText.match("AB-CD 1234")?.format).isEqualTo(PlateFormat.GERMAN)
        assertThat(PlateText.match("AB-CD 1234E")?.format).isEqualTo(PlateFormat.GERMAN)
    }

    @Test
    fun `swapped characters are marked, never corrected`() {
        assertThat(PlateText.match("8-MK 4821")).isEqualTo(PlateMatch("?-MK 4821", "8MK4821", PlateFormat.GERMAN))
        assertThat(PlateText.match("B-MK 48O1")).isEqualTo(PlateMatch("B-MK 48?1", "BMK48O1", PlateFormat.GERMAN))
        assertThat(PlateText.match("B-MK 482I")).isEqualTo(PlateMatch("B-MK 482?", "BMK482I", PlateFormat.GERMAN))
        // The group structure decides: O in the digit group is ambiguous even though "BMKO" alone would be letters.
        assertThat(PlateText.match("B-MK O821")?.display).isEqualTo("B-MK ?821")
        assertThat(PlateText.match("8-MK 482I")?.display).isEqualTo("?-MK 482?")
        // Four swaps needed: not forced into the German format (only the loose generic fallback accepts it).
        assertThat(PlateText.match("8-M8 4O2I")?.format).isEqualTo(PlateFormat.GENERIC)
        assertThat(PlateText.match("88-88 8888")).isNull()
        // A swap must fit its block, so a Polish plate is not turned into an uncertain German one.
        assertThat(PlateText.match("WA 12345")).isEqualTo(PlateMatch("WA 12345", "WA12345", PlateFormat.GENERIC))
    }

    @Test
    fun `generic EU fallback`() {
        listOf("AB-123-CD", "WA 12345", "12-ABC-3", "W 12345 X").forEach {
            assertThat(PlateText.match(it)?.format).isEqualTo(PlateFormat.GENERIC)
        }
        assertThat(PlateText.match("AB-123-CD")?.uncertain).isFalse()
    }

    @Test
    fun `street text is rejected`() {
        listOf("STOP", "A 7", "B 27", "Ausfahrt 12", "Parken 2 Std", "Einbahnstraße", "50", "Tel 0800 123456", "")
            .forEach { assertThat(PlateText.match(it)).isNull() }
    }

    @Test
    fun `find picks the widest german window and skips the EU band letter`() {
        val found = PlateText.find(listOf("D", "B", "MK", "4821"))
        assertThat(found).hasSize(1)
        assertThat(found[0].first).isEqualTo(1)
        assertThat(found[0].last).isEqualTo(3)
        assertThat(found[0].match.display).isEqualTo("B MK 4821")
    }

    @Test
    fun `find returns several plates of one line in order`() {
        val found = PlateText.find(listOf("B-MK", "4821", "und", "HH-JK", "553"))
        assertThat(found.map { it.match.normalized }).containsExactly("BMK4821", "HHJK553").inOrder()
    }

    @Test
    fun `confidence is averaged only when every score is present`() {
        assertThat(meanConfidence(listOf(0.9f, 0.8f))).isWithin(1e-6f).of(0.85f)
        assertThat(meanConfidence(listOf(0.9f, 0f))).isNull()
        assertThat(meanConfidence(listOf(Float.NaN))).isNull()
        assertThat(meanConfidence(emptyList())).isNull()
    }
}
