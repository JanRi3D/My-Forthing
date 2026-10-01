package me.ri3d.cam.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.cam.drive.DRIVE_FILE_SCOPE
import me.ri3d.cam.drive.DriveAuthState
import me.ri3d.cam.drive.DriveError
import me.ri3d.cam.drive.format.DriveFormat
import me.ri3d.cam.drive.format.DriveSidecar
import me.ri3d.cam.media.BackupState
import me.ri3d.cam.media.memoryDb
import me.ri3d.cam.plates.Plate
import me.ri3d.cam.plates.PlateSighting
import me.ri3d.cam.plates.SightingSource
import java.io.IOException

/** [DriveBackup] against an in-memory Drive: verification, resume, pauses, duplicates, account switch, delete, reconcile. */
@RunWith(RobolectricTestRunner::class)
class DriveBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private lateinit var f: BackupFixture

    private fun TestScope.fixture() = BackupFixture(context, db, this, tmp.newFile("prefs.preferences_pb").apply { delete() }).also { f = it }

    @After
    fun tearDown() {
        f.clear()
        db.close()
    }

    @Test
    fun `an upload is verified before the sidecar is written and the item is DONE`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        val md5 = DriveFormat.md5Hex(item.localFile!!)
        val progress = mutableListOf<Long>()

        assertThat(f.backup.upload(item.id) { sent, _ -> progress += sent }).isEqualTo(BackupOutcome.Done)

        val media = f.api.of(item.id, DriveFormat.ROLE_MEDIA).single()
        assertThat(media.name).isEqualTo("${item.id}.mp4")
        assertThat(media.appProperties).containsExactly(
            "mf.format", "1", "mf.role", "media", "mf.id", item.id, "mf.kind", "ORIGINAL_VIDEO", "mf.category", "EVENT",
        )
        val done = f.item(item.id)!!
        assertThat(done.backupState).isEqualTo(BackupState.DONE)
        assertThat(done.driveFileId).isEqualTo(media.id)
        assertThat(done.driveMd5).isEqualTo(md5)
        assertThat(progress.last()).isEqualTo(1000L)
        val sidecar = f.api.sidecar(item.id)
        assertThat(sidecar.copy(backup = sidecar.backup.copy(completedAt = null), downloadedAt = null)).isEqualTo(
            DriveSidecar(
                id = item.id, kind = "ORIGINAL_VIDEO", category = "EVENT", recorderType = 1, originalFileName = "e1.mp4",
                recorderPath = "/sim/EVENT/e1.mp4", recorderTime = "2026-10-01 12:00:00", sizeBytes = 1000, md5 = md5,
                mime = "video/mp4", durationMs = null, parent = null, plates = null, backup = DriveSidecar.Backup(complete = true),
            ),
        )
        assertThat(sidecar.downloadedAt).isNotNull()
        assertThat(f.store.session(item.id)).isNull()
    }

    @Test
    fun `an interrupted upload resumes from the persisted session URI`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.uploadFailures += { DriveError.Offline(IOException("gone")) }

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry)
        assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.QUEUED)
        val session = f.store.session(item.id)
        assertThat(session).startsWith("https://upload.example/session-")

        // After a restart (new DriveBackup, same store) the upload continues in the same session.
        val restarted = DriveBackup(f.api, f.auth, f.repository, f.preferences, me.ri3d.cam.plates.PlateExport(db.plateDao(), f.preferences), BackupStore(context))
        assertThat(restarted.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.sessions).containsExactly(null, session).inOrder()
        assertThat(f.store.session(item.id)).isNull()
    }

    @Test
    fun `an md5 mismatch deletes the unverified upload, writes no sidecar and retries until FAILED`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.corrupt = true

        repeat(DriveBackup.MAX_ATTEMPTS - 1) {
            assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry)
            assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.QUEUED)
        }
        assertThat(f.api.files).isEmpty() // every unverified upload deleted, no sidecar
        assertThat(f.api.deleted).hasSize(DriveBackup.MAX_ATTEMPTS - 1)

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Failed(BackupErrors.MD5_MISMATCH))
        assertThat(f.item(item.id)!!.run { backupState to backupError }).isEqualTo(BackupState.FAILED to BackupErrors.MD5_MISMATCH)
        assertThat(f.item(item.id)!!.driveFileId).isNull()

        // A retry after the failure succeeds once Drive receives the right bytes.
        f.api.corrupt = false
        f.repository.update(item.id) { it.copy(backupState = BackupState.QUEUED) }
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
    }

    @Test
    fun `a full Drive pauses the queue without failing the item`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.uploadFailures += { DriveError.InsufficientStorage() }

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Paused(PauseReason.STORAGE_FULL))

        assertThat(f.store.storageFull.value).isTrue()
        assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.QUEUED)
    }

    @Test
    fun `revoked access pauses until Drive is reconnected`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.uploadFailures += {
            f.auth.state.value = DriveAuthState.NeedsReconnect("widerrufen", "a@example.com") // as GoogleDriveAuth does on the second 401
            DriveError.NeedsReconnect()
        }

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Paused(PauseReason.RECONNECT))
        assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.QUEUED)

        // While it needs a reconnect nothing is uploaded.
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Paused(PauseReason.RECONNECT))
        assertThat(f.api.sessions).hasSize(1)

        f.auth.state.value = DriveAuthState.Connected("a@example.com", setOf(DRIVE_FILE_SCOPE))
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
    }

    @Test
    fun `a Drive file of the item with the same md5 is adopted instead of uploaded again`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        val existing = f.api.driveFile(
            "earlier", "${item.id}.mp4", DriveFormat.mediaAppProperties(item.id, "ORIGINAL_VIDEO", "EVENT", null), "month-old",
            DriveFormat.md5Hex(item.localFile!!),
        )
        f.api.files += existing

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)

        assertThat(f.api.sessions).isEmpty() // nothing uploaded
        assertThat(f.item(item.id)!!.driveFileId).isEqualTo("earlier")
        assertThat(f.api.of(item.id, DriveFormat.ROLE_SIDECAR).single().parents).containsExactly("month-old") // next to its media
    }

    @Test
    fun `other content under the item's id is replaced when unverified and a conflict when verified`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        val props = DriveFormat.mediaAppProperties(item.id, "ORIGINAL_VIDEO", "EVENT", null)
        f.api.files += f.api.driveFile("stale", "${item.id}.mp4", props, "m", "f".repeat(32))

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.deleted).containsExactly("stale") // no sidecar: it never was a verified backup

        val other = f.queued("/sim/EVENT/e2.mp4")
        f.api.files += f.api.driveFile("verified", "${other.id}.mp4", DriveFormat.mediaAppProperties(other.id, "ORIGINAL_VIDEO", "EVENT", null), "m", "f".repeat(32))
        f.api.files += f.api.driveFile("side", "${other.id}.json", DriveFormat.sidecarAppProperties(other.id), "m", null)

        assertThat(f.backup.upload(other.id)).isEqualTo(BackupOutcome.Failed(BackupErrors.DRIVE_CONFLICT))
        assertThat(f.api.deleted).containsExactly("stale") // the verified version is never touched
    }

    @Test
    fun `an account switch resets every Drive state and drops the session URIs`() = runTest {
        val f = fixture()
        val done = f.queued("/sim/EVENT/e1.mp4")
        assertThat(f.backup.upload(done.id)).isEqualTo(BackupOutcome.Done)
        val waiting = f.queued("/sim/EVENT/e2.mp4")
        f.api.uploadFailures += { DriveError.Offline(IOException("gone")) }
        assertThat(f.backup.upload(waiting.id)).isEqualTo(BackupOutcome.Retry)
        assertThat(f.store.session(waiting.id)).isNotNull()
        val uploads = f.api.sessions.size

        // The worker meets the new account first (before the queue's observer resets anything).
        f.auth.state.value = DriveAuthState.Connected("B@example.com", setOf(DRIVE_FILE_SCOPE))
        assertThat(f.backup.upload(waiting.id)).isEqualTo(BackupOutcome.Skipped)

        assertThat(f.api.sessions).hasSize(uploads) // the old session was never used with the new account
        assertThat(f.store.session(waiting.id)).isNull()
        listOf(done.id, waiting.id).forEach { id ->
            val reset = f.item(id)!!
            assertThat(listOf(reset.backupState, reset.driveFileId, reset.driveMd5)).containsExactly(BackupState.NONE, null, null).inOrder()
            assertThat(reset.localFile!!.isFile).isTrue()
        }
        assertThat(f.store.account).isEqualTo("b@example.com")
        assertThat(f.backup.adoptAccount("b@example.com")).isFalse()
    }

    @Test
    fun `plate metadata goes into the sidecar only with the opt-in`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        val plate = db.plateDao().insert(Plate(normalized = "BMK4821", display = "B-MK 4821", firstSeen = 1, lastSeen = 1, count = 1))
        db.plateDao().insert(PlateSighting(0, plate, "B-MK 482?", item.id, 12_000, SightingSource.CLIP, 1, null, null, 812f, 604f, 1044.4f, 668f))

        f.backup.upload(item.id)
        assertThat(f.api.sidecar(item.id).plates).isNull()

        f.preferences.update { it.copy(backupIncludePlateMetadata = true) }
        f.repository.update(item.id) { it.copy(backupState = BackupState.QUEUED) }
        f.backup.upload(item.id)
        assertThat(f.api.sidecar(item.id).plates).containsExactly(
            DriveSidecar.Plate("B-MK 482?", "BMK4821", 12_000, null, listOf(812, 604, 1044, 668)),
        )
    }

    @Test
    fun `deleting the Drive copy removes media and sidecar and keeps phone and recorder copies`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.backup.upload(item.id)
        val media = f.api.of(item.id, DriveFormat.ROLE_MEDIA).single().id
        val sidecar = f.api.of(item.id, DriveFormat.ROLE_SIDECAR).single().id

        assertThat(f.backup.deleteOnDrive(item.id).isSuccess).isTrue()

        assertThat(f.api.deleted).containsExactly(media, sidecar).inOrder()
        val kept = f.item(item.id)!!
        assertThat(listOf(kept.backupState, kept.driveFileId, kept.driveMd5)).containsExactly(BackupState.NONE, null, null).inOrder()
        assertThat(kept.localFile!!.isFile).isTrue()
        assertThat(kept.recorderPath).isEqualTo("/sim/EVENT/e1.mp4")
        assertThat(f.store.isExcluded(item.id)).isTrue()
    }

    @Test
    fun `deleting fails cleanly without a connection and changes nothing`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.backup.upload(item.id)
        f.auth.state.value = DriveAuthState.NotConnected

        assertThat(f.backup.deleteOnDrive(item.id).exceptionOrNull()).isInstanceOf(DriveError.NotConnected::class.java)
        assertThat(f.api.deleted).isEmpty()
        assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.DONE)
    }

    @Test
    fun `reconciliation forgets backups that vanished from Drive and deletes nothing`() = runTest {
        val f = fixture()
        val kept = f.queued("/sim/EVENT/e1.mp4")
        val gone = f.queued("/sim/EVENT/e2.mp4")
        val incomplete = f.queued("/sim/EVENT/e3.mp4")
        listOf(kept, gone, incomplete).forEach { f.backup.upload(it.id) }
        f.api.files.removeAll(f.api.of(gone.id, DriveFormat.ROLE_MEDIA)) // the user deleted it in Drive
        f.api.files.removeAll(f.api.of(incomplete.id, DriveFormat.ROLE_SIDECAR))

        assertThat(f.backup.reconcile().getOrThrow()).isEqualTo(2)

        assertThat(f.item(kept.id)!!.backupState).isEqualTo(BackupState.DONE)
        listOf(gone, incomplete).forEach {
            val item = f.item(it.id)!!
            assertThat(item.run { backupState to driveFileId }).isEqualTo(BackupState.NONE to null)
            assertThat(item.localFile!!.isFile).isTrue()
            assertThat(f.store.isExcluded(it.id)).isTrue()
        }
        assertThat(f.api.deleted).isEmpty()
    }

    @Test
    fun `an item without its phone copy fails without contacting Drive`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        item.localFile!!.delete()

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Failed(BackupErrors.NO_LOCAL_COPY))
        assertThat(f.api.sessions).isEmpty()
        assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.FAILED)
    }

    @Test
    fun `only queued items are uploaded`() = runTest {
        val f = fixture()
        val item = f.local("/sim/EVENT/e1.mp4")
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Skipped)
        assertThat(f.api.sessions).isEmpty()
    }
}
