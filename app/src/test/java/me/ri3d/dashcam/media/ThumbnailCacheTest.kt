package me.ri3d.dashcam.media

import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import me.ri3d.dashcam.dashcam.managerFor
import me.ri3d.dashcam.recorder.RecorderSimulator
import me.ri3d.dashcam.recorder.SimulatedFiles
import java.io.File

/** Recorder thumbnails on disk: stable keys, LRU size cap, stored whatever the headers say, served offline. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailCacheTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val jpeg = SimulatedFiles.jpeg(320, 180, "thm")

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        server.close()
        File(context.cacheDir, MediaModule.THUMB_CACHE_DIR).deleteRecursively()
        File(context.cacheDir, "lru").deleteRecursively()
    }

    @Test
    fun `the key is the recording path and its recorder time, not the URL`() = runTest {
        val key = thumbKey("/sd/DCIM/ch1_20261002_091128_0782.mp4", "2026-10-02 09:11:28")
        assertThat(key).isEqualTo(thumbKey("/sd/DCIM/ch1_20261002_091128_0782.mp4", "2026-10-02 09:11:28"))
        assertThat(key).isNotEqualTo(thumbKey("/sd/DCIM/ch1_20261002_091128_0782.mp4", "2026-11-05 10:00:00")) // reused path
        assertThat(thumbKey("/a.mp4", null)).isNotEqualTo(thumbKey("/b.mp4", null))

        val manager = managerFor(RecorderSimulator())
        val http = RecorderHttp(manager, "http://10.0.2.2:8080", context)
        val item = recorderItem("x", 0, "/sd/DCIM/x.mp4", "2026-10-02 09:11:28")
        val recorder = http.thumb(item, network = true)!!
        manager.setSimulator(true)
        val simulator = http.thumb(item, network = false)!!
        assertThat(recorder.url).isEqualTo("http://192.168.42.1/sd/DCIM/x.thm")
        assertThat(simulator.url).isEqualTo("http://10.0.2.2:8080/sd/DCIM/x.thm")
        assertThat(simulator.key).isEqualTo(recorder.key)
        assertThat(http.thumb(item.copy(localThumbPath = "/t.jpg", recorderThumbPath = null), true)).isNull()
    }

    @Test
    fun `the disk cache drops the least recently used thumbnails beyond its size`() {
        val cache = thumbnailDiskCache(File(context.cacheDir, "lru"), maxBytes = 10_000)
        fun put(key: String) = cache.openEditor(key)!!.run {
            cache.fileSystem.write(metadata) { }
            cache.fileSystem.write(data) { write(ByteArray(4_000)) }
            commit()
        }
        put("a")
        put("b")
        cache.openSnapshot("a")!!.close() // a is used again: b is now the oldest
        put("c") // 12,000 bytes > 10,000

        val end = System.currentTimeMillis() + 5_000
        while (cache.size > 10_000 && System.currentTimeMillis() < end) Thread.sleep(10) // cleanup runs in the background
        assertThat(cache.size).isAtMost(10_000L)
        assertThat(cache.openSnapshot("b")).isNull()
        cache.openSnapshot("a")!!.close()
        cache.openSnapshot("c")!!.close()
    }

    @Test
    fun `a thumbnail is stored despite no-store headers and shown later without a session`() = runTest {
        val manager = managerFor(RecorderSimulator()).apply { setSimulator(true) }
        manager.connect()
        val http = RecorderHttp(manager, server.url("/").toString(), context)
        server.enqueue(
            MockResponse.Builder().code(200).body(Buffer().write(jpeg))
                .addHeader("Content-Type", "application/binary").addHeader("Cache-Control", "no-store, max-age=0").build(),
        )
        val thumb = RecorderThumb(thumbKey("/sd/DCIM/a.mp4", "2026-10-02 09:11:28"), http.url("/sd/DCIM/a.thm"), network = true)

        val first = http.imageLoader.execute(thumb.request(context))
        assertThat(first).isInstanceOf(SuccessResult::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        http.imageLoader.diskCache!!.openSnapshot(thumb.key)!!.close()

        manager.disconnect()
        http.imageLoader.memoryCache!!.clear()
        // No session, no network, another URL (recorder instead of simulator): the key finds it on disk.
        val offline = thumb.copy(url = "http://192.168.42.1/sd/DCIM/a.thm", network = false)
        val second = http.imageLoader.execute(offline.request(context)) as SuccessResult
        assertThat(second.dataSource).isEqualTo(DataSource.DISK)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an error answer is not stored`() = runTest {
        val manager = managerFor(RecorderSimulator()).apply { setSimulator(true) }
        manager.connect()
        val http = RecorderHttp(manager, server.url("/").toString(), context)
        server.enqueue(MockResponse.Builder().code(404).body("not found").build())
        val thumb = RecorderThumb(thumbKey("/sd/DCIM/b.mp4", null), http.url("/sd/DCIM/b.thm"), network = true)

        assertThat(http.imageLoader.execute(thumb.request(context))).isInstanceOf(ErrorResult::class.java)
        assertThat(http.imageLoader.diskCache!!.openSnapshot(thumb.key)).isNull()
    }
}
