package me.ri3d.cam.recorder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameCodecTest {
    private fun frame(seq: Int, text: String) = FrameCodec.encode(seq, text.toByteArray())

    @Test
    fun encode_matchesReportHeaderExample() {
        val body = RecorderCommand.StartSession.body(0)
        assertThat(body).isEqualTo("""{"token":0,"msgId":1,"param":{"clientType":1}}""")
        val bytes = FrameCodec.encode(1, body.toByteArray())
        assertThat(Redactor.hex(bytes.copyOf(12))).isEqualTo("46414142" + "00000001" + "0000002e")
        assertThat(bytes.size).isEqualTo(12 + 46)
    }

    @Test
    fun decoder_fragmentedFrame_byteByByte() {
        val bytes = frame(7, """{"msgId":3,"rval":0}""")
        val decoder = FrameCodec.Decoder()
        val out = bytes.indices.flatMap { decoder.feed(bytes, it, 1) }
        assertThat(out).containsExactly(Frame(7, """{"msgId":3,"rval":0}""".toByteArray()))
    }

    @Test
    fun decoder_combinedFramesInOneRead() {
        val decoder = FrameCodec.Decoder()
        val out = decoder.feed(frame(1, "a") + frame(2, "bb") + frame(3, ""))
        assertThat(out.map { it.seq }).containsExactly(1, 2, 3).inOrder()
        assertThat(String(out[1].body)).isEqualTo("bb")
        assertThat(out[2].body).isEmpty()
    }

    @Test
    fun decoder_splitAcrossHeaderAndBody() {
        val bytes = frame(9, "hello") + frame(10, "world")
        val decoder = FrameCodec.Decoder()
        val first = decoder.feed(bytes, 0, 6) // inside the first header
        val second = decoder.feed(bytes, 6, 15) // rest of frame 1 + start of frame 2's header
        val third = decoder.feed(bytes, 21, bytes.size - 21)
        assertThat(first).isEmpty()
        assertThat(second.map { it.seq }).containsExactly(9)
        assertThat(third.map { it.seq }).containsExactly(10)
    }

    @Test
    fun decoder_resyncsOnFaabAndReportsGarbage() {
        val garbage = mutableListOf<String>()
        val decoder = FrameCodec.Decoder { garbage += String(it) }
        val out = decoder.feed("xyz".toByteArray() + frame(1, "a") + "FAA-noise".toByteArray() + frame(2, "b"))
        assertThat(out.map { it.seq }).containsExactly(1, 2).inOrder()
        assertThat(garbage.joinToString("")).isEqualTo("xyzFAA-noise")
    }

    @Test
    fun decoder_markerSplitAcrossReads() {
        val decoder = FrameCodec.Decoder()
        val bytes = "junk".toByteArray() + frame(5, "x")
        assertThat(decoder.feed(bytes, 0, 6)).isEmpty() // "junkFA"
        assertThat(decoder.feed(bytes, 6, bytes.size - 6).map { it.seq }).containsExactly(5)
    }

    @Test
    fun decoder_absurdLengthIsTreatedAsGarbage() {
        val garbage = mutableListOf<ByteArray>()
        val decoder = FrameCodec.Decoder { garbage += it }
        val bogus = byteArrayOf(0x46, 0x41, 0x41, 0x42, 0, 0, 0, 1, 0x7f, 0x7f, 0x7f, 0x7f)
        val out = decoder.feed(bogus + frame(2, "ok"))
        assertThat(out).containsExactly(Frame(2, "ok".toByteArray()))
        assertThat(garbage.sumOf { it.size }).isEqualTo(bogus.size)
    }
}
