package androidx.media3.exoplayer.rtsp

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import me.ri3d.dashcam.live.rewriteSdp
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Media3 1.11's own track building (package-private, hence this package) on the recorder's SDP shape: without
 * `a=control` it fails exactly as on hardware 2026-10-02; after [rewriteSdp] it yields one video track whose SETUP
 * URL is the session URL.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderSdpMedia3Test {
    private val sdp = listOf(
        "v=0", "o=- 1 1 IN IP4 192.168.42.1", "s=Live", "c=IN IP4 0.0.0.0", "t=0 0",
        "m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=fmtp:96 packetization-mode=1;profile-level-id=64001F;sprop-parameter-sets=Z2QAH6zZQPARabIAAAMACAAAAwGcHjBjLA==,aOvjyyLA",
        "m=audio 0 RTP/AVP 8", "a=rtpmap:8 PCMA/8000",
    ).joinToString("\r\n", postfix = "\r\n")
    private val session = Uri.parse("rtsp://127.0.0.1:40000/ch1/sub")
    private val headers = RtspHeaders.Builder().build()

    private fun tracks(text: String) = SessionDescriptionParser.parse(text).mediaDescriptionList
        .filter(RtpPayloadFormat::isFormatSupported)
        .map { RtspMediaTrack(headers, it, session) }

    @Test
    fun `the recorder's SDP fails in Media3 with missing attribute control`() {
        val e = assertThrows(IllegalArgumentException::class.java) { tracks(sdp) }
        assertThat(e).hasMessageThat().isEqualTo("missing attribute control")
    }

    @Test
    fun `the rewritten SDP gives one H264 track on the session URL`() {
        val tracks = tracks(rewriteSdp(sdp).sdp)
        assertThat(tracks).hasSize(1)
        assertThat(tracks.single().uri).isEqualTo(session)
        assertThat(tracks.single().payloadFormat.format.sampleMimeType).isEqualTo("video/avc")
    }
}
