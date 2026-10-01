package me.ri3d.cam.drive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import me.ri3d.cam.drive.format.DriveFormat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DriveRestApiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val auth = FakeDriveAuth("t1", "t2", "t3")
    private lateinit var api: DriveRestApi

    @Before
    fun setUp() {
        server.start()
        api = DriveRestApi(OkHttpClient(), auth, server.url("/"), chunkSize = CHUNK)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `resumable upload resumes from the persisted session after an interruption`() = runTest {
        val bytes = Random(1).nextBytes(600 * 1024)
        val file = tmp.newFile("clip.mp4").apply { writeBytes(bytes) }
        val total = bytes.size
        val session = server.url("/upload/session/abc").toString()
        server.enqueue(MockResponse.Builder().code(200).setHeader("Location", session).build())
        server.enqueue(resumeIncomplete(lastByte = CHUNK - 1))
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) // chunk sent, connection drops

        var persisted: String? = null
        val first = api.uploadResumable(file, "id.mp4", "video/mp4", "month", mapOf("mf.id" to "id"), null, { persisted = it }) { _, _ -> }

        assertThat(first.exceptionOrNull()).isInstanceOf(DriveError.Offline::class.java)
        assertThat(persisted).isEqualTo(session)
        val start = server.takeRequest()
        assertThat(start.url.queryParameter("uploadType")).isEqualTo("resumable")
        assertThat(start.headers["X-Upload-Content-Length"]).isEqualTo("$total")
        assertThat(start.body!!.utf8()).contains("\"mf.id\":\"id\"")
        assertThat(server.takeRequest().headers["Content-Range"]).isEqualTo("bytes 0-${CHUNK - 1}/$total")
        drain()

        // Restart with the persisted session: ask how far Drive got, then continue from there.
        server.enqueue(resumeIncomplete(lastByte = CHUNK - 1))
        server.enqueue(resumeIncomplete(lastByte = 2L * CHUNK - 1))
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"f1","size":"$total","md5Checksum":"abc123"}""").build())
        val progress = mutableListOf<Long>()
        val second = api.uploadResumable(file, "id.mp4", "video/mp4", "month", emptyMap(), persisted) { sent, _ -> progress += sent }

        assertThat(second.getOrThrow()).isEqualTo(DriveFile(id = "f1", size = total.toLong(), md5Checksum = "abc123"))
        val status = server.takeRequest()
        assertThat(status.headers["Content-Range"]).isEqualTo("bytes */$total")
        assertThat(status.bodySize).isEqualTo(0L)
        val chunk2 = server.takeRequest()
        assertThat(chunk2.url.toString()).isEqualTo(session)
        assertThat(chunk2.headers["Content-Range"]).isEqualTo("bytes $CHUNK-${2 * CHUNK - 1}/$total")
        assertThat(chunk2.body!!.toByteArray()).isEqualTo(bytes.copyOfRange(CHUNK.toInt(), 2 * CHUNK.toInt()))
        val chunk3 = server.takeRequest()
        assertThat(chunk3.headers["Content-Range"]).isEqualTo("bytes ${2 * CHUNK}-${total - 1}/$total")
        assertThat(chunk3.body!!.toByteArray()).isEqualTo(bytes.copyOfRange(2 * CHUNK.toInt(), total))
        assertThat(progress.first()).isEqualTo(CHUNK)
        assertThat(progress.last()).isEqualTo(total.toLong())
        assertThat(progress).isInOrder()
    }

    @Test
    fun `an expired upload session starts a new one`() = runTest {
        val file = tmp.newFile("a.jpg").apply { writeBytes(ByteArray(10)) }
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(200).setHeader("Location", server.url("/upload/new").toString()).build())
        server.enqueue(MockResponse.Builder().code(201).body("""{"id":"f2","md5Checksum":"m"}""").build())
        var persisted: String? = null

        val result = api.uploadResumable(file, "a.jpg", "image/jpeg", "p", emptyMap(), server.url("/upload/old").toString(), { persisted = it }) { _, _ -> }

        assertThat(result.getOrThrow().id).isEqualTo("f2")
        assertThat(persisted).isEqualTo(server.url("/upload/new").toString())
    }

    @Test
    fun `401 refreshes the token once and retries`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"storageQuota":{"limit":"16106127360","usage":"42"}}""").build())

        assertThat(api.about().getOrThrow()).isEqualTo(DriveQuota(limit = 16_106_127_360, usage = 42))
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer t1")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer t2")
        assertThat(auth.invalidated).containsExactly("t1" to false)
    }

    @Test
    fun `a second 401 asks the user to reconnect`() = runTest {
        server.dispatcher = answer { MockResponse.Builder().code(401).build() }

        assertThat(api.about().exceptionOrNull()).isInstanceOf(DriveError.NeedsReconnect::class.java)
        assertThat(auth.invalidated).containsExactly("t1" to false, "t2" to true).inOrder()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `the token refresh allowance is per call`() = runTest {
        repeat(2) {
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(MockResponse.Builder().code(200).body("""{"storageQuota":{"usage":"1"}}""").build())
        }

        assertThat(api.about().getOrThrow()).isEqualTo(DriveQuota(limit = null, usage = 1))
        assertThat(api.about().getOrThrow()).isEqualTo(DriveQuota(limit = null, usage = 1))
        assertThat(auth.invalidated).containsExactly("t1" to false, "t2" to false).inOrder()
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `failing chunks give up after maxAttempts`() = runTest {
        val file = tmp.newFile("big.mp4").apply { writeBytes(ByteArray(600 * 1024)) }
        server.dispatcher = answer { request ->
            when {
                request.method == "POST" -> MockResponse.Builder().code(200).setHeader("Location", server.url("/upload/s").toString()).build()
                request.headers["Content-Range"]!!.startsWith("bytes */") -> resumeIncomplete(lastByte = CHUNK - 1)
                else -> MockResponse.Builder().code(503).build()
            }
        }

        val result = api.uploadResumable(file, "big.mp4", "video/mp4", "p", emptyMap(), null) { _, _ -> }

        assertThat((result.exceptionOrNull() as DriveError.Http).code).isEqualTo(503)
        assertThat(server.requestCount).isAtMost(1 + 2 * MAX_ATTEMPTS)
    }

    @Test
    fun `a 308 confirming part of a chunk continues from there`() = runTest {
        val bytes = Random(2).nextBytes(600 * 1024)
        val file = tmp.newFile("a.mp4").apply { writeBytes(bytes) }
        server.enqueue(MockResponse.Builder().code(200).setHeader("Location", server.url("/upload/s").toString()).build())
        server.enqueue(resumeIncomplete(lastByte = 99_999))
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"f","md5Checksum":"m"}""").build())

        api.uploadResumable(file, "a.mp4", "video/mp4", "p", emptyMap(), null) { _, _ -> }.getOrThrow()

        server.takeRequest()
        assertThat(server.takeRequest().headers["Content-Range"]).isEqualTo("bytes 0-${CHUNK - 1}/${bytes.size}")
        val next = server.takeRequest()
        assertThat(next.headers["Content-Range"]).isEqualTo("bytes 100000-${100_000 + CHUNK - 1}/${bytes.size}")
        assertThat(next.body!!.toByteArray()).isEqualTo(bytes.copyOfRange(100_000, 100_000 + CHUNK.toInt()))
    }

    @Test
    fun `a 308 without Range means nothing was stored`() = runTest {
        val file = tmp.newFile("a.jpg").apply { writeBytes(ByteArray(10)) }
        server.enqueue(MockResponse.Builder().code(308).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"f","md5Checksum":"m"}""").build())

        api.uploadResumable(file, "a.jpg", "image/jpeg", "p", emptyMap(), server.url("/upload/s").toString()) { _, _ -> }.getOrThrow()

        assertThat(server.takeRequest().headers["Content-Range"]).isEqualTo("bytes */10")
        assertThat(server.takeRequest().headers["Content-Range"]).isEqualTo("bytes 0-9/10")
    }

    @Test
    fun `a 5xx on a chunk asks the session before continuing`() = runTest {
        val file = tmp.newFile("a.mp4").apply { writeBytes(ByteArray(300 * 1024)) }
        val total = 300 * 1024
        server.enqueue(MockResponse.Builder().code(200).setHeader("Location", server.url("/upload/s").toString()).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(resumeIncomplete(lastByte = CHUNK - 1)) // Drive kept the chunk despite the 503
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"f","md5Checksum":"m"}""").build())

        api.uploadResumable(file, "a.mp4", "video/mp4", "p", emptyMap(), null) { _, _ -> }.getOrThrow()

        server.takeRequest()
        assertThat(server.takeRequest().headers["Content-Range"]).isEqualTo("bytes 0-${CHUNK - 1}/$total")
        assertThat(server.takeRequest().headers["Content-Range"]).isEqualTo("bytes */$total")
        assertThat(server.takeRequest().headers["Content-Range"]).isEqualTo("bytes $CHUNK-${total - 1}/$total")
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `a 401 on a chunk refreshes the token and re-sends the chunk`() = runTest {
        val file = tmp.newFile("a.jpg").apply { writeBytes("0123456789".toByteArray()) }
        server.enqueue(MockResponse.Builder().code(200).setHeader("Location", server.url("/upload/s").toString()).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"f","md5Checksum":"m"}""").build())

        assertThat(api.uploadResumable(file, "a.jpg", "image/jpeg", "p", emptyMap(), null) { _, _ -> }.getOrThrow().id).isEqualTo("f")

        server.takeRequest()
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer t1")
        val resent = server.takeRequest()
        assertThat(resent.headers["Authorization"]).isEqualTo("Bearer t2")
        assertThat(resent.headers["Content-Range"]).isEqualTo("bytes 0-9/10")
        assertThat(resent.body!!.utf8()).isEqualTo("0123456789")
        assertThat(auth.invalidated).containsExactly("t1" to false)
    }

    @Test
    fun `403 storageQuotaExceeded is InsufficientStorage`() = runTest {
        val file = tmp.newFile("a.mp4").apply { writeBytes(ByteArray(10)) }
        server.enqueue(
            MockResponse.Builder().code(403)
                .body("""{"error":{"code":403,"errors":[{"domain":"usageLimits","reason":"storageQuotaExceeded"}]}}""").build(),
        )

        val result = api.uploadResumable(file, "a.mp4", "video/mp4", "p", emptyMap(), null) { _, _ -> }

        assertThat(result.exceptionOrNull()).isInstanceOf(DriveError.InsufficientStorage::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `list follows pageToken with restricted fields and retries a 503`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"nextPageToken":"p2","files":[{"id":"a","appProperties":{"mf.id":"1"}}]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"files":[{"id":"b","unknownField":true}]}""").build())

        val files = api.list(DriveFormat.DISCOVERY_QUERY).getOrThrow()

        assertThat(files.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(files[0].appProperties).containsExactly("mf.id", "1")
        server.takeRequest() // the 503
        val page1 = server.takeRequest()
        assertThat(page1.url.queryParameter("q")).isEqualTo(DriveFormat.DISCOVERY_QUERY)
        assertThat(page1.url.queryParameter("fields")).startsWith("nextPageToken,files(id,")
        assertThat(page1.url.queryParameter("pageToken")).isNull()
        assertThat(server.takeRequest().url.queryParameter("pageToken")).isEqualTo("p2")
    }

    @Test
    fun `ensureRootFolder creates the root folder and the manifest`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"files":[]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"root1"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"files":[]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"manifest1"}""").build())

        assertThat(api.ensureRootFolder().getOrThrow()).isEqualTo("root1")
        assertThat(server.takeRequest().url.queryParameter("q")).isEqualTo(DriveFormat.ROOT_FOLDER_QUERY)
        val folder = server.takeRequest().body!!.utf8()
        assertThat(folder).contains("\"mimeType\":\"application/vnd.google-apps.folder\"")
        assertThat(folder).contains("\"mf.role\":\"root\"")
        assertThat(folder).contains("\"mf.format\":\"1\"")
        assertThat(folder).doesNotContain("parents")
        assertThat(server.takeRequest().url.queryParameter("q")).contains("name = 'myforthing.json' and 'root1' in parents")
        val manifest = server.takeRequest()
        assertThat(manifest.method).isEqualTo("POST")
        assertThat(manifest.url.queryParameter("uploadType")).isEqualTo("multipart")
        assertThat(manifest.headers["Content-Type"]).startsWith("multipart/related")
        assertThat(manifest.body!!.utf8()).contains("\"format\":1,\"app\":\"me.ri3d.cam\"")
        assertThat(manifest.body!!.utf8()).contains("\"parents\":[\"root1\"]")
    }

    @Test
    fun `writeJson updates an existing file in place`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"files":[{"id":"s1"}]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"s1"}""").build())

        assertThat(api.writeJson("x.json", "month", "{}", mapOf("mf.role" to "sidecar")).getOrThrow().id).isEqualTo("s1")
        server.takeRequest()
        val update = server.takeRequest()
        assertThat(update.method).isEqualTo("PATCH")
        assertThat(update.url.encodedPath).isEqualTo("/upload/drive/v3/files/s1")
        assertThat(update.body!!.utf8()).doesNotContain("parents")
    }

    @Test
    fun `network failure is Offline`() = runTest {
        server.close()
        assertThat(api.about().exceptionOrNull()).isInstanceOf(DriveError.Offline::class.java)
    }

    private fun answer(response: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = response(request)
    }

    private fun resumeIncomplete(lastByte: Long) =
        MockResponse.Builder().code(308).setHeader("Range", "bytes=0-$lastByte").build()

    private fun drain(): List<RecordedRequest> = generateSequence { server.takeRequest(200, TimeUnit.MILLISECONDS) }.toList()

    private companion object {
        const val CHUNK = 256L * 1024
        const val MAX_ATTEMPTS = 5 // DriveRestApi default
    }
}

/** Hands out [tokens] in order; each [invalidate] moves to the next one. */
class FakeDriveAuth(vararg tokens: String) : DriveAuth {
    private val queue = ArrayDeque(tokens.toList())
    private var current = queue.removeFirst()
    val invalidated = mutableListOf<Pair<String, Boolean>>()
    override val state = MutableStateFlow<DriveAuthState>(DriveAuthState.Connected("a@example.com", setOf(DRIVE_FILE_SCOPE)))

    override suspend fun connect(activity: android.app.Activity, chooseAccount: Boolean) = Result.success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun accessToken() = Result.success(current)

    override suspend fun invalidate(token: String, revoked: Boolean) {
        invalidated += token to revoked
        queue.removeFirstOrNull()?.let { current = it }
    }
}
