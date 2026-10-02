package me.ri3d.dashcam.drive

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/** The Drive token goes to Google's hosts over HTTPS only; a 401 drops it. No network: a terminal interceptor answers. */
class DriveAuthInterceptorTest {
    private val auth = FakeDriveAuth("t1", "t2")
    private val seen = mutableListOf<Request>()
    private var code = 200
    private val client = OkHttpClient.Builder()
        .addInterceptor(DriveAuthInterceptor(auth))
        .addInterceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x").body("".toResponseBody()).build()
        }
        .build()

    private fun authorization(url: String): String? {
        client.newCall(Request.Builder().url(url).build()).execute().close()
        return seen.last().header("Authorization")
    }

    @Test
    fun `the token goes to Google's image and API hosts`() {
        assertThat(authorization("https://lh3.googleusercontent.com/drive-storage/abc=s220")).isEqualTo("Bearer t1")
        assertThat(authorization("https://www.googleapis.com/drive/v3/files/f1?alt=media")).isEqualTo("Bearer t1")
        assertThat(authorization("https://googleapis.com/x")).isEqualTo("Bearer t1")
    }

    @Test
    fun `no token for any other host or without TLS`() {
        listOf(
            "https://example.com/x.jpg",
            "https://evilgoogleapis.com/x",
            "https://googleapis.com.example.com/x",
            "https://lh3.googleusercontent.com.example.com/x",
            "http://www.googleapis.com/drive/v3/files/f1",
            "http://192.168.42.1/sd/DCIM/a.thm",
        ).forEach { assertThat(authorization(it)).isNull() }
        assertThat(auth.invalidated).isEmpty()
    }

    @Test
    fun `a 401 drops the token so the next image gets a fresh one`() {
        code = 401
        assertThat(authorization("https://lh3.googleusercontent.com/x")).isEqualTo("Bearer t1")
        assertThat(auth.invalidated).containsExactly("t1" to false)

        code = 200
        assertThat(authorization("https://lh3.googleusercontent.com/x")).isEqualTo("Bearer t2")
    }
}
