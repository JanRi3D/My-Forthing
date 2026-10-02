package me.ri3d.dashcam.backup

import android.content.Context
import androidx.work.WorkManager
import coil3.memory.MemoryCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.core.log.Log
import me.ri3d.dashcam.drive.DriveApi
import me.ri3d.dashcam.drive.DriveAuth
import me.ri3d.dashcam.drive.DriveAuthState
import me.ri3d.dashcam.drive.DriveError
import me.ri3d.dashcam.drive.DriveRestApi
import me.ri3d.dashcam.drive.format.DriveBackupEntry
import me.ri3d.dashcam.drive.format.DriveFormat
import me.ri3d.dashcam.drive.format.DriveFormatReader
import me.ri3d.dashcam.drive.format.DriveSidecar
import me.ri3d.dashcam.media.BackupState
import me.ri3d.dashcam.enhance.ui.UpscaleJobs
import me.ri3d.dashcam.media.DownloadQueue
import me.ri3d.dashcam.media.DriveDownloadQueue
import me.ri3d.dashcam.media.DriveImport
import me.ri3d.dashcam.media.MediaCategory
import me.ri3d.dashcam.media.MediaItem
import me.ri3d.dashcam.media.MediaKind
import me.ri3d.dashcam.media.MediaRepository
import me.ri3d.dashcam.media.RecorderHttp
import me.ri3d.dashcam.media.RecorderThumb
import me.ri3d.dashcam.plates.ui.ClipScans
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One import from Drive: [added] new rows, [adopted] rows that took over their Drive copy, [merged] rows of the same
 * recordings under other ids that gave way to their Drive row, [unreadable] unusable sidecars.
 */
data class ImportReport(val added: Int = 0, val adopted: Int = 0, val merged: Int = 0, val unreadable: Int = 0) {
    /** Backups the library knows now that it did not before. */
    val imported: Int get() = added + adopted
}

/**
 * Drive restore (docs/features/drive-restore.md): imports every complete backup of the connected Drive account into
 * the library (Drive tab, "Vom Drive laden"), keeps the app account's hint of the Drive account (`driveAccount`, synced
 * by ProfileSync) and reconnects that account silently on a fresh install. Never writes to Drive.
 */
@Singleton
class DriveRestore @Inject constructor(
    private val api: DriveApi,
    private val auth: DriveAuth,
    private val repository: MediaRepository,
    private val backup: DriveBackup,
    private val store: BackupStore,
    private val preferences: PreferencesRepository,
    private val clipScans: ClipScans,
    private val http: RecorderHttp,
    @ApplicationContext private val context: Context,
) {
    internal var clock: () -> Long = System::currentTimeMillis
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running: Deferred<Result<ImportReport>>? = null
    private val _importing = MutableStateFlow(false)
    private val attempted = ConcurrentHashMap.newKeySet<String>()
    private val lastAutomatic = ConcurrentHashMap<String, Long>() // account → time of the last automatic attempt

    // ponytail: in memory; a phone copy that differs from its Drive copy is hashed again after a restart.
    private val differs = ConcurrentHashMap.newKeySet<String>()

    // ponytail: in memory; the links expire within hours. After a restart only cached thumbnails show until an import.
    private val thumbnailLinks = ConcurrentHashMap<String, String>()

    init {
        backup.onAccountReset(::forgetImages)
    }

    /** Account switch: the previous account's thumbnail links and its cached thumbnails and photos go. */
    private fun forgetImages(fileIds: List<String>) {
        thumbnailLinks.clear()
        val loader = http.imageLoader
        scope.launch(Dispatchers.IO) {
            fileIds.forEach { id ->
                loader.diskCache?.remove(thumbKey(id))
                loader.memoryCache?.remove(MemoryCache.Key(thumbKey(id)))
                loader.memoryCache?.remove(MemoryCache.Key(photoKey(id)))
            }
        }
    }

    /** An import runs (Drive tab progress). */
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** When the last import ended ("Stand" of the Drive tab). */
    val lastImport: StateFlow<Long?> = store.lastImport

    /** An import was attempted in this process (thumbnail links known when it succeeded). */
    val importedThisProcess: Boolean get() = attempted.isNotEmpty()

    /** App-scoped ([BackupQueue.start]): the Drive-account hint and the silent reconnect follow the Drive state. */
    fun start() {
        scope.launch { followDriveAccount() }
        scope.launch { reconnectFromHint() }
    }

    /**
     * Imports every complete backup (media + sidecar, oldest media file per id) of the connected account that the
     * library does not know as Drive copy yet. One import at a time: a call during a running one gets its result.
     * Fails with [DriveError.NotConnected] / [DriveError.NeedsReconnect] without touching anything.
     */
    suspend fun importFromDrive(): Result<ImportReport> {
        val job = synchronized(this) { running?.takeIf { it.isActive } ?: scope.async { runImport() }.also { running = it } }
        return job.await()
    }

    /**
     * The automatic import for [email] (BackupQueue's observer and "Jetzt prüfen", before the automatic rules queue
     * anything) while this account was never imported (fresh install, account switch, first start with this version).
     * A failed attempt is repeated at the next call after [AUTOMATIC_RETRY_MS]. True once the account is imported.
     */
    suspend fun importOnce(email: String): Boolean {
        running?.join() // may belong to the previous account
        if (store.lastImport.value != null) return true
        val now = clock()
        val last = lastAutomatic[email.lowercase()]
        if (last != null && now - last < AUTOMATIC_RETRY_MS) return false
        lastAutomatic[email.lowercase()] = now
        importFromDrive()
        return store.lastImport.value != null
    }

    /** Drive thumbnail of [item]: from Drive while this process knows its link, else from the disk cache only. */
    fun thumbnail(item: MediaItem): RecorderThumb? {
        val fileId = item.driveFileId ?: return null
        val link = thumbnailLinks[fileId]
        // Without a link the content URL only carries the request: with the network off Coil answers from the cache.
        return RecorderThumb(thumbKey(fileId), link ?: DriveRestApi.contentUrl(fileId), network = link != null)
    }

    private suspend fun runImport(): Result<ImportReport> {
        _importing.value = true
        return try {
            Result.success(importNow())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import from Drive stopped: ${e.javaClass.simpleName}")
            Result.failure(e)
        } finally {
            _importing.value = false
        }
    }

    private suspend fun importNow(): ImportReport {
        val state = auth.state.value
        val email = DriveBackup.accountOf(state) ?: throw if (state is DriveAuthState.NeedsReconnect) DriveError.NeedsReconnect() else DriveError.NotConnected()
        attempted += email
        backup.adoptAccount(email)
        val entries = DriveFormatReader.scan(api).getOrThrow().filter { it.complete }
        entries.forEach { entry -> entry.media?.let { media -> media.thumbnailLink?.let { thumbnailLinks[media.id] = it } } }
        val todo = entries.filter { repository.get(it.mediaId)?.driveFileId == null }
        val counts = MediaImportCounts()
        val permits = Semaphore(SIDECAR_PARALLEL)
        coroutineScope {
            todo.forEach { entry ->
                launch {
                    // A sidecar that cannot be fetched (offline, 5xx after retries) ends the import; what is in stays.
                    val json = permits.withPermit { api.readJson(entry.sidecar!!.id).getOrThrow() }
                    val row = driveRow(entry, json, System.currentTimeMillis())
                    if (row == null) counts.unreadable.incrementAndGet() else counts.count(backup.whileAccount(email) { repository.importDriveCopy(row) })
                }
            }
        }
        // The same recordings under other ids (listed or downloaded before Drive was connected, or refused earlier).
        repository.unmergedTwins().forEach { (twin, driveId) -> if (merge(email, driveId, twin)) counts.merged.incrementAndGet() }
        backup.whileAccount(email) { store.setLastImport(clock()) }
        return counts.report().also { Log.d(TAG, "import from Drive: $it of ${entries.size} complete entries") }
    }

    /**
     * Replaces [twin] by the Drive row [driveId] of the same recording (the Drive id wins, so the backup never uploads it
     * a second time; derived items and plate sightings move along) when its phone copy, if any, has the Drive copy's
     * content. While the twin is in use (uploading, downloading from the recorder, checked for plates, upscaled) it is
     * excluded from the automatic backup instead, and the next import merges it.
     */
    private suspend fun merge(email: String, driveId: String, twin: MediaItem): Boolean {
        val drive = repository.get(driveId) ?: return false
        if (twin.localUri != null && drive.localUri != null || twin.id in differs) return false // two phone copies: both stay
        if (inUse(twin) || twin.localUri != null && pending(DriveDownloadQueue.workName(driveId))) {
            store.exclude(twin.id)
            return false
        }
        if (twin.localUri != null) {
            val file = twin.localFile?.takeIf { it.isFile } ?: return false
            if (!withContext(Dispatchers.IO) { DriveFormat.md5Hex(file) }.equals(drive.driveMd5, ignoreCase = true)) {
                differs += twin.id // another recording, or a damaged copy: it stays a recording of its own
                return false
            }
        }
        val merged = backup.whileAccount(email) { repository.mergeIntoDriveCopy(driveId, twin) } == true
        if (merged) store.forgetItem(twin.id)
        return merged
    }

    /** Something works on [twin] under its id right now (a merge would pull the row away from it). */
    private suspend fun inUse(twin: MediaItem): Boolean =
        twin.backupState == BackupState.UPLOADING ||
            clipScans.state.value.let { twin.id == it.running || twin.id in it.queued } ||
            pending(DownloadQueue.workName(twin.id)) ||
            WorkManager.getInstance(context).getWorkInfosByTagFlow(UpscaleJobs.idTag(twin.id)).first().any { !it.state.isFinished }

    /** The unique work [name] is queued, running or waiting (asked from WorkManager, also before its queue exists). */
    private suspend fun pending(name: String): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name).first().any { !it.state.isFinished }

    /**
     * Keeps the app account's hint ([me.ri3d.dashcam.core.model.AppPreferences.driveAccount]): the connected account's
     * e-mail, "" once the user disconnected (only "Trennen" leads from a connection back to NotConnected).
     */
    internal suspend fun followDriveAccount() {
        var previous: DriveAuthState? = null
        auth.state.collect { state ->
            val email = (state as? DriveAuthState.Connected)?.accountEmail
            when {
                email != null -> {
                    store.driveDisconnected = false
                    if (preferences.preferences.first().driveAccount != email) preferences.update { it.copy(driveAccount = email) }
                }
                state is DriveAuthState.NotConnected && previous != null && previous !is DriveAuthState.NotConnected -> {
                    store.driveDisconnected = true
                    preferences.update { it.copy(driveAccount = "") }
                }
            }
            previous = state
        }
    }

    /**
     * While nothing is connected, the hint's account is reconnected silently, once per value and process, never after
     * the user disconnected on this phone. Google UI is never launched from here ([DriveAuth.reconnectSilently]).
     */
    internal suspend fun reconnectFromHint() {
        val tried = mutableSetOf<String>()
        combine(auth.state, preferences.preferences.map { it.driveAccount }.distinctUntilChanged()) { state, hint -> state to hint }
            .collect { (state, hint) ->
                if (state !is DriveAuthState.NotConnected || hint.isNullOrEmpty() || store.driveDisconnected || !tried.add(hint)) return@collect
                auth.reconnectSilently(hint).onFailure { Log.w(TAG, "silent Drive reconnect failed: ${it.javaClass.simpleName}") }
            }
    }

    private class MediaImportCounts {
        val added = AtomicInteger()
        val adopted = AtomicInteger()
        val merged = AtomicInteger()
        val unreadable = AtomicInteger()

        fun count(result: DriveImport?) {
            when (result) {
                DriveImport.ADDED -> added.incrementAndGet()
                DriveImport.ADOPTED -> adopted.incrementAndGet()
                DriveImport.KNOWN, null -> Unit // known meanwhile, or the account changed
            }
        }

        fun report() = ImportReport(added.get(), adopted.get(), merged.get(), unreadable.get())
    }

    companion object {
        private const val TAG = "DriveRestore"

        /** Sidecars fetched at the same time. */
        const val SIDECAR_PARALLEL = 4

        /** A failed automatic import is tried again at the next observer pass after this. */
        const val AUTOMATIC_RETRY_MS = 60_000L

        /** Disk and memory cache key of a Drive thumbnail: stable, the thumbnailLink expires. */
        fun thumbKey(fileId: String) = "drive-thumb:$fileId"

        /** Memory cache key of a Drive photo shown in the clip screen (never on disk). */
        fun photoKey(fileId: String) = "drive-file:$fileId"

        /**
         * The library row of a complete [entry] from its sidecar, or null when the sidecar is unusable: unparsable,
         * another id, not complete, an unknown kind, an md5 that differs from the media file, an id that is no UUID.
         * The recorder copy is unknown on this phone (recorderPath stays null); a later listing re-attaches it by type,
         * name and raw time. Plates are not restored.
         */
        internal fun driveRow(entry: DriveBackupEntry, sidecarJson: String, now: Long): MediaItem? {
            val media = entry.media ?: return null
            val sidecar = runCatching { DriveSidecar.fromJson(sidecarJson) }.getOrNull() ?: return null
            val kind = MediaKind.entries.firstOrNull { it.name == sidecar.kind } ?: return null
            val md5 = media.md5Checksum ?: sidecar.md5
            val uuid = runCatching { UUID.fromString(entry.mediaId).toString() == entry.mediaId.lowercase() }.getOrDefault(false)
            if (!uuid || sidecar.id != entry.mediaId || !sidecar.backup.complete || !md5.equals(sidecar.md5, ignoreCase = true)) return null
            return MediaItem(
                id = entry.mediaId,
                kind = kind,
                category = MediaCategory.entries.firstOrNull { it.name == sidecar.category } ?: MediaCategory.UNKNOWN,
                recorderType = sidecar.recorderType,
                recorderPath = null,
                recorderThumbPath = null,
                originalFileName = sidecar.originalFileName,
                recorderTime = sidecar.recorderTime,
                recorderTimeEpochGuess = MediaRepository.epochGuess(sidecar.recorderTime),
                localUri = null,
                localSizeBytes = null,
                localThumbPath = null,
                downloadedAt = sidecar.downloadedAt?.let(::epochOf),
                parentId = sidecar.parent?.id,
                parentPositionMs = sidecar.parent?.positionMs,
                driveFileId = media.id,
                backupState = BackupState.DONE,
                backupError = null,
                driveMd5 = md5.lowercase(),
                createdAt = media.createdTime?.let(::epochOf) ?: now,
            )
        }

        private fun epochOf(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
    }
}
