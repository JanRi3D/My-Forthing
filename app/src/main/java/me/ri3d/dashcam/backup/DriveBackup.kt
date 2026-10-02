package me.ri3d.dashcam.backup

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
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.core.log.Log
import me.ri3d.dashcam.drive.DriveApi
import me.ri3d.dashcam.drive.DriveAuth
import me.ri3d.dashcam.drive.DriveAuthState
import me.ri3d.dashcam.drive.DriveError
import me.ri3d.dashcam.drive.DriveFile
import me.ri3d.dashcam.drive.format.DriveFormat
import me.ri3d.dashcam.drive.format.DriveFormatReader
import me.ri3d.dashcam.drive.format.DriveSidecar
import me.ri3d.dashcam.media.BackupState
import me.ri3d.dashcam.media.MediaItem
import me.ri3d.dashcam.media.MediaRepository
import me.ri3d.dashcam.plates.PlateExport
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.time.Instant
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

    /** On a NONE row: the media file was deleted in Drive, its sidecar not; "Drive-Kopie löschen" stays offered. */
    const val SIDECAR_LEFT = "SIDECAR_LEFT"

    fun of(e: Throwable): String = when (e) {
        is Md5Mismatch -> MD5_MISMATCH
        is DriveError.Http -> "HTTP:${e.code}:${e.reason.orEmpty()}"
        is DriveError.Authorization -> "AUTH:${e.statusCode}"
        else -> "ERROR:${e.javaClass.simpleName}"
    }
}

private class Md5Mismatch : Exception("Drive md5Checksum differs from the phone copy")

private class DriveConflict : Exception("Drive holds a verified file with other content for this id")

/** A sidecar-less file of this id with other content that is neither our recorded upload nor a day old: wait. */
private class UnverifiedCopy : Exception("unverified Drive file of unknown origin, too recent to delete")

/**
 * The queue's private bookkeeping (SharedPreferences `backup_queue`; Android backup is off app-wide): the Drive
 * account the backup states belong to, upload session URIs, unverified uploads, completion times, failure counts,
 * exclusions and the storage pause.
 */
@Singleton
class BackupStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _storageFull = MutableStateFlow(prefs.getBoolean(STORAGE_FULL, false))

    /** Paused because Drive is full; cleared by the user ("Fortsetzen", "Sichern", "Jetzt prüfen") or an account switch. */
    val storageFull: StateFlow<Boolean> = _storageFull.asStateFlow()

    /** SHA-256 of the lower-case e-mail of the Drive account the backup states belong to (compared only). */
    var account: String?
        get() = prefs.getString(ACCOUNT, null)
        set(value) = prefs.edit { putString(ACCOUNT, value) }

    /**
     * A resumable session can finish an upload without the auth header, so it is stored with the [account] (hash) it
     * was started for and never handed out for another one (also when a late callback writes it after a switch).
     */
    fun session(id: String, account: String): String? =
        prefs.getString(SESSION + id, null)?.takeIf { it.startsWith("$account ") }?.substringAfter(' ')

    fun setSession(id: String, account: String, uri: String) = prefs.edit { putString(SESSION + id, "$account $uri") }

    fun clearSession(id: String) = prefs.edit { remove(SESSION + id) }

    /** Drive id of this item's last upload that is not verified yet: safe to delete when its md5 is wrong. */
    fun unverified(id: String): String? = prefs.getString(UNVERIFIED + id, null)

    fun setUnverified(id: String, fileId: String?) =
        prefs.edit { if (fileId == null) remove(UNVERIFIED + id) else putString(UNVERIFIED + id, fileId) }

    /** When the item became DONE (0 = unknown); "Drive-Status prüfen" gives Drive's listing time to catch up. */
    fun doneAt(id: String): Long = prefs.getLong(DONE_AT + id, 0)

    fun setDoneAt(id: String, at: Long?) = prefs.edit { if (at == null) remove(DONE_AT + id) else putLong(DONE_AT + id, at) }

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

    private val _lastImport = MutableStateFlow(prefs.getLong(LAST_IMPORT, 0).takeIf { it > 0 })

    /** When the last import from Drive ended (Drive tab "Stand"); null before the first one for the current account. */
    val lastImport: StateFlow<Long?> = _lastImport.asStateFlow()

    fun setLastImport(at: Long) {
        prefs.edit { putLong(LAST_IMPORT, at) }
        _lastImport.value = at
    }

    /** The user disconnected Drive on this phone: no silent reconnect from the app account until they connect again. */
    var driveDisconnected: Boolean
        get() = prefs.getBoolean(DRIVE_DISCONNECTED, false)
        set(value) = prefs.edit { putBoolean(DRIVE_DISCONNECTED, value) }

    /** Account switch: everything stored per item, the storage pause and the import time belonged to the previous account. */
    fun forgetAccountState() {
        val stale = prefs.all.keys.filter { key -> PER_ITEM.any { key.startsWith(it) } }
        prefs.edit {
            stale.forEach(::remove)
            remove(STORAGE_FULL)
            remove(LAST_IMPORT)
        }
        _storageFull.value = false
        _lastImport.value = null
    }

    private companion object {
        const val PREFS = "backup_queue"
        const val ACCOUNT = "account"
        const val STORAGE_FULL = "storage_full"
        const val LAST_IMPORT = "last_import"
        const val DRIVE_DISCONNECTED = "drive_disconnected"
        const val SESSION = "session:"
        const val UNVERIFIED = "unverified:"
        const val DONE_AT = "done_at:"
        const val ATTEMPTS = "attempts:"
        const val EXCLUDED = "excluded:"
        val PER_ITEM = listOf(SESSION, UNVERIFIED, DONE_AT, ATTEMPTS, EXCLUDED)
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

    val connected: Boolean get() = auth.state.value is DriveAuthState.Connected

    /**
     * Uploads the phone copy of [id] if it is QUEUED/UPLOADING: duplicate check by `mf.id` (a Drive file with the same
     * MD5 is adopted), resumable upload with a persisted session URI, `md5Checksum` verification, then the sidecar,
     * and only then DONE with `driveFileId` / `driveMd5`.
     */
    suspend fun upload(id: String, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): BackupOutcome = uploadLock.withLock {
        val email = accountOf(auth.state.value) ?: return@withLock BackupOutcome.Paused(notConnectedReason())
        adoptAccount(email)
        val account = keyOf(email)
        val item = repository.update(id) {
            if (it.backupState in BackupRules.PENDING) it.copy(backupState = BackupState.UPLOADING, backupError = null) else it
        }
        if (item?.backupState != BackupState.UPLOADING) return@withLock BackupOutcome.Skipped
        val file = item.localFile?.takeIf { it.isFile && it.length() > 0 } ?: return@withLock fail(id, BackupErrors.NO_LOCAL_COPY)
        try {
            val (media, md5) = transfer(item, file, account, onProgress)
            store.clearSession(id) // settled (uploaded or adopted): a completed session is never reused
            // An account switch meanwhile resets every state: then this result belongs to the old account.
            val done = repository.update(id) {
                if (it.backupState == BackupState.UPLOADING && accountOf(auth.state.value) == email) {
                    it.copy(backupState = BackupState.DONE, driveFileId = media.id, driveMd5 = md5, backupError = null)
                } else {
                    it
                }
            }
            if (done?.backupState != BackupState.DONE) {
                accountOf(auth.state.value)?.let { adoptAccount(it) } // resets now when the account changed
                return@withLock BackupOutcome.Skipped
            }
            store.clearFailures(id)
            store.setDoneAt(id, System.currentTimeMillis())
            BackupOutcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "upload stopped: ${e.javaClass.simpleName}")
            handle(id, e)
        }
    }

    private suspend fun transfer(item: MediaItem, file: File, account: String, onProgress: (Long, Long) -> Unit): Pair<DriveFile, String> {
        val md5 = withContext(Dispatchers.IO) { DriveFormat.md5Hex(file) }
        val mime = URLConnection.guessContentTypeFromName(file.name.lowercase()) ?: if (item.isVideo) "video/mp4" else "application/octet-stream"
        val root = api.ensureRootFolder().getOrThrow()
        val month = api.ensureMonthFolder(root, DriveFormat.monthFolderName(item.recorderTimeEpochGuess, item.downloadedAt ?: item.createdAt)).getOrThrow()
        val existing = api.list(byIdQuery(item.id)).getOrThrow()
        val media = existing.filter { it.role == DriveFormat.ROLE_MEDIA }
        // Resumable uploads appear only once complete: a listed media file means its session is used up.
        if (media.isNotEmpty()) store.clearSession(item.id)
        val (same, other) = media.partition { it.md5Checksum.equals(md5, ignoreCase = true) }
        if (other.isNotEmpty()) {
            // A sidecar is only ever written next to a verified file: with one present and nothing matching, Drive holds
            // another version, which is never touched.
            if (same.isEmpty() && existing.any { it.role == DriveFormat.ROLE_SIDECAR }) throw DriveConflict()
            // Readers take the oldest media file per id, so unverified ones go before a sidecar is written: our recorded
            // upload at once, anything else only when it is a day old (it may be an upload still being verified).
            for (stale in other) {
                if (stale.id != store.unverified(item.id) && !stale.olderThan(UNVERIFIED_GRACE_MS)) throw UnverifiedCopy()
                api.delete(stale.id).getOrThrow()
            }
            store.setUnverified(item.id, null)
        }
        val uploaded = same.minByOrNull { it.createdTime.orEmpty() } ?: uploadVerified(item, file, mime, month, md5, account, onProgress)
        val sidecar = sidecar(item, file, md5, mime).toJson()
        api.writeJson(DriveFormat.sidecarFileName(item.id), uploaded.parents.firstOrNull() ?: month, sidecar, DriveFormat.sidecarAppProperties(item.id))
            .getOrThrow()
        return uploaded to md5
    }

    private suspend fun uploadVerified(
        item: MediaItem,
        file: File,
        mime: String,
        folder: String,
        md5: String,
        account: String,
        onProgress: (Long, Long) -> Unit,
    ): DriveFile {
        val uploaded = api.uploadResumable(
            file, DriveFormat.mediaFileName(item.id, item.originalFileName, mime), mime, folder,
            DriveFormat.mediaAppProperties(item.id, item.kind.name, item.category.name, item.parentId),
            store.session(item.id, account), onSessionUri = { store.setSession(item.id, account, it) }, onProgress,
        ).getOrThrow()
        store.clearSession(item.id) // used up
        store.setUnverified(item.id, uploaded.id)
        if (!uploaded.md5Checksum.equals(md5, ignoreCase = true)) {
            api.delete(uploaded.id).getOrThrow() // our unverified copy; the phone copy stays
            store.setUnverified(item.id, null)
            throw Md5Mismatch()
        }
        store.setUnverified(item.id, null)
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
        // A rejected request (4xx) will not work in that session again; 5xx / 429 / network failures keep it to resume.
        if (e is DriveError.Http && e.code in 400..499 && e.code != 408 && e.code != 429) store.clearSession(id)
        val outcome = when {
            e is DriveError.InsufficientStorage -> {
                store.setStorageFull(true)
                BackupOutcome.Paused(PauseReason.STORAGE_FULL)
            }
            e is DriveError.NotConnected || e is DriveError.NeedsReconnect && auth.state.value !is DriveAuthState.Connected ->
                BackupOutcome.Paused(notConnectedReason())
            e is DriveError.Offline || e is UnverifiedCopy -> BackupOutcome.Retry // waits without counting
            e is DriveConflict -> return fail(id, BackupErrors.DRIVE_CONFLICT)
            store.countFailure(id) < MAX_ATTEMPTS -> BackupOutcome.Retry
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
     * Removes every Drive file of [id] – media first, then the sidecar – and forgets the Drive copy; phone and recorder
     * copies stay. Once the media files are gone the item is no longer backed up (and excluded from the automatic
     * rules); a sidecar that could not be deleted is marked [BackupErrors.SIDECAR_LEFT] so the user can try again.
     */
    suspend fun deleteOnDrive(id: String): Result<Unit> = driveCall {
        val email = accountOf(auth.state.value) ?: throw DriveError.NotConnected()
        adoptAccount(email)
        val item = repository.get(id) ?: return@driveCall
        check(item.backupState !in BackupRules.PENDING) { "upload pending" } // offered for DONE / conflict / left sidecar only
        // Only files listed in the current account (delete() reports a missing file as success).
        val (media, sidecars) = api.list(byIdQuery(id)).getOrThrow().partition { it.role == DriveFormat.ROLE_MEDIA }
        (media.map { it.id } + listOfNotNull(item.driveFileId)).distinct().forEach { api.delete(it).getOrThrow() }
        forget(id)
        try {
            sidecars.forEach { api.delete(it.id).getOrThrow() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            repository.update(id) { it.copy(backupError = BackupErrors.SIDECAR_LEFT) }
            throw e
        }
    }

    /**
     * "Drive-Status prüfen": DONE items whose media file or sidecar is no longer in Drive are not backed up any more
     * (state NONE, excluded from the automatic rules). Items done within [LISTING_LAG_MS] are skipped (Drive's listing
     * can lag behind). Never deletes anything. Returns how many were missing.
     */
    suspend fun reconcile(): Result<Int> = driveCall {
        val email = accountOf(auth.state.value) ?: throw DriveError.NotConnected()
        adoptAccount(email)
        val now = System.currentTimeMillis()
        // Taken before listing, so an upload finishing meanwhile is not judged by the older listing.
        val done = repository.observe().first().filter { it.backupState == BackupState.DONE && now - store.doneAt(it.id) >= LISTING_LAG_MS }
        val complete = DriveFormatReader.scan(api).getOrThrow().filter { it.complete }.map { it.mediaId }.toSet()
        val missing = done.filter { it.id !in complete }
        missing.forEach { forget(it.id) }
        missing.size
    }

    /** Drive copy gone: excluded from the automatic rules (before the state changes), session dropped, row updated. */
    private suspend fun forget(id: String) {
        store.exclude(id)
        store.clearSession(id)
        store.setDoneAt(id, null)
        repository.markDriveDeleted(id)
    }

    /**
     * Account switch (CONTRACTS §10): when [email] differs from the account the states belong to, every Drive field
     * and state is reset and everything stored per item (sessions, unverified uploads, exclusions, …) is dropped.
     * Returns true when it reset.
     */
    suspend fun adoptAccount(email: String): Boolean = accountLock.withLock {
        val account = keyOf(email)
        val previous = store.account
        if (previous == account) return@withLock false
        if (previous != null) {
            store.forgetAccountState()
            repository.observe().first()
                .filter { it.driveFileId != null || it.driveMd5 != null || it.backupState != BackupState.NONE || it.backupError != null }
                .forEach { repository.markDriveDeleted(it.id) }
        }
        store.account = account
        previous != null
    }

    /**
     * Runs [block] only while the backup states belong to [email], never during an account switch's reset: a write of
     * the Drive import lands before the reset (and is reset with everything else) or is skipped (null).
     */
    suspend fun <T> whileAccount(email: String, block: suspend () -> T): T? = accountLock.withLock {
        if (store.account == keyOf(email)) block() else null
    }

    private fun notConnectedReason() =
        if (auth.state.value is DriveAuthState.NeedsReconnect) PauseReason.RECONNECT else PauseReason.NOT_CONNECTED

    companion object {
        private const val TAG = "DriveBackup"

        /** Real failures per item before it is FAILED (offline waits are not counted). */
        const val MAX_ATTEMPTS = 5

        /** A sidecar-less file of other content that is not recorded as ours is deleted only after this. */
        const val UNVERIFIED_GRACE_MS = 24 * 60 * 60 * 1000L

        /** "Drive-Status prüfen" does not judge items that became DONE more recently. */
        const val LISTING_LAG_MS = 5 * 60 * 1000L

        fun accountOf(state: DriveAuthState): String? = (state as? DriveAuthState.Connected)?.accountEmail?.lowercase()

        /** Every Drive file (media, sidecar) of one item. */
        fun byIdQuery(id: String) = "appProperties has { key='${DriveFormat.KEY_ID}' and value='${DriveFormat.escape(id)}' } and trashed = false"

        private val DriveFile.role: String? get() = appProperties[DriveFormat.KEY_ROLE]

        /** An unknown or unreadable creation time counts as recent. */
        private fun DriveFile.olderThan(ms: Long): Boolean =
            createdTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { System.currentTimeMillis() - it > ms } ?: false

        private fun keyOf(email: String) =
            MessageDigest.getInstance("SHA-256").digest(email.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

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
