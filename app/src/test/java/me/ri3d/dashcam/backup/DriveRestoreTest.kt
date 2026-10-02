package me.ri3d.dashcam.backup

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.dashcam.drive.DRIVE_FILE_SCOPE
import me.ri3d.dashcam.drive.DriveAuthState
import me.ri3d.dashcam.drive.DriveError
import me.ri3d.dashcam.drive.format.DriveFormat
import me.ri3d.dashcam.drive.format.DriveFormatReader
import me.ri3d.dashcam.drive.format.DriveSidecar
import me.ri3d.dashcam.media.BackupState
import me.ri3d.dashcam.media.DownloadQueue
import me.ri3d.dashcam.media.DownloadWorker
import me.ri3d.dashcam.media.MediaCategory
import me.ri3d.dashcam.media.MediaItem
import me.ri3d.dashcam.media.MediaKind
import me.ri3d.dashcam.media.MediaRepository
import me.ri3d.dashcam.media.RecorderThumb
import me.ri3d.dashcam.media.eventually
import me.ri3d.dashcam.media.memoryDb
import me.ri3d.dashcam.media.recorderFile
import me.ri3d.dashcam.media.recorderItem
import me.ri3d.dashcam.plates.Plate
import me.ri3d.dashcam.plates.PlateSighting
import me.ri3d.dashcam.plates.SightingSource
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/** [DriveRestore] against an in-memory Drive: import, adopt, merge, idempotence, and the Drive-account hint. */
@RunWith(RobolectricTestRunner::class)
class DriveRestoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private lateinit var f: BackupFixture

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context) // the import asks the download queue
    }

    @After
    fun tearDown() {
        f.clear()
        db.close()
    }

    private fun TestScope.fixture() = BackupFixture(context, db, this, tmp.newFile("prefs.preferences_pb").apply { delete() }).also { f = it }

    private fun sidecar(
        name: String = "ch1_20261001_120000_0001G.mp4",
        time: String? = "2026-10-01 12:00:00",
        type: Int? = 1,
        kind: String = "ORIGINAL_VIDEO",
        category: String = "EVENT",
        parent: DriveSidecar.Parent? = null,
        id: String = UUID.randomUUID().toString(),
    ) = DriveSidecar(
        id = id, kind = kind, category = category, recorderType = type, originalFileName = name, recorderPath = "/sd/EVENT/$name",
        recorderTime = time, downloadedAt = "2026-10-01T18:02:11.482+02:00", sizeBytes = 0, md5 = "", mime = "video/mp4",
        durationMs = 30_000, parent = parent,
        plates = listOf(DriveSidecar.Plate("B-MK 4821", "BMK4821", 12_000, 0.9f, listOf(1, 2, 3, 4))),
        backup = DriveSidecar.Backup(complete = true, completedAt = "2026-10-01T18:05:40+02:00"),
    )

    private suspend fun all(): List<MediaItem> = f.repository.observe().first()

    @Test
    fun `a complete backup becomes a library row with the sidecar's fields`() = runTest {
        val f = fixture()
        val content = ByteArray(300) { (it * 7).toByte() }
        val clip = sidecar()
        val clipFile = f.api.backup(clip, content)
        val frame = sidecar(name = "enhanced_x.jpg", time = null, type = null, kind = "ENHANCED_FRAME", parent = DriveSidecar.Parent(clip.id, 37_000))
        f.api.backup(frame)

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(added = 2))

        val row = f.item(clip.id)!!
        assertThat(row).isEqualTo(
            MediaItem(
                id = clip.id, kind = MediaKind.ORIGINAL_VIDEO, category = MediaCategory.EVENT, recorderType = 1, recorderPath = null,
                recorderThumbPath = null, originalFileName = clip.originalFileName, recorderTime = "2026-10-01 12:00:00",
                recorderTimeEpochGuess = MediaRepository.epochGuess("2026-10-01 12:00:00"), localUri = null, localSizeBytes = null,
                localThumbPath = null, downloadedAt = OffsetDateTime.parse("2026-10-01T18:02:11.482+02:00").toInstant().toEpochMilli(),
                parentId = null, parentPositionMs = null, driveFileId = clipFile.id, backupState = BackupState.DONE, backupError = null,
                driveMd5 = md5(content), createdAt = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli(),
            ),
        )
        val derived = f.item(frame.id)!!
        assertThat(derived.kind).isEqualTo(MediaKind.ENHANCED_FRAME)
        assertThat(derived.parentId).isEqualTo(clip.id)
        assertThat(derived.parentPositionMs).isEqualTo(37_000)
        assertThat(derived.recorderType).isNull()
        assertThat(db.plateDao().history().first()).isEmpty() // plates are not restored
        assertThat(f.store.lastImport.value).isNotNull()
        assertThat(f.restore.thumbnail(row)).isEqualTo(
            RecorderThumb("drive-thumb:${clipFile.id}", "https://lh3.googleusercontent.com/t/${clip.id}", network = true),
        )
        assertThat(f.repository.observeDrive().first().map { it.id }).containsExactly(clip.id, frame.id)
    }

    @Test
    fun `a row with the same id takes over its Drive copy and keeps everything else`() = runTest {
        val f = fixture()
        val local = f.local("/sim/EVENT/e1.mp4")
        val media = f.api.backup(sidecar(name = "e1.mp4", id = local.id), content = local.localFile!!.readBytes())

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(adopted = 1))

        assertThat(f.item(local.id)).isEqualTo(
            local.copy(driveFileId = media.id, driveMd5 = md5(local.localFile!!.readBytes()), backupState = BackupState.DONE),
        )
    }

    @Test
    fun `a recorder-only row of the same file gives way to the Drive id and keeps its recorder copy`() = runTest {
        val f = fixture()
        f.repository.upsertFromRecorderListing(1, listOf(recorderFile("/sd/EVENT/e1.mp4")))
        val listed = db.mediaDao().byRecorderPath("/sd/EVENT/e1.mp4")!!
        val backup = sidecar(name = "e1.mp4")
        val media = f.api.backup(backup)

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(added = 1, merged = 1))

        assertThat(f.item(listed.id)).isNull()
        val merged = f.item(backup.id)!!
        assertThat(merged.recorderPath).isEqualTo("/sd/EVENT/e1.mp4")
        assertThat(merged.recorderThumbPath).isEqualTo(listed.recorderThumbPath)
        assertThat(merged.driveFileId).isEqualTo(media.id)
        assertThat(merged.backupState).isEqualTo(BackupState.DONE)
        // The next listing finds the Drive id under the path.
        f.repository.upsertFromRecorderListing(1, listOf(recorderFile("/sd/EVENT/e1.mp4")))
        assertThat(db.mediaDao().recorderType(1).map { it.id }).containsExactly(backup.id)
    }

    @Test
    fun `a phone copy merges only with identical content`() = runTest {
        val f = fixture()
        val same = f.local("/sim/EVENT/same.mp4", bytes = ByteArray(60) { 9 })
        val other = f.local("/sim/EVENT/other.mp4", bytes = ByteArray(50) { 7 })
        val sameBackup = sidecar(name = "same.mp4")
        f.api.backup(sameBackup, content = ByteArray(60) { 9 })
        val otherBackup = sidecar(name = "other.mp4")
        f.api.backup(otherBackup, content = ByteArray(50) { 8 })

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(added = 2, merged = 1))

        assertThat(f.item(same.id)).isNull()
        val merged = f.item(sameBackup.id)!!
        assertThat(merged.localUri).isEqualTo(same.localUri)
        assertThat(merged.localSizeBytes).isEqualTo(60L)
        assertThat(merged.localThumbPath).isEqualTo(same.localThumbPath)
        assertThat(merged.downloadedAt).isEqualTo(same.downloadedAt)
        assertThat(merged.recorderPath).isEqualTo("/sim/EVENT/same.mp4")
        assertThat(f.item(other.id)).isEqualTo(other) // different content: both stay
        assertThat(f.item(otherBackup.id)!!.localUri).isNull()
    }

    @Test
    fun `plate sightings and derived items move to the Drive id`() = runTest {
        val f = fixture()
        val withPlate = f.local("/sim/EVENT/p.mp4")
        val plate = db.plateDao().insert(Plate(normalized = "BMK4821", display = "B-MK 4821", firstSeen = 1, lastSeen = 1, count = 1))
        db.plateDao().insert(PlateSighting(0, plate, "B-MK 4821", withPlate.id, 1_000, SightingSource.CLIP, 1, null, null, 0f, 0f, 1f, 1f))
        val parent = f.local("/sim/EVENT/q.mp4")
        db.mediaDao().insert(recorderItem("child", 1, "/sim/child.mp4").copy(kind = MediaKind.ENHANCED_FRAME, parentId = parent.id))
        val content = withPlate.localFile!!.readBytes()
        val p = sidecar(name = "p.mp4")
        f.api.backup(p, content)
        val q = sidecar(name = "q.mp4")
        f.api.backup(q, content)

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(added = 2, merged = 2))

        assertThat(f.item(withPlate.id)).isNull()
        assertThat(db.plateDao().observeForMedia(p.id).first()).hasSize(1)
        assertThat(db.plateDao().observeForMedia(withPlate.id).first()).isEmpty()
        assertThat(f.item("child")!!.parentId).isEqualTo(q.id)
        assertThat(f.repository.currentId(parent.id)).isEqualTo(q.id) // a screen still holding the old id saves there
    }

    @Test
    fun `a row in use is excluded from the automatic backup and merged by the next import`() = runTest {
        val f = fixture()
        f.repository.upsertFromRecorderListing(1, listOf(recorderFile("/sd/EVENT/e1.mp4")))
        val downloading = db.mediaDao().byRecorderPath("/sd/EVENT/e1.mp4")!!
        val waiting = OneTimeWorkRequestBuilder<DownloadWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DownloadQueue.workName(downloading.id), ExistingWorkPolicy.KEEP, waiting).result.get()
        val uploading = f.local("/sim/EVENT/up.mp4").let { f.repository.update(it.id) { r -> r.copy(backupState = BackupState.UPLOADING) }!! }
        val checked = f.local("/sim/EVENT/scan.mp4")
        f.clipScans.enqueue(checked) // a plate check of it is queued
        val e1 = sidecar(name = "e1.mp4")
        f.api.backup(e1)
        f.api.backup(sidecar(name = "up.mp4"), uploading.localFile!!.readBytes())
        f.api.backup(sidecar(name = "scan.mp4"), checked.localFile!!.readBytes())

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(added = 3))
        assertThat(f.item(downloading.id)).isEqualTo(downloading)
        assertThat(f.item(uploading.id)).isEqualTo(uploading)
        assertThat(f.item(checked.id)).isEqualTo(checked)
        assertThat(listOf(downloading, uploading, checked).all { f.store.isExcluded(it.id) }).isTrue()

        // Once the download is gone, the next import (e.g. "Aktualisieren") merges it.
        WorkManager.getInstance(context).cancelUniqueWork(DownloadQueue.workName(downloading.id)).result.get()
        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(merged = 1))
        assertThat(f.item(downloading.id)).isNull()
        assertThat(f.item(e1.id)!!.recorderPath).isEqualTo("/sd/EVENT/e1.mp4")
        assertThat(f.store.isExcluded(downloading.id)).isFalse() // its bookkeeping went with it
    }

    @Test
    fun `a merge takes the current recorder fields but not a changed phone copy`() = runTest {
        val f = fixture()
        f.repository.upsertFromRecorderListing(1, listOf(recorderFile("/sd/EVENT/e1.mp4")))
        val seen = db.mediaDao().byRecorderPath("/sd/EVENT/e1.mp4")!!
        val backup = sidecar(name = "e1.mp4")
        f.api.backup(backup)
        f.repository.importDriveCopy(DriveRestore.driveRow(DriveFormatReader.pair(f.api.files).single(), f.api.json.values.single(), 0)!!)
        // Re-listed meanwhile with another thumbnail: still the same recording.
        db.mediaDao().update(seen.copy(recorderThumbPath = "/sd/EVENT/e1_new.thm"))

        assertThat(f.repository.mergeIntoDriveCopy(backup.id, seen.copy(localUri = "file:/elsewhere"))).isFalse() // decided on another copy
        assertThat(f.repository.mergeIntoDriveCopy(backup.id, seen)).isTrue()
        assertThat(f.item(backup.id)!!.recorderThumbPath).isEqualTo("/sd/EVENT/e1_new.thm")
    }

    @Test
    fun `incomplete and orphaned entries are ignored, of duplicates the oldest media file wins`() = runTest {
        val f = fixture()
        val content = ByteArray(100) { it.toByte() }
        val mediaOnly = sidecar()
        f.api.files += f.api.driveFile(
            "lonely", "${mediaOnly.id}.mp4", DriveFormat.mediaAppProperties(mediaOnly.id, "ORIGINAL_VIDEO", "EVENT", null), "month", md5(content),
        )
        val sidecarOnly = sidecar()
        f.api.files += f.api.driveFile("orphan", "${sidecarOnly.id}.json", DriveFormat.sidecarAppProperties(sidecarOnly.id), "month", null)
        f.api.json["orphan"] = sidecarOnly.copy(md5 = md5(content)).toJson()
        val twice = sidecar()
        f.api.backup(twice, content, createdTime = Instant.parse("2026-10-01T11:00:00Z"))
        val oldest = f.api.driveFile(
            "oldest", "${twice.id}.mp4", DriveFormat.mediaAppProperties(twice.id, "ORIGINAL_VIDEO", "EVENT", null), "month", md5(content),
            createdTime = Instant.parse("2026-10-01T09:00:00Z"),
        )
        f.api.files += oldest

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(added = 1))

        assertThat(all().map { it.id }).containsExactly(twice.id)
        assertThat(f.item(twice.id)!!.driveFileId).isEqualTo("oldest")
    }

    @Test
    fun `an unusable sidecar is skipped and counted, the others are imported`() = runTest {
        val f = fixture()
        f.api.backup(sidecar(), sidecarJson = "{ not json")
        f.api.backup(sidecar(kind = "HOLOGRAM"))
        val wrongId = sidecar()
        f.api.backup(wrongId, sidecarJson = wrongId.copy(id = UUID.randomUUID().toString(), md5 = md5(ByteArray(100) { it.toByte() })).toJson())
        val good = sidecar()
        f.api.backup(good)

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(added = 1, unreadable = 3))
        assertThat(all().map { it.id }).containsExactly(good.id)
    }

    @Test
    fun `a second run changes nothing and reads no sidecar`() = runTest {
        val f = fixture()
        f.repository.upsertFromRecorderListing(1, listOf(recorderFile("/sd/EVENT/m.mp4")))
        f.api.backup(sidecar(name = "m.mp4"))
        f.api.backup(sidecar())
        f.restore.importFromDrive().getOrThrow()
        val before = all()
        f.api.readHooks += { IllegalStateException("no sidecar read expected") }

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport())

        assertThat(all()).isEqualTo(before)
        assertThat(f.api.readHooks).hasSize(1)
    }

    @Test
    fun `nothing is imported while Drive is not connected or needs a reconnect`() = runTest {
        val f = fixture()
        f.api.backup(sidecar())
        f.auth.state.value = DriveAuthState.NotConnected
        assertThat(f.restore.importFromDrive().exceptionOrNull()).isInstanceOf(DriveError.NotConnected::class.java)
        f.auth.state.value = DriveAuthState.NeedsReconnect("x", "a@example.com")
        assertThat(f.restore.importFromDrive().exceptionOrNull()).isInstanceOf(DriveError.NeedsReconnect::class.java)

        assertThat(f.api.listCalls).isEqualTo(0)
        assertThat(all()).isEmpty()
        assertThat(f.store.lastImport.value).isNull()
    }

    @Test
    fun `an import that cannot read a sidecar fails and the next one finishes it`() = runTest {
        val f = fixture()
        val backup = sidecar()
        f.api.backup(backup)
        f.api.readHooks += { DriveError.Offline(IOException("gone")) }

        assertThat(f.restore.importFromDrive().exceptionOrNull()).isInstanceOf(DriveError.Offline::class.java)
        assertThat(f.store.lastImport.value).isNull()

        assertThat(f.restore.importFromDrive().getOrThrow()).isEqualTo(ImportReport(added = 1))
        assertThat(f.item(backup.id)).isNotNull()
    }

    @Test
    fun `the automatic import runs until it succeeded, at most once a minute, and again after an account switch`() = runTest {
        val f = fixture()
        var now = 0L
        f.restore.clock = { now }
        f.api.backup(sidecar())
        f.api.readHooks += { DriveError.Offline(IOException("offline")) }
        assertThat(f.restore.importOnce("a@example.com")).isFalse()
        val lists = f.api.listCalls
        now += 30_000
        assertThat(f.restore.importOnce("a@example.com")).isFalse() // too soon: not asked again
        assertThat(f.api.listCalls).isEqualTo(lists)
        now += DriveRestore.AUTOMATIC_RETRY_MS
        assertThat(f.restore.importOnce("a@example.com")).isTrue()
        assertThat(all()).hasSize(1)

        val later = sidecar()
        f.api.backup(later)
        assertThat(f.restore.importOnce("a@example.com")).isTrue()
        assertThat(f.item(later.id)).isNull() // imported already: "Aktualisieren" or "Drive-Status prüfen" bring it

        f.auth.state.value = DriveAuthState.Connected("b@example.com", setOf(DRIVE_FILE_SCOPE))
        assertThat(f.backup.adoptAccount("b@example.com")).isTrue() // resets every Drive field, and the import time
        assertThat(f.store.lastImport.value).isNull()
        assertThat(f.backup.whileAccount("a@example.com") { "written" }).isNull() // the old account's rows never land
        f.restore.importOnce("b@example.com")
        assertThat(f.item(later.id)).isNotNull()
    }

    @Test
    fun `an account switch forgets the previous account's thumbnail links and cached thumbnails`() = runTest {
        val f = fixture()
        val backup = sidecar()
        val media = f.api.backup(backup)
        f.restore.importFromDrive().getOrThrow()
        val row = f.item(backup.id)!!
        assertThat(f.restore.thumbnail(row)!!.network).isTrue()
        val cache = f.http.imageLoader.diskCache!!
        val key = DriveRestore.thumbKey(media.id)
        cache.openEditor(key)!!.also { editor -> cache.fileSystem.write(editor.data) { writeUtf8("jpeg") }; editor.commit() }
        assertThat(cache.openSnapshot(key)?.use { true }).isTrue()

        f.auth.state.value = DriveAuthState.Connected("b@example.com", setOf(DRIVE_FILE_SCOPE))
        assertThat(f.backup.adoptAccount("b@example.com")).isTrue()

        eventually { cache.openSnapshot(key)?.use { true } == null }
        assertThat(f.restore.thumbnail(row)!!.network).isFalse() // no link of the old account is used any more
        java.io.File(context.cacheDir, me.ri3d.dashcam.media.MediaModule.THUMB_CACHE_DIR).deleteRecursively()
    }

    @Test
    fun `the Drive account follows the connection, empty after a disconnect`() = runTest {
        val f = fixture()
        var hint: String? = null
        backgroundScope.launch { f.preferences.preferences.collect { hint = it.driveAccount } }
        backgroundScope.launch { f.restore.followDriveAccount() }

        eventually { hint == "a@example.com" }

        f.auth.state.value = DriveAuthState.NotConnected
        eventually { hint == "" && f.store.driveDisconnected }

        f.auth.state.value = DriveAuthState.Connected("b@example.com", setOf(DRIVE_FILE_SCOPE))
        eventually { hint == "b@example.com" && !f.store.driveDisconnected }
    }

    @Test
    fun `the hinted account is reconnected silently once per value, never after a disconnect here`() = runTest {
        val f = fixture()
        f.auth.state.value = DriveAuthState.NotConnected
        f.auth.onReconnect = { Result.failure(DriveError.Offline(IOException("offline"))) } // stays not connected
        backgroundScope.launch { f.restore.reconnectFromHint() }

        f.preferences.update { it.copy(driveAccount = "a@example.com") }
        eventually { f.auth.reconnects == listOf("a@example.com") }

        f.auth.state.value = DriveAuthState.NeedsReconnect("x", "a@example.com")
        f.auth.state.value = DriveAuthState.NotConnected
        f.preferences.update { it.copy(driveAccount = "") }
        f.preferences.update { it.copy(driveAccount = "b@example.com") }
        eventually { f.auth.reconnects == listOf("a@example.com", "b@example.com") }

        f.store.driveDisconnected = true
        f.preferences.update { it.copy(driveAccount = "c@example.com") }
        f.preferences.update { it.copy(driveAccount = "a@example.com") }
        repeat(20) { testScheduler.runCurrent() }
        assertThat(f.auth.reconnects).containsExactly("a@example.com", "b@example.com").inOrder()
    }
}
