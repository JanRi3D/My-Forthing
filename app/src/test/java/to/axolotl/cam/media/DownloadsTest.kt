package to.axolotl.cam.media

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import to.axolotl.cam.dashcam.managerFor
import to.axolotl.cam.recorder.RecorderSimulator
import java.io.File
import java.io.IOException

/** Downloads against MockWebServer: 206 resume, restart on 200, cancel cleanup, duplicate prevention. */
@RunWith(RobolectricTestRunner::class)
class DownloadsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val server = MockWebServer()
    private val body = ByteArray(5000) { (it % 251).toByte() }
    private lateinit var downloader: MediaDownloader

    @Before
    fun setUp() {
        server.start()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                DownloadWorker(appContext, workerParameters, downloader)
        }
        val config = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
        listOf("media", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    private class Setup(val repository: MediaRepository, val queue: DownloadQueue, val item: MediaItem) {
        val target: File get() = File(repository.mediaDir, "${item.id}/a.mp4")
        val part: File get() = MediaDownloader.partFile(target)
    }

    private suspend fun TestScope.setup(): Setup {
        val manager = managerFor(RecorderSimulator()).apply { setSimulator(true) } // simulator: the unbound client is allowed
        val repository = MediaRepository(context, db, manager)
        downloader = MediaDownloader(repository, RecorderHttp(manager, server.url("/").toString(), context))
        repository.upsertFromRecorderListing(0, listOf(recorderFile("/sim/a.mp4")))
        return Setup(repository, DownloadQueue(context, repository, downloader, manager), db.mediaDao().byRecorderPath("/sim/a.mp4")!!)
    }

    private fun Setup.writePart(bytes: ByteArray) = part.apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun rest(from: Int) = MockResponse.Builder().code(206)
        .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}").body(Buffer().write(body, from, body.size - from)).build()

    private fun full() = MockResponse.Builder().code(200).body(Buffer().write(body)).build()

    @Test
    fun `a partial download resumes with Range when the recorder answers 206`() = runTest {
        val s = setup()
        s.writePart(body.copyOf(1000))
        server.enqueue(rest(1000))

        val item = downloader.download(s.item.id)

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/sim/a.mp4")
        assertThat(request.headers["Range"]).isEqualTo("bytes=1000-")
        assertThat(s.target.readBytes()).isEqualTo(body)
        assertThat(s.part.exists()).isFalse()
        assertThat(item.localFile).isEqualTo(s.target)
        assertThat(item.localSizeBytes).isEqualTo(5000)
        assertThat(item.downloadedAt).isNotNull()
    }

    @Test
    fun `a 200 to a range request restarts the file from the start`() = runTest {
        val s = setup()
        s.writePart(ByteArray(1000) { 7 })
        server.enqueue(full())

        downloader.download(s.item.id)

        assertThat(server.takeRequest().headers["Range"]).isEqualTo("bytes=1000-")
        assertThat(s.target.readBytes()).isEqualTo(body)
    }

    @Test
    fun `a 206 that does not continue the part discards it`() = runTest {
        val s = setup()
        s.writePart(body.copyOf(1000))
        server.enqueue(rest(0))

        val e = assertThrows(DownloadException::class.java) { kotlinx.coroutines.runBlocking { downloader.download(s.item.id) } }

        assertThat(e.permanent).isFalse()
        assertThat(s.part.exists()).isFalse()
        server.enqueue(full())
        downloader.download(s.item.id)
        server.takeRequest()
        assertThat(server.takeRequest().headers["Range"]).isNull()
        assertThat(s.target.readBytes()).isEqualTo(body)
    }

    @Test
    fun `an interrupted body keeps the part for the next attempt`() = runTest {
        val s = setup()
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(body)).onResponseBody(SocketEffect.CloseSocket()).build())

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { downloader.download(s.item.id) } }
        val kept = s.part.length().toInt()
        assertThat(kept).isIn(1 until body.size)
        assertThat(s.target.exists()).isFalse()

        server.enqueue(rest(kept))
        downloader.download(s.item.id)
        server.takeRequest()
        assertThat(server.takeRequest().headers["Range"]).isEqualTo("bytes=$kept-")
        assertThat(s.target.readBytes()).isEqualTo(body)
    }

    @Test
    fun `a 404 is permanent`() = runTest {
        val s = setup()
        server.enqueue(MockResponse.Builder().code(404).build())
        val e = assertThrows(DownloadException::class.java) { kotlinx.coroutines.runBlocking { downloader.download(s.item.id) } }
        assertThat(e.permanent).isTrue()
    }

    private fun workState(id: String): WorkInfo.State? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(DownloadQueue.workName(id)).get().singleOrNull()?.state

    @Test
    fun `the queue downloads once and refuses a second copy`() = runTest {
        val s = setup()
        server.enqueue(full())

        assertThat(s.queue.enqueue(s.item.id)).isTrue()
        eventually { workState(s.item.id) == WorkInfo.State.SUCCEEDED }
        eventually { s.queue.progress.value[s.item.id]?.state == TransferState.DONE }

        assertThat(s.target.readBytes()).isEqualTo(body)
        assertThat(s.queue.enqueue(s.item.id)).isFalse() // already on the phone
        s.repository.upsertFromRecorderListing(0, listOf(recorderFile("/sim/a.mp4"))) // listed again
        assertThat(db.mediaDao().recorderType(0).map { it.id }).containsExactly(s.item.id)
        assertThat(downloader.download(s.item.id).localFile).isEqualTo(s.target) // no request
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(s.queue.enqueue("unknown")).isFalse()
    }

    @Test
    fun `cancel stops the work and drops the partial file`() = runTest {
        val s = setup()
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(body)).onResponseBody(SocketEffect.CloseSocket()).build())

        assertThat(s.queue.enqueue(s.item.id)).isTrue()
        eventually { workState(s.item.id) == WorkInfo.State.ENQUEUED && s.part.isFile } // attempt failed, waits for retry
        eventually { s.queue.progress.value[s.item.id]?.state == TransferState.WAITING }

        s.queue.cancel(s.item.id)

        eventually { workState(s.item.id) == WorkInfo.State.CANCELLED }
        assertThat(s.part.exists()).isFalse()
        assertThat(s.target.exists()).isFalse()
        assertThat(db.mediaDao().get(s.item.id)!!.localUri).isNull()
    }
}
