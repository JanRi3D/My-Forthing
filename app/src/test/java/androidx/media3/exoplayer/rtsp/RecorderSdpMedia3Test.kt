package androidx.media3.exoplayer.rtsp

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import me.ri3d.dashcam.live.RECORDER_SDP
import me.ri3d.dashcam.live.rewriteSdp
import me.ri3d.dashcam.recorder.SimulatorRtspServer
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Media3 1.11's own SDP parsing and track building (package-private, hence this package) on the recorder's
 * descriptions: as sent they fail exactly as on hardware 2026-10-02 ("missing attribute control"); after [rewriteSdp]
 * they yield one H.264 track on the session URL whose initialization data are the bare (and for the SPS, cut) SPS and
 * PPS.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderSdpMedia3Test {
    /** The shape assumed before the raw capture (complete session part, audio, bare parameter sets). */
    private val assumed = listOf(
        "v=0", "o=- 1 1 IN IP4 192.168.42.1", "s=Live", "c=IN IP4 0.0.0.0", "t=0 0",
        "m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=fmtp:96 packetization-mode=1;profile-level-id=64001F;sprop-parameter-sets=Z2QAH6zZQPARabIAAAMACAAAAwGcHjBjLA==,aOvjyyLA",
        "m=audio 0 RTP/AVP 8", "a=rtpmap:8 PCMA/8000",
    ).joinToString("\r\n", postfix = "\r\n")
    private val session = Uri.parse("rtsp://127.0.0.1:40000/ch1/sub")
    private val headers = RtspHeaders.Builder().build()

    private fun tracks(text: String) = SessionDescriptionParser.parse(text).mediaDescriptionList
        .filter(RtpPayloadFormat::isFormatSupported)
        .map { RtspMediaTrack(headers, it, session) }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `the recorder's SDP fails in Media3 with missing attribute control`() {
        listOf(assumed, RECORDER_SDP).forEach { sdp ->
            val e = assertThrows(IllegalArgumentException::class.java) { tracks(sdp) }
            assertThat(e).hasMessageThat().isEqualTo("missing attribute control")
        }
    }

    @Test
    fun `the rewritten SDP gives one H264 track on the session URL`() {
        val tracks = tracks(rewriteSdp(assumed, "192.168.42.1").sdp)
        assertThat(tracks).hasSize(1)
        assertThat(tracks.single().uri).isEqualTo(session)
        assertThat(tracks.single().payloadFormat.format.sampleMimeType).isEqualTo("video/avc")
    }

    @Test
    fun `the recorder's exact SDP, rewritten, gives one H264 track with the bare SPS and PPS`() {
        val sdp = rewriteSdp(RECORDER_SDP, "192.168.42.1").sdp
        val description = SessionDescriptionParser.parse(sdp)
        val format = tracks(sdp).single().payloadFormat.format

        assertThat(description.origin).isEqualTo("- 0 0 IN IP4 192.168.42.1")
        assertThat(description.sessionName).isEqualTo("My Forthing")
        assertThat(description.timing).isEqualTo("0 0")
        assertThat(tracks(sdp).single().uri).isEqualTo(session)
        assertThat(format.sampleMimeType).isEqualTo("video/avc")
        assertThat(format.codecs).isEqualTo("avc1.4DE028") // profile-level-id kept as sent
        // Media3 prefixes one start code; the recorder's own start code is gone, the SPS ends before its restriction.
        assertThat(format.initializationData.map(::hex))
            .containsExactly("00000001674d001f8d8d406e1fd0800002ee0000afc80a", "0000000168ee3880").inOrder()
        assertThat(format.width to format.height).isEqualTo(880 to 496)
    }

    @Test
    fun `the recorder's SPS as sent, even without its start code, makes Media3 read past its end`() {
        // On the device this is thrown on Media3's playback thread, outside its error handling: the app would end.
        val uncut = "v=0\r\no=- 0 0 IN IP4 h\r\ns=x\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z00AH42NQG4f0IAAAu4AAK/IDw==,aO44gA==\r\na=control:*\r\n"
        assertThrows(ArrayIndexOutOfBoundsException::class.java) { tracks(uncut) }
    }

    @Test
    fun `the simulator's SDP (same shape, the clip's parameter sets), rewritten, gives the clip's size`() {
        val format = tracks(rewriteSdp(SimulatorRtspServer.SDP, "10.0.2.2").sdp).single().payloadFormat.format
        assertThat(format.width to format.height).isEqualTo(320 to 176)
        assertThat(hex(format.initializationData[0])).isEqualTo("00000001" + "6742c0298d681417a420c020c040")
    }
}
