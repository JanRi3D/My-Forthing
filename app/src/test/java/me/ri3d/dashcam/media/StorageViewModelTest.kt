package me.ri3d.dashcam.media

import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.util.UUID

/** Storage counts the phone copies that are the last copy, so "Freigeben" can warn before deleting them. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageViewModelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        listOf("media", "screenshots", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    @Test
    fun `last copies are counted per kind, screenshots imported first`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = managerFor(RecorderSimulator())
        val repository = MediaRepository(context, db, manager)
        repository.upsertFromRecorderListing(0, listOf(recorderFile("/sim/a.mp4"), recorderFile("/sim/b.mp4")))
        listOf("/sim/a.mp4", "/sim/b.mp4").forEach { path ->
            val item = db.mediaDao().byRecorderPath(path)!!
            repository.markDownloaded(item.id, File(repository.mediaDir, "${item.id}/x.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) })
        }
        repository.markRecorderDeleted("/sim/b.mp4") // b exists only on the phone now
        val shot = UUID.randomUUID().toString()
        File(repository.screenshotDir.apply { mkdirs() }, "$shot.jpg").writeBytes(SimulatedFiles.jpeg(64, 36, "shot"))
        File(repository.screenshotDir, "$shot.json").writeText("""{"id":"$shot","capturedAt":"2026-10-01T12:00:00+02:00","source":"live","width":64,"height":36}""")
        val http = RecorderHttp(manager, "http://127.0.0.1:1", context)

        val vm = StorageViewModel(context, repository, DownloadQueue(context, repository, MediaDownloader(repository, http), manager), http)
        eventually { vm.usage.value != null }

        val usage = vm.usage.value!!
        assertThat(usage.lastCopies[StorageKind.DOWNLOADS]).isEqualTo(1)
        assertThat(usage.lastCopies[StorageKind.SCREENSHOTS]).isEqualTo(1)
        assertThat(usage.bytes[StorageKind.DOWNLOADS]).isEqualTo(20)
    }
}
