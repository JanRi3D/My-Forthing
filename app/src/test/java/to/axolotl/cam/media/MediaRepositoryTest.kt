package to.axolotl.cam.media

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import to.axolotl.cam.dashcam.managerFor
import to.axolotl.cam.enhance.EnhanceEngine
import to.axolotl.cam.enhance.EnhancedKind
import to.axolotl.cam.enhance.EnhancementInfo
import to.axolotl.cam.recorder.RecorderResult
import to.axolotl.cam.recorder.RecorderSimulator
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val dao = db.mediaDao()
    private val sim = RecorderSimulator()

    @After
    fun tearDown() {
        db.close()
        listOf("media", "screenshots", "enhance", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    private fun TestScope.repository() = MediaRepository(context, db, managerFor(sim).apply { setSimulator(true) })

    private suspend fun MediaRepository.downloaded(path: String, type: Int = 0): MediaItem {
        upsertFromRecorderListing(type, listOf(recorderFile(path)))
        val item = dao.byRecorderPath(path)!!
        val file = File(mediaDir, "${item.id}/${path.substringAfterLast('/')}").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100)) }
        return markDownloaded(item.id, file)!!
    }

    @Test
    fun `listing upserts keep one row and id per recorder path`() = runTest {
        val repo = repository()
        repo.upsertFromRecorderListing(0, listOf(recorderFile("/sim/a.mp4"), recorderFile("/sim/b.JPG"), recorderFile("/sim/c.mp4", time = "kaputt")))
        val a = dao.byRecorderPath("/sim/a.mp4")!!
        repo.upsertFromRecorderListing(0, listOf(recorderFile("/sim/a.mp4", time = "2026-10-01 12:00:05")))
        repo.upsertFromRecorderListing(7, listOf(recorderFile("/sim/x.mp4")))

        val again = dao.byRecorderPath("/sim/a.mp4")!!
        assertThat(again.id).isEqualTo(a.id)
        assertThat(again.recorderTime).isEqualTo("2026-10-01 12:00:05")
        assertThat(a.originalFileName).isEqualTo("a.mp4")
        assertThat(a.category).isEqualTo(MediaCategory.NORMAL)
        assertThat(a.recorderTimeEpochGuess)
            .isEqualTo(LocalDateTime.of(2026, 10, 1, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        assertThat(dao.byRecorderPath("/sim/b.JPG")!!.kind).isEqualTo(MediaKind.ORIGINAL_PHOTO)
        assertThat(dao.byRecorderPath("/sim/c.mp4")!!.run { recorderTime to recorderTimeEpochGuess }).isEqualTo("kaputt" to null)
        assertThat(dao.byRecorderPath("/sim/x.mp4")!!.run { category to recorderType }).isEqualTo(MediaCategory.UNKNOWN to 7)
    }

    @Test
    fun `deleting one copy never deletes another`() = runTest {
        val repo = repository()
        val item = repo.downloaded("/sim/a.mp4")
        val file = item.localFile!!
        assertThat(file.isFile).isTrue()
        assertThat(item.localSizeBytes).isEqualTo(100)

        repo.deleteLocalCopy(item.id) // recorder copy remains
        val kept = dao.get(item.id)!!
        assertThat(kept.recorderPath).isEqualTo("/sim/a.mp4")
        assertThat(kept.localUri).isNull()
        assertThat(file.exists()).isFalse()

        val again = repo.markDownloaded(item.id, file.apply { parentFile!!.mkdirs(); writeBytes(ByteArray(5)) })!!
        repo.markRecorderDeleted("/sim/a.mp4") // phone copy remains
        assertThat(dao.get(item.id)!!.run { recorderPath to localUri }).isEqualTo(null to again.localUri)
        assertThat(file.isFile).isTrue()

        repo.deleteLocalCopy(item.id) // last copy: row gone
        assertThat(dao.get(item.id)).isNull()
    }

    @Test
    fun `a Drive copy keeps the row until the backup forgets it`() = runTest {
        val repo = repository()
        val item = repo.downloaded("/sim/a.mp4")
        repo.update(item.id) { it.copy(driveFileId = "d1", backupState = BackupState.DONE, driveMd5 = "m") }

        repo.deleteLocalCopy(item.id)
        repo.markRecorderDeleted("/sim/a.mp4")
        assertThat(dao.get(item.id)!!.driveFileId).isEqualTo("d1")

        repo.markDriveDeleted(item.id)
        assertThat(dao.get(item.id)).isNull()
    }

    @Test
    fun `deleteOnRecorder sends 4101 with device paths and keeps phone copies`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val repo = MediaRepository(context, db, manager)
        val local = repo.downloaded("/sim/a.mp4")
        repo.upsertFromRecorderListing(0, listOf(recorderFile("/sim/b.mp4")))
        val remoteOnly = dao.byRecorderPath("/sim/b.mp4")!!

        sim.rvalOverrides[4101] = 310
        val failed = repo.deleteOnRecorder(listOf("/sim/a.mp4", "/sim/b.mp4"))
        runCurrent()
        assertThat((failed as RecorderResult.Failed).error.code).isEqualTo(310)
        assertThat(dao.get(remoteOnly.id)).isNotNull()

        sim.rvalOverrides.clear()
        assertThat(repo.deleteOnRecorder(listOf("/sim/a.mp4", "/sim/b.mp4"))).isInstanceOf(RecorderResult.Ok::class.java)
        assertThat(sim.received.last { it.msgId == 4101 }.json)
            .isEqualTo("""{"msgId":4101,"token":123,"param":{"fileList":["/sim/a.mp4","/sim/b.mp4"]}}""")
        assertThat(dao.get(local.id)!!.run { recorderPath to (localFile?.isFile) }).isEqualTo(null to true)
        assertThat(dao.get(remoteOnly.id)).isNull()
    }

    @Test
    fun `a complete listing forgets recorder copies it no longer lists`() = runTest {
        val repo = repository()
        val kept = repo.downloaded("/sim/b.mp4")
        repo.upsertFromRecorderListing(0, listOf(recorderFile("/sim/a.mp4"), recorderFile("/sim/c.mp4")))
        val c = dao.byRecorderPath("/sim/c.mp4")!!

        repo.reconcileRecorderListing(0, setOf("/sim/a.mp4"))

        assertThat(dao.byRecorderPath("/sim/a.mp4")).isNotNull()
        assertThat(dao.get(kept.id)!!.run { recorderPath to localUri }).isEqualTo(null to kept.localUri)
        assertThat(dao.get(c.id)).isNull()
    }

    @Test
    fun `screenshots are imported once, only with their JSON, and deleted with it`() = runTest {
        val repo = repository()
        val dir = repo.screenshotDir.apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        jpeg(File(dir, "$id.jpg"))
        jpeg(File(dir, "${UUID.randomUUID()}.jpg")) // still being written: no JSON yet
        File(dir, "$id.json").writeText("""{"id":"$id","capturedAt":"2026-10-01T12:00:00+02:00","source":"live","width":64,"height":36}""")

        assertThat(repo.importScreenshots()).isEqualTo(1)
        assertThat(repo.importScreenshots()).isEqualTo(0)
        val item = dao.get(id)!!
        assertThat(item.kind).isEqualTo(MediaKind.SCREENSHOT)
        assertThat(item.createdAt).isEqualTo(1_790_848_800_000L) // 2026-10-01T10:00:00Z
        assertThat(item.localFile).isEqualTo(File(dir, "$id.jpg"))
        assertThat(item.localThumbPath?.let(::File)?.isFile).isTrue()
        assertThat(item.recorderPath).isNull()

        repo.deleteLocalCopy(id)
        assertThat(dao.get(id)).isNull()
        assertThat(File(dir, "$id.jpg").exists() || File(dir, "$id.json").exists()).isFalse()
        assertThat(repo.importScreenshots()).isEqualTo(0) // not resurrected
    }

    @Test
    fun `derived outputs are linked to their original and never change it`() = runTest {
        val repo = repository()
        val parent = repo.downloaded("/sim/e.mp4", type = 1)
        val id = UUID.randomUUID().toString()
        val output = File(context.filesDir, "enhance/$id.jpg").apply { parentFile!!.mkdirs() }
        jpeg(output)
        val info = EnhancementInfo(EnhancedKind.ENHANCED_FRAME, EnhanceEngine.CLASSICAL, null, 4f, parent.id, 37_000, 1_000)
        EnhancementInfo.sidecarOf(output).writeText(info.toJson())

        val derived = repo.registerDerived(MediaKind.ENHANCED_FRAME, output, parent.id, 37_000, info)

        assertThat(derived.id).isEqualTo(id)
        assertThat(derived.run { parentId to parentPositionMs }).isEqualTo(parent.id to 37_000L)
        assertThat(derived.category).isEqualTo(MediaCategory.EVENT)
        assertThat(derived.recorderPath).isNull()
        assertThat(derived.createdAt).isEqualTo(1_000)
        assertThat(repo.registerDerived(MediaKind.ENHANCED_FRAME, output, parent.id, 37_000, info)).isEqualTo(derived) // idempotent
        assertThat(dao.get(parent.id)).isEqualTo(parent)

        repo.deleteLocalCopy(derived.id)
        assertThat(dao.get(derived.id)).isNull()
        assertThat(output.exists() || EnhancementInfo.sidecarOf(output).exists()).isFalse()
        assertThat(dao.get(parent.id)).isEqualTo(parent)
        assertThat(parent.localFile!!.isFile).isTrue()

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.registerDerived(MediaKind.ORIGINAL_VIDEO, output, parent.id, null, null) }
        }
    }

    private fun jpeg(file: File) {
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
}
