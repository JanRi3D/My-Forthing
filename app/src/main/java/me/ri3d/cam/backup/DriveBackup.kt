package me.ri3d.cam.backup

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.drive.DriveApi
import me.ri3d.cam.drive.DriveAuth
import me.ri3d.cam.drive.DriveAuthState
import me.ri3d.cam.drive.DriveError
import me.ri3d.cam.drive.DriveFile
import me.ri3d.cam.drive.format.DriveFormat
import me.ri3d.cam.drive.format.DriveFormatReader
import me.ri3d.cam.drive.format.DriveSidecar
import me.ri3d.cam.media.BackupState
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaRepository
import me.ri3d.cam.plates.PlateExport
import java.io.File
import java.net.URLConnection
import javax.inject.Inject
import javax.inject.Singleton

/** Why the queue stopped: Drive not connected, access to renew, or Drive full. */
enum class PauseReason { NOT_CONNECTED, RECONNECT, STORAGE_FULL }

sealed interface BackupOutcome {
    /** Verified, sidecar written, item DONE. */
    data object Done : BackupOutcome

    /** Nothing to do: cancelled, reset or deleted meanwhile. */
    data object Skipped : BackupOutcome

    /** Try again later (item QUEUED): offline, or a failure below [DriveBackup.MAX_ATTEMPTS]. */
    data object Retry : BackupOutcome

    /** Item FAILED with [error] (a `MediaItem.backupError` value). */
    data class Failed(val error: String) : BackupOutcome

    /** Item stays QUEUED; nothing uploads until the reason is gone. */
    data class Paused(val reason: PauseReason) : BackupOutcome
}

/** `MediaItem.backupError` values: a name, `HTTP:<code>:<reason>` or `AUTH:<status>`. */
object BackupErrors {
    const val NO_LOCAL_COPY = "NO_LOCAL_COPY"
    const val MD5_MISMATCH = "MD5_MISMATCH"
    const val DRIVE_CONFLICT = "DRIVE_CONFLICT"

    fun of(e: Throwable): String = when (e) {
        is Md5Mismatch -> MD5_MISMATCH
        is DriveError.Http -> "HTTP:${e.code}:${e.reason.orEmpty()}"
        is DriveError.Authorization -> "AUTH:${e.statusCode}"
        else -> "ERROR:${e.javaClass.simpleName}"
    }
}

private class Md5Mismatch : Exception("Drive md5Checksum differs from the phone copy")

private class DriveConflict : Exception("Drive holds a verified file with other content for this id")

/**
 * The queue's private bookkeeping (SharedPreferences `backup_queue`; Android backup is off app-wide): the Drive
 * account the backup states belong to, upload session URIs, failure counts, exclusions and the storage pause.
 */
@Singleton
class BackupStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _storageFull = MutableStateFlow(prefs.getBoolean(STORAGE_FULL, false))

    /** Paused because Drive is full; cleared by the user ("Fortsetzen", "Sichern", "Jetzt prüfen") or an account switch. */
    val storageFull: StateFlow<Boolean> = _storageFull.asStateFlow()

    /** Lower-case e-mail of the Drive account the backup states belong to. */
    var account: String?
        get() = prefs.getString(ACCOUNT, null)
        set(value) = prefs.edit { putString(ACCOUNT, value) }

    /** A resumable session can finish an upload without the auth header: it never outlives its account. */
    fun session(id: String): String? = prefs.getString(SESSION + id, null)

    fun setSession(id: String, uri: String?) = prefs.edit { if (uri == null) remove(SESSION + id) else putString(SESSION + id, uri) }

    /** Counts a real failure (offline waits are not counted); returns the new count. */
    fun countFailure(id: String): Int = (prefs.getInt(ATTEMPTS + id, 0) + 1).also { n -> prefs.edit { putInt(ATTEMPTS + id, n) } }

    fun clearFailures(id: String) = prefs.edit { remove(ATTEMPTS + id) }

    /** Cancelled, or its Drive copy deleted, by the user: the automatic rules skip it until it is backed up manually. */
    fun isExcluded(id: String): Boolean = prefs.contains(EXCLUDED + id)

    fun exclude(id: String) = prefs.edit { putBoolean(EXCLUDED + id, true) }

    fun include(id: String) = prefs.edit { remove(EXCLUDED + id) }

    fun setStorageFull(full: Boolean) {
        prefs.edit { putBoolean(STORAGE_FULL, full) }
        _storageFull.value = full
    }

    /** Account switch: sessions, failure counts and the storage pause belonged to the previous account. */
    fun forgetAccountState() {
        val stale = prefs.all.keys.filter { it.startsWith(SESSION) || it.startsWith(ATTEMPTS) }
        prefs.edit {
            stale.forEach(::remove)
            remove(STORAGE_FULL)
        }
        _storageFull.value = false
    }

    private companion object {
        const val PREFS = "backup_queue"
        const val ACCOUNT = "account"
        const val STORAGE_FULL = "storage_full"
        const val SESSION = "session:"
        const val ATTEMPTS = "attempts:"
        const val EXCLUDED = "excluded:"
    }
}

/**
 * The Drive side of backups (CONTRACTS §10, docs/features/backup.md): verified upload of one phone copy, deleting the
 * Drive copy, reconciling with Drive, and the account-switch reset. Uploads run one at a time ([upload] holds a lock).
 */
@Singleton
class DriveBackup @Inject constructor(
    private val api: DriveApi,
    private val auth: DriveAuth,
    private val repository: MediaRepository,
    private val preferences: PreferencesRepository,
    private val plates: PlateExport,
    private val store: BackupStore,
) {
    private val uploadLock = Mutex()
    private val accountLock = Mutex()

    /**
     * Uploads the phone copy of [id] if it is QUEUED/UPLOADING: duplicate check by `mf.id` (a Drive file with the same
     * MD5 is adopted), resumable upload with a persisted session URI, `md5Checksum` verification, then the sidecar,
     * and only then DONE with `driveFileId` / `driveMd5`.
     */
    suspend fun upload(id: String, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): BackupOutcome = uploadLock.withLock {
        val email = accountOf(auth.state.value) ?: return@withLock BackupOutcome.Paused(notConnectedReason())
        adoptAccount(email)
        val item = repository.update(id) {
            if (it.backupState in BackupRules.PENDING) it.copy(backupState = BackupState.UPLOADING, backupError = null) else it
        }
        if (item?.backupState != BackupState.UPLOADING) return@withLock BackupOutcome.Skipped
        val file = item.localFile?.takeIf { it.isFile && it.length() > 0 } ?: return@withLock fail(id, BackupErrors.NO_LOCAL_COPY)
        try {
            val (media, md5) = transfer(item, file, onProgress)
            // An account switch meanwhile resets every state: then this result belongs to the old account.
            repository.update(id) {
                if (it.backupState == BackupState.UPLOADING && accountOf(auth.state.value) == email) {
                    it.copy(backupState = BackupState.DONE, driveFileId = media.id, driveMd5 = md5, backupError = null)
                } else {
                    it
                }
            }
            store.clearFailures(id)
            BackupOutcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "upload stopped: ${e.javaClass.simpleName}")
            handle(id, e)
        }
    }

    private suspend fun transfer(item: MediaItem, file: File, onProgress: (Long, Long) -> Unit): Pair<DriveFile, String> {
        val md5 = withContext(Dispatchers.IO) { DriveFormat.md5Hex(file) }
        val mime = URLConnection.guessContentTypeFromName(file.name.lowercase()) ?: if (item.isVideo) "video/mp4" else "application/octet-stream"
        val root = api.ensureRootFolder().getOrThrow()
        val month = api.ensureMonthFolder(root, DriveFormat.monthFolderName(item.recorderTimeEpochGuess, item.downloadedAt ?: item.createdAt)).getOrThrow()
        val existing = api.list(byIdQuery(item.id)).getOrThrow()
        val media = existing.filter { it.role == DriveFormat.ROLE_MEDIA }
        val uploaded = media.filter { it.md5Checksum.equals(md5, ignoreCase = true) }.minByOrNull { it.createdTime.orEmpty() } ?: run {
            if (media.isNotEmpty()) {
                // A sidecar is only ever written next to a verified file: with one present, Drive holds another version.
                if (existing.any { it.role == DriveFormat.ROLE_SIDECAR }) throw DriveConflict()
                media.forEach { api.delete(it.id).getOrThrow() } // unverified uploads of this item
            }
            uploadVerified(item, file, mime, month, md5, onProgress)
        }
        val sidecar = sidecar(item, file, md5, mime).toJson()
        api.writeJson(DriveFormat.sidecarFileName(item.id), uploaded.parents.firstOrNull() ?: month, sidecar, DriveFormat.sidecarAppProperties(item.id))
            .getOrThrow()
        return uploaded to md5
    }

    private suspend fun uploadVerified(item: MediaItem, file: File, mime: String, folder: String, md5: String, onProgress: (Long, Long) -> Unit): DriveFile {
        val uploaded = api.uploadResumable(
            file, DriveFormat.mediaFileName(item.id, item.originalFileName, mime), mime, folder,
            DriveFormat.mediaAppProperties(item.id, item.kind.name, item.category.name, item.parentId),
            store.session(item.id), onSessionUri = { store.setSession(item.id, it) }, onProgress,
        ).getOrThrow()
        store.setSession(item.id, null) // used up
        if (!uploaded.md5Checksum.equals(md5, ignoreCase = true)) {
            api.delete(uploaded.id).getOrThrow() // our unverified copy; the phone copy stays
            throw Md5Mismatch()
        }
        return uploaded
    }

    private suspend fun sidecar(item: MediaItem, file: File, md5: String, mime: String): DriveSidecar {
        // null = the user keeps plate metadata local; [] = opted in, nothing recognised in this item.
        val sightings = if (preferences.preferences.first().backupIncludePlateMetadata) {
            plates.forMedia(item.id).map { DriveSidecar.Plate(it.text, it.normalized, it.positionMs, it.confidence, it.box) }
        } else {
            null
        }
        return DriveSidecar(
            id = item.id,
            kind = item.kind.name,
            category = item.category.name,
            recorderType = item.recorderType,
            originalFileName = item.originalFileName,
            recorderPath = item.recorderPath,
            recorderTime = item.recorderTime,
            downloadedAt = item.downloadedAt?.let { DriveFormat.isoTimestamp(it) },
            sizeBytes = file.length(),
            md5 = md5,
            mime = mime,
            durationMs = if (item.isVideo) durationMs(file) else null,
            parent = item.parentId?.let { DriveSidecar.Parent(it, item.parentPositionMs) },
            plates = sightings,
            backup = DriveSidecar.Backup(complete = true, completedAt = DriveFormat.isoTimestamp(System.currentTimeMillis())),
        )
    }

    private suspend fun handle(id: String, e: Exception): BackupOutcome {
        val outcome = when {
            e is DriveError.InsufficientStorage -> {
                store.setStorageFull(true)
                BackupOutcome.Paused(PauseReason.STORAGE_FULL)
            }
            e is DriveError.NotConnected || e is DriveError.NeedsReconnect && auth.state.value !is DriveAuthState.Connected ->
                BackupOutcome.Paused(notConnectedReason())
            e is DriveError.Offline -> BackupOutcome.Retry // waits; the session URI resumes it
            e is DriveConflict -> return fail(id, BackupErrors.DRIVE_CONFLICT)
            store.countFailure(id) < MAX_ATTEMPTS -> {
                store.setSession(id, null) // a session that failed this way starts over
                BackupOutcome.Retry
            }
            else -> return fail(id, BackupErrors.of(e))
        }
        repository.update(id) { if (it.backupState == BackupState.UPLOADING) it.copy(backupState = BackupState.QUEUED) else it }
        return outcome
    }

    private suspend fun fail(id: String, error: String): BackupOutcome {
        store.clearFailures(id)
        repository.update(id) { if (it.backupState == BackupState.UPLOADING) it.copy(backupState = BackupState.FAILED, backupError = error) else it }
        return BackupOutcome.Failed(error)
    }

    /**
     * Removes every Drive file of [id] (media first, then the sidecar) and forgets the Drive copy; phone and recorder
     * copies stay. The automatic rules then skip the item until it is backed up manually.
     */
    suspend fun deleteOnDrive(id: String): Result<Unit> = driveCall {
        val email = accountOf(auth.state.value) ?: throw DriveError.NotConnected()
        adoptAccount(email)
        store.exclude(id) // before the state changes, so the automatic rules do not upload it again at once
        val item = repository.get(id) ?: return@driveCall
        // Only files listed in the current account (delete() reports a missing file as success).
        val files = api.list(byIdQuery(id)).getOrThrow().sortedBy { it.role != DriveFormat.ROLE_MEDIA }
        (files.map { it.id } + listOfNotNull(item.driveFileId)).distinct().forEach { api.delete(it).getOrThrow() }
        repository.markDriveDeleted(id)
    }

    /**
     * "Drive-Status prüfen": DONE items whose media file or sidecar is no longer in Drive are not backed up any more
     * (state NONE, excluded from the automatic rules). Never deletes anything. Returns how many were missing.
     */
    suspend fun reconcile(): Result<Int> = driveCall {
        val email = accountOf(auth.state.value) ?: throw DriveError.NotConnected()
        adoptAccount(email)
        // Taken before listing, so an upload finishing meanwhile is not judged by the older listing.
        val done = repository.observe().first().filter { it.backupState == BackupState.DONE }
        val complete = DriveFormatReader.scan(api).getOrThrow().filter { it.complete }.map { it.mediaId }.toSet()
        val missing = done.filter { it.id !in complete }
        missing.forEach {
            store.exclude(it.id)
            repository.markDriveDeleted(it.id)
        }
        missing.size
    }

    /**
     * Account switch (CONTRACTS §10): when [email] differs from the account the states belong to, every Drive field
     * and state is reset and the stored session URIs are dropped. Returns true when it reset.
     */
    suspend fun adoptAccount(email: String): Boolean = accountLock.withLock {
        val account = email.lowercase()
        val previous = store.account
        if (previous == account) return@withLock false
        if (previous != null) {
            store.forgetAccountState()
            repository.observe().first()
                .filter { it.driveFileId != null || it.driveMd5 != null || it.backupState != BackupState.NONE }
                .forEach { repository.markDriveDeleted(it.id) }
        }
        store.account = account
        previous != null
    }

    private fun notConnectedReason() =
        if (auth.state.value is DriveAuthState.NeedsReconnect) PauseReason.RECONNECT else PauseReason.NOT_CONNECTED

    companion object {
        private const val TAG = "DriveBackup"

        /** Real failures per item before it is FAILED (offline waits are not counted). */
        const val MAX_ATTEMPTS = 5

        fun accountOf(state: DriveAuthState): String? = (state as? DriveAuthState.Connected)?.accountEmail?.lowercase()

        /** Every Drive file (media, sidecar) of one item. */
        fun byIdQuery(id: String) = "appProperties has { key='${DriveFormat.KEY_ID}' and value='${DriveFormat.escape(id)}' } and trashed = false"

        private val DriveFile.role: String? get() = appProperties[DriveFormat.KEY_ROLE]

        private fun durationMs(file: File): Long? = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull()

        /** [block]'s exceptions become `Result.failure`; cancellation propagates. */
        private suspend fun <T> driveCall(block: suspend () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
