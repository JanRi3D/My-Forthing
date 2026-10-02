package me.ri3d.dashcam.backup

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
import me.ri3d.dashcam.drive.DRIVE_FILE_SCOPE
import me.ri3d.dashcam.drive.DriveAuthState
import me.ri3d.dashcam.drive.DriveError
import me.ri3d.dashcam.drive.format.DriveFormat
import me.ri3d.dashcam.drive.format.DriveSidecar
import me.ri3d.dashcam.media.BackupState
import me.ri3d.dashcam.media.memoryDb
import me.ri3d.dashcam.plates.Plate
import me.ri3d.dashcam.plates.PlateSighting
import me.ri3d.dashcam.plates.SightingSource
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

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
        assertThat(f.store.currentSession(item.id)).isNull()
    }

    @Test
    fun `an interrupted upload resumes from the persisted session URI`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.uploadFailures += { DriveError.Offline(IOException("gone")) }

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry)
        assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.QUEUED)
        val session = f.store.currentSession(item.id)
        assertThat(session).startsWith("https://upload.example/session-")

        // After a restart (new DriveBackup, same store) the upload continues in the same session.
        val restarted = DriveBackup(f.api, f.auth, f.repository, f.preferences, me.ri3d.dashcam.plates.PlateExport(db.plateDao(), f.preferences), BackupStore(context))
        assertThat(restarted.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.sessions).containsExactly(null, session).inOrder()
        assertThat(f.store.currentSession(item.id)).isNull()
    }

    @Test
    fun `a server error keeps the session to resume, a rejected request drops it`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.uploadFailures += { DriveError.Http(503, null) }
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry)
        val session = f.store.currentSession(item.id)
        assertThat(session).isNotNull()

        f.api.uploadFailures += { DriveError.Http(400, "badRequest") }
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry)
        assertThat(f.api.sessions.last()).isEqualTo(session) // resumed after the 503
        assertThat(f.store.currentSession(item.id)).isNull() // not after the 400

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.sessions.last()).isNull()
    }

    @Test
    fun `adopting a completed upload clears its session and a later backup never reuses it`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        val props = DriveFormat.mediaAppProperties(item.id, "ORIGINAL_VIDEO", "EVENT", null)
        f.api.uploadFailures += { // Drive completed the upload, the answer got lost
            f.api.files += f.api.driveFile("completed", "${item.id}.mp4", props, "month-x", DriveFormat.md5Hex(item.localFile!!))
            DriveError.Offline(IOException("answer lost"))
        }
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry)
        assertThat(f.store.currentSession(item.id)).isNotNull()

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.item(item.id)!!.driveFileId).isEqualTo("completed")
        assertThat(f.store.currentSession(item.id)).isNull()

        assertThat(f.backup.deleteOnDrive(item.id).isSuccess).isTrue()
        f.repository.update(item.id) { it.copy(backupState = BackupState.QUEUED) }
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.sessions).containsExactly(null, null).inOrder() // a fresh session, never the completed one
    }

    @Test
    fun `a session stored for another account is never used`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.backup.adoptAccount("a@example.com")
        val oldAccount = f.store.account!!
        f.auth.state.value = DriveAuthState.Connected("b@example.com", setOf(DRIVE_FILE_SCOPE))
        f.backup.adoptAccount("b@example.com")
        f.store.setSession(item.id, oldAccount, "https://upload.example/late") // a late callback of the old account's upload
        f.repository.update(item.id) { it.copy(backupState = BackupState.QUEUED) }

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.sessions).containsExactly(null)
    }

    @Test
    fun `a failing sidecar write keeps the item queued and the next attempt adopts the upload`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.writeHooks += { DriveError.Http(500, null) }

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry)
        assertThat(f.item(item.id)!!.run { backupState to driveFileId }).isEqualTo(BackupState.QUEUED to null)
        assertThat(f.api.of(item.id, DriveFormat.ROLE_SIDECAR)).isEmpty()

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.sessions).hasSize(1) // uploaded once
        assertThat(f.item(item.id)!!.driveFileId).isEqualTo(f.api.of(item.id, DriveFormat.ROLE_MEDIA).single().id)
    }

    @Test
    fun `an account switch during the transfer never marks the item DONE`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.writeHooks += {
            f.auth.state.value = DriveAuthState.Connected("b@example.com", setOf(DRIVE_FILE_SCOPE))
            null
        }

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Skipped)

        val reset = f.item(item.id)!!
        assertThat(listOf(reset.backupState, reset.driveFileId)).containsExactly(BackupState.NONE, null).inOrder()
        assertThat(f.backup.adoptAccount("b@example.com")).isFalse() // already reset for the new account
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
    fun `our own unverified upload is replaced at once`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.api.corrupt = true
        f.api.deleteHooks += { DriveError.Offline(IOException("gone")) } // deleting the corrupt upload fails

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry)
        val corrupt = f.api.of(item.id, DriveFormat.ROLE_MEDIA).single().id
        assertThat(f.store.unverified(item.id)).isEqualTo(corrupt)

        f.api.corrupt = false
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.deleted).containsExactly(corrupt)
        assertThat(f.api.of(item.id, DriveFormat.ROLE_MEDIA).map { it.id }).containsExactly(f.item(item.id)!!.driveFileId)
        assertThat(f.store.unverified(item.id)).isNull()
    }

    @Test
    fun `another unverified file waits a day before it is replaced, a verified other version is never touched`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        val props = DriveFormat.mediaAppProperties(item.id, "ORIGINAL_VIDEO", "EVENT", null)
        f.api.files += f.api.driveFile("recent", "${item.id}.mp4", props, "m", "f".repeat(32))

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Retry) // maybe an upload still being verified
        assertThat(f.api.deleted).isEmpty()
        assertThat(f.api.sessions).isEmpty()
        assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.QUEUED)

        f.api.files.clear()
        f.api.files += f.api.driveFile("old", "${item.id}.mp4", props, "m", "f".repeat(32), Instant.now().minus(25, ChronoUnit.HOURS))
        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)
        assertThat(f.api.deleted).containsExactly("old")

        val other = f.queued("/sim/EVENT/e2.mp4")
        f.api.files += f.api.driveFile("verified", "${other.id}.mp4", DriveFormat.mediaAppProperties(other.id, "ORIGINAL_VIDEO", "EVENT", null), "m", "f".repeat(32))
        f.api.files += f.api.driveFile("side", "${other.id}.json", DriveFormat.sidecarAppProperties(other.id), "m", null)
        assertThat(f.backup.upload(other.id)).isEqualTo(BackupOutcome.Failed(BackupErrors.DRIVE_CONFLICT))
        assertThat(f.api.deleted).containsExactly("old")
    }

    @Test
    fun `adopting also removes unverified copies, since readers take the oldest media file`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        val props = DriveFormat.mediaAppProperties(item.id, "ORIGINAL_VIDEO", "EVENT", null)
        f.api.files += f.api.driveFile("bad", "${item.id}.mp4", props, "m", "f".repeat(32), Instant.now().minus(25, ChronoUnit.HOURS))
        f.api.files += f.api.driveFile("good", "${item.id}.mp4", props, "m", DriveFormat.md5Hex(item.localFile!!))

        assertThat(f.backup.upload(item.id)).isEqualTo(BackupOutcome.Done)

        assertThat(f.api.deleted).containsExactly("bad")
        assertThat(f.item(item.id)!!.driveFileId).isEqualTo("good")
        assertThat(f.api.sessions).isEmpty()
    }

    @Test
    fun `an account switch resets every Drive state and drops the session URIs`() = runTest {
        val f = fixture()
        val done = f.queued("/sim/EVENT/e1.mp4")
        assertThat(f.backup.upload(done.id)).isEqualTo(BackupOutcome.Done)
        val waiting = f.queued("/sim/EVENT/e2.mp4")
        f.api.uploadFailures += { DriveError.Offline(IOException("gone")) }
        assertThat(f.backup.upload(waiting.id)).isEqualTo(BackupOutcome.Retry)
        assertThat(f.store.currentSession(waiting.id)).isNotNull()
        val oldAccount = f.store.account!!
        val uploads = f.api.sessions.size
        f.store.exclude(done.id)

        // The worker meets the new account first (before the queue's observer resets anything).
        f.auth.state.value = DriveAuthState.Connected("B@example.com", setOf(DRIVE_FILE_SCOPE))
        assertThat(f.backup.upload(waiting.id)).isEqualTo(BackupOutcome.Skipped)

        assertThat(f.api.sessions).hasSize(uploads) // the old session was never used with the new account
        assertThat(f.store.session(waiting.id, oldAccount)).isNull()
        assertThat(f.store.isExcluded(done.id)).isFalse() // exclusions belonged to the old account
        listOf(done.id, waiting.id).forEach { id ->
            val reset = f.item(id)!!
            assertThat(listOf(reset.backupState, reset.driveFileId, reset.driveMd5)).containsExactly(BackupState.NONE, null, null).inOrder()
            assertThat(reset.localFile!!.isFile).isTrue()
        }
        assertThat(f.store.account).doesNotContain("@") // only a hash of the e-mail is stored
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
        f.store.setSession(item.id, f.store.account!!, "https://upload.example/leftover")

        assertThat(f.backup.deleteOnDrive(item.id).isSuccess).isTrue()

        assertThat(f.api.deleted).containsExactly(media, sidecar).inOrder()
        val kept = f.item(item.id)!!
        assertThat(listOf(kept.backupState, kept.driveFileId, kept.driveMd5)).containsExactly(BackupState.NONE, null, null).inOrder()
        assertThat(kept.localFile!!.isFile).isTrue()
        assertThat(kept.recorderPath).isEqualTo("/sim/EVENT/e1.mp4")
        assertThat(f.store.isExcluded(item.id)).isTrue()
        assertThat(f.store.currentSession(item.id)).isNull()
    }

    @Test
    fun `deleting fails cleanly without a connection or offline and changes nothing`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.backup.upload(item.id)
        f.api.deleteHooks += { DriveError.Offline(IOException("gone")) }

        assertThat(f.backup.deleteOnDrive(item.id).exceptionOrNull()).isInstanceOf(DriveError.Offline::class.java)
        f.auth.state.value = DriveAuthState.NotConnected
        assertThat(f.backup.deleteOnDrive(item.id).exceptionOrNull()).isInstanceOf(DriveError.NotConnected::class.java)

        assertThat(f.api.deleted).isEmpty()
        assertThat(f.item(item.id)!!.backupState).isEqualTo(BackupState.DONE)
        assertThat(f.store.isExcluded(item.id)).isFalse()
    }

    @Test
    fun `a sidecar left by a half-done delete is no backup and can be deleted again`() = runTest {
        val f = fixture()
        val item = f.queued("/sim/EVENT/e1.mp4")
        f.backup.upload(item.id)
        f.api.deleteHooks += { null } // media
        f.api.deleteHooks += { DriveError.Offline(IOException("gone")) } // sidecar

        assertThat(f.backup.deleteOnDrive(item.id).isFailure).isTrue()
        val left = f.item(item.id)!!
        assertThat(listOf(left.backupState, left.driveFileId, left.backupError)).containsExactly(BackupState.NONE, null, BackupErrors.SIDECAR_LEFT).inOrder()
        assertThat(f.api.of(item.id, DriveFormat.ROLE_SIDECAR)).hasSize(1)

        assertThat(f.backup.deleteOnDrive(item.id).isSuccess).isTrue()
        assertThat(f.api.files).isEmpty()
        assertThat(f.item(item.id)!!.backupError).isNull()
    }

    @Test
    fun `reconciliation forgets backups that vanished from Drive and deletes nothing`() = runTest {
        val f = fixture()
        val kept = f.queued("/sim/EVENT/e1.mp4")
        val gone = f.queued("/sim/EVENT/e2.mp4")
        val incomplete = f.queued("/sim/EVENT/e3.mp4")
        val fresh = f.queued("/sim/EVENT/e4.mp4")
        listOf(kept, gone, incomplete, fresh).forEach { f.backup.upload(it.id) }
        listOf(kept, gone, incomplete).forEach { f.store.setDoneAt(it.id, System.currentTimeMillis() - DriveBackup.LISTING_LAG_MS - 1) }
        f.api.files.removeAll(f.api.of(gone.id, DriveFormat.ROLE_MEDIA)) // the user deleted it in Drive
        f.api.files.removeAll(f.api.of(incomplete.id, DriveFormat.ROLE_SIDECAR))
        f.api.files.removeAll(f.api.of(fresh.id, DriveFormat.ROLE_MEDIA)) // just done: Drive's listing may lag behind

        assertThat(f.backup.reconcile().getOrThrow()).isEqualTo(2)

        assertThat(f.item(kept.id)!!.backupState).isEqualTo(BackupState.DONE)
        assertThat(f.item(fresh.id)!!.backupState).isEqualTo(BackupState.DONE)
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
