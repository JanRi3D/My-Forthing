package me.ri3d.dashcam.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.R
import me.ri3d.dashcam.backup.BackupRules
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.drive.DriveApi
import me.ri3d.dashcam.drive.DriveError
import me.ri3d.dashcam.drive.format.DriveFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Vom Drive laden": the Drive copy of an item to the phone, where a recorder download would put it
 * ([MediaDownloader.targetFile]), checked against `driveMd5` before it becomes the phone copy. WorkManager unique work
 * per item under the backup's network conditions ([BackupRules.constraints], re-applied when they change), surviving
 * process death; one transfer at a time ([slot]), independent of the recorder's download slot. The HTTP side (Range
 * resume of `<name>.drive.part`, token refresh, backoff) is [DriveApi.download] on Drive's own client.
 */
@Singleton
class DriveDownloadQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MediaRepository,
    private val downloader: MediaDownloader,
    private val api: DriveApi,
    private val preferences: PreferencesRepository,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // ponytail: a work waiting for the slot holds a WorkManager slot (and may be stopped and rerun); fine for a few.
    /** Held by the transferring worker; the others wait for it, shown as queued. */
    internal val slot = Mutex()

    val progress: StateFlow<Map<String, TransferProgress>> = workManager.getWorkInfosByTagFlow(TAG)
        .map { infos ->
            infos.mapNotNull(::toProgress).groupBy { it.mediaId }
                .mapValues { (_, all) -> all.firstOrNull { it.state in DownloadQueue.ACTIVE } ?: all.last() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        // Changed network conditions apply to waiting and running downloads too (a running one resumes its part). Only on
        // a change, like BackupQueue: WorkManager's stored network request does not compare equal to a new one. Works of
        // an earlier process keep their constraints; the worker checks the current conditions anyway.
        scope.launch {
            preferences.preferences.map { BackupRules.constraints(it) }.distinctUntilChanged().drop(1).collect { constraints ->
                mutex.withLock {
                    workManager.getWorkInfosByTagFlow(TAG).first().filter { !it.state.isFinished }.forEach { info ->
                        info.tags.firstOrNull { it.startsWith(DownloadQueue.ID_TAG) }?.removePrefix(DownloadQueue.ID_TAG)
                            ?.let { repository.get(it) }?.let { enqueue(it, constraints, ExistingWorkPolicy.REPLACE) }
                    }
                }
            }
        }
    }

    /**
     * False when nothing was queued: unknown item, no Drive copy, already on the phone, or a download of it from the
     * recorder is pending (both would write the same file).
     */
    suspend fun enqueue(mediaId: String): Boolean = mutex.withLock {
        val item = repository.get(mediaId) ?: return@withLock false
        if (item.localFile?.isFile == true || item.driveFileId == null || pending(DownloadQueue.workName(mediaId))) return@withLock false
        enqueue(item, BackupRules.constraints(preferences.preferences.first()), ExistingWorkPolicy.KEEP)
        true
    }

    private suspend fun enqueue(item: MediaItem, constraints: Constraints, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DriveDownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to item.id, DownloadWorker.KEY_NAME to item.originalFileName))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG).addTag(DownloadQueue.ID_TAG + item.id).addTag(DownloadQueue.NAME_TAG + item.originalFileName)
            .build()
        workManager.enqueueUniqueWork(workName(item.id), policy, request).await()
    }

    /** The unique work [name] is queued, running or waiting. */
    internal suspend fun pending(name: String): Boolean = workManager.getWorkInfosForUniqueWorkFlow(name).first().any { !it.state.isFinished }

    /** The default network fits the backup's conditions right now (checked by the worker before it transfers). */
    suspend fun networkFits(): Boolean = BackupRules.defaultNetworkFits(context, preferences.preferences.first())

    /** Stops the download and drops its partial file. */
    suspend fun cancel(mediaId: String) {
        workManager.cancelUniqueWork(workName(mediaId)).await()
        discardPartial(mediaId)
    }

    /**
     * Downloads, verifies and registers the phone copy of [mediaId]. Throws [DownloadException] (permanent) or a
     * [DriveError] ([DriveError.Offline]: the part stays for the resume).
     */
    suspend fun transfer(mediaId: String, onProgress: (Long, Long?) -> Unit = { _, _ -> }): MediaItem {
        val item = repository.get(mediaId) ?: throw DownloadException(DownloadFailure.UNKNOWN_ITEM, permanent = true)
        if (item.localFile?.isFile == true) return item
        val fileId = item.driveFileId ?: throw DownloadException(DownloadFailure.NOT_ON_DRIVE, permanent = true)
        val target = downloader.targetFile(item)
        val staged = staged(target)
        // A transfer that completed before the process died is checked without downloading it again.
        if (!staged.isFile) api.download(fileId, staged, onProgress).getOrThrow()
        val md5 = withContext(Dispatchers.IO) { DriveFormat.md5Hex(staged) }
        if (item.driveMd5 != null && !md5.equals(item.driveMd5, ignoreCase = true)) {
            staged.delete() // never kept, never resumed
            throw DownloadException(DownloadFailure.CHECKSUM, permanent = true)
        }
        withContext(Dispatchers.IO) { Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        return repository.markDownloaded(mediaId, target) ?: run {
            target.delete() // the item was deleted meanwhile: keep no orphan file
            throw DownloadException(DownloadFailure.UNKNOWN_ITEM, permanent = true)
        }
    }

    suspend fun discardPartial(mediaId: String) {
        val item = repository.get(mediaId) ?: return
        val staged = staged(downloader.targetFile(item))
        withContext(Dispatchers.IO) {
            staged.delete()
            MediaDownloader.partFile(staged).delete()
        }
    }

    companion object {
        const val TAG = "drive-download"
        internal const val KEY_WAITING = "waiting"
        private const val BACKOFF_SECONDS = 30L

        fun workName(mediaId: String) = "drive-download-$mediaId"

        /** `<name>.drive` (and its `.drive.part`) next to the target: never mixed up with a recorder download's part. */
        fun staged(target: File) = File(target.path + ".drive")

        /** Enqueued = waiting for the network conditions (or the retry after a network that did not fit). */
        internal fun toProgress(info: WorkInfo): TransferProgress? = DownloadQueue.toProgress(info)?.let {
            val forSlot = it.state == TransferState.RUNNING && info.progress.getBoolean(KEY_WAITING, false)
            val state = when {
                forSlot -> TransferState.QUEUED
                info.state == WorkInfo.State.ENQUEUED -> TransferState.WAITING
                else -> it.state
            }
            it.copy(state = state, fromDrive = true)
        }
    }
}

/** Runs one Drive download in the foreground (data sync) with the transfers notification; one at a time. */
@HiltWorker
class DriveDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: DriveDownloadQueue,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(DownloadWorker.KEY_ID) ?: return Result.failure()
        val name = inputData.getString(DownloadWorker.KEY_NAME).orEmpty()
        // JobScheduler decided on the network a moment ago; the default network may no longer fit the conditions.
        if (!queue.networkFits()) return Result.retry()
        setProgress(workDataOf(DriveDownloadQueue.KEY_WAITING to true))
        return queue.slot.withLock {
            if (!queue.networkFits()) return@withLock Result.retry() // changed while waiting for the slot
            // Not allowed from the background on Android 12+: then it runs as normal work, without a notification.
            val foreground = runCatching { setForeground(foregroundInfo(name)) }.isSuccess
            setProgress(workDataOf(DownloadWorker.KEY_BYTES to 0L)) // no longer waiting
            try {
                var last = 0L
                queue.transfer(mediaId) { bytes, total ->
                    val now = System.currentTimeMillis()
                    if (now - last >= DownloadWorker.PROGRESS_INTERVAL_MS || bytes == total) {
                        last = now
                        setProgressAsync(workDataOf(DownloadWorker.KEY_BYTES to bytes, DownloadWorker.KEY_TOTAL to (total ?: -1L)))
                        if (foreground) TransferNotifications.update(applicationContext, id, title(name), bytes, total)
                    }
                }
                Result.success()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { if (cancelledByUser(mediaId)) queue.discardPartial(mediaId) }
                throw e
            } catch (e: DriveError.Offline) {
                Result.retry() // the part stays; the next run resumes it
            } catch (e: Exception) {
                Result.failure(failure(e))
            } finally {
                TransferNotifications.cancel(applicationContext, id)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(inputData.getString(DownloadWorker.KEY_NAME).orEmpty())

    private fun foregroundInfo(name: String) = TransferNotifications.foregroundInfo(applicationContext, id, title(name), 0, null)

    private fun title(name: String) = applicationContext.getString(R.string.media_drive_download_notification_title, name)

    /** Cancelled by the user (sheet, notification): the work itself reads CANCELLED. */
    private suspend fun cancelledByUser(mediaId: String): Boolean {
        if (!isStopped) return false
        val works = WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWorkFlow(DriveDownloadQueue.workName(mediaId)).first()
        // A REPLACE successor (changed network conditions) resumes the part.
        return works.any { it.id == id && it.state == WorkInfo.State.CANCELLED } && works.none { !it.state.isFinished }
    }

    private fun failure(e: Exception): Data {
        val (failure, code) = when (e) {
            is DownloadException -> e.failure to e.httpCode
            is DriveError.Http -> (if (e.code == 404) DownloadFailure.NOT_ON_DRIVE else DownloadFailure.DRIVE) to e.code
            is DriveError.NotConnected, is DriveError.NeedsReconnect -> DownloadFailure.DRIVE_NOT_CONNECTED to null
            else -> DownloadFailure.DRIVE to null
        }
        val detail = when (e) {
            is DriveError.Http -> e.reason
            is DownloadException -> null
            else -> e.javaClass.simpleName
        }
        return workDataOf(DownloadWorker.KEY_ERROR to failure.name, DownloadWorker.KEY_HTTP to (code ?: -1), DownloadWorker.KEY_DETAIL to detail)
    }
}
