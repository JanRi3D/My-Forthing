package to.axolotl.cam.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import to.axolotl.cam.R
import to.axolotl.cam.dashcam.RecorderConnectionManager
import to.axolotl.cam.dashcam.RecorderConnectionState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A download that cannot finish now; [permanent] = retrying will not help. */
class DownloadException(message: String, val permanent: Boolean) : IOException(message)

/** One recorder file → `files/media/<id>/<name>`. */
@Singleton
class MediaDownloader @Inject constructor(private val repository: MediaRepository, private val http: RecorderHttp) {

    /**
     * Bytes go to `<name>.part`. If a part exists the request asks for the rest (`Range: bytes=<n>-`) and appends only
     * when the recorder answers 206 with a Content-Range starting at n (`If-Range` is not assumed); a 200 rewrites
     * the part from the start. When complete (length checked if the recorder sent one): fsync, atomic rename, row
     * updated. A file already on the phone is returned unchanged: no second copy, no new item.
     */
    suspend fun download(mediaId: String, onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> }): MediaItem {
        val item = repository.get(mediaId) ?: throw DownloadException("unknown item", permanent = true)
        if (item.localFile?.isFile == true) return item
        val path = item.recorderPath ?: throw DownloadException("not on the recorder", permanent = true)
        val target = targetFile(item)
        val part = partFile(target)
        target.parentFile?.mkdirs()
        val offset = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(http.url(path)).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val call = http.client().newCall(request)
        coroutineScope {
            // A blocked read does not see coroutine cancellation; cancelling the call ends it at once.
            val watchdog = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        val code = response.code
                        val range = response.header("Content-Range")
                        val append = code == 206 && offset > 0 && rangeStart(range) == offset
                        when {
                            code == 416 || code == 206 && !append -> {
                                part.delete() // the part does not fit what the recorder offers: start over
                                throw DownloadException("HTTP $code for bytes=$offset-", permanent = false)
                            }
                            code != 200 && code != 206 -> throw DownloadException("HTTP $code", permanent = code in 400..499 && code != 408 && code != 429)
                        }
                        val total = if (append) rangeTotal(range) else response.body.contentLength().takeIf { it >= 0 }
                        var done = if (append) offset else 0L
                        FileOutputStream(part, append).use { out ->
                            val source = response.body.byteStream()
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                ensureActive()
                                val n = source.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                            out.fd.sync()
                        }
                        if (total != null && done != total) throw DownloadException("incomplete: $done of $total", permanent = false)
                    }
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            } finally {
                watchdog.cancel()
            }
        }
        return repository.markDownloaded(mediaId, target) ?: run {
            target.delete() // the item was deleted meanwhile: keep no orphan file
            throw DownloadException("item deleted", permanent = true)
        }
    }

    /** User cancel: the partial file is not kept for a resume. */
    suspend fun discardPartial(mediaId: String) {
        val item = repository.get(mediaId) ?: return
        val target = targetFile(item)
        withContext(Dispatchers.IO) {
            partFile(target).delete()
            if (!target.exists()) target.parentFile?.delete()
        }
    }

    fun targetFile(item: MediaItem) = File(File(repository.mediaDir, item.id), safeName(item.originalFileName, item.id))

    companion object {
        private const val BUFFER = 64 * 1024
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        fun partFile(target: File) = File(target.path + ".part")

        /** The recorder's name, reduced to safe characters (it is also the name others see when sharing). */
        fun safeName(name: String, fallback: String) = name.replace(UNSAFE, "_").trim('.').ifBlank { fallback }

        /** `bytes <start>-<end>/<total>` → start. */
        fun rangeStart(contentRange: String?): Long? = contentRange?.let { Regex("""bytes\s+(\d+)-\d+/""").find(it) }?.groupValues?.get(1)?.toLongOrNull()

        /** `bytes <start>-<end>/<total>` → total; null for `*`. */
        fun rangeTotal(contentRange: String?): Long? = contentRange?.substringAfterLast('/', "")?.toLongOrNull()
    }
}

enum class TransferState { QUEUED, RUNNING, WAITING, DONE, FAILED, CANCELLED }

/** One download as WorkManager reports it. [totalBytes] is null while unknown. */
data class TransferProgress(
    val mediaId: String,
    val name: String,
    val state: TransferState,
    val bytes: Long,
    val totalBytes: Long?,
    val error: String?,
)

/**
 * Durable download queue: WorkManager unique work per media id (KEEP), no network constraint (the recorder Wi-Fi
 * has no internet), linear backoff. Runs survive process death; WorkManager re-enqueues them, and a work waiting for
 * its next attempt starts again as soon as a session is Ready.
 */
@Singleton
class DownloadQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: MediaRepository,
    private val downloader: MediaDownloader,
    manager: RecorderConnectionManager,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val progress: StateFlow<Map<String, TransferProgress>> = workManager.getWorkInfosByTagFlow(TAG)
        .map { infos ->
            // A replaced work may still be listed next to its successor: the unfinished one wins.
            infos.mapNotNull(::toProgress).groupBy { it.mediaId }
                .mapValues { (_, all) -> all.firstOrNull { it.state in ACTIVE } ?: all.last() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            manager.state.collect { if (it is RecorderConnectionState.Ready) resumeWaiting() }
        }
    }

    /** False when nothing was queued: unknown item, not on the recorder, or already on the phone (no duplicate). */
    suspend fun enqueue(mediaId: String): Boolean {
        val item = repository.get(mediaId) ?: return false
        if (item.localFile?.isFile == true || item.recorderPath == null) return false
        enqueue(item, ExistingWorkPolicy.KEEP)
        return true
    }

    /** Stops the download and drops its partial file. */
    suspend fun cancel(mediaId: String) {
        workManager.cancelUniqueWork(workName(mediaId))
        downloader.discardPartial(mediaId)
    }

    private fun enqueue(item: MediaItem, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to item.id, DownloadWorker.KEY_NAME to item.originalFileName))
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG).addTag(ID_TAG + item.id).addTag(NAME_TAG + item.originalFileName)
            .build()
        workManager.enqueueUniqueWork(workName(item.id), policy, request)
    }

    /** Waiting for the next attempt (e.g. no recorder Wi-Fi after a restart): try now. Running work is left alone. */
    private suspend fun resumeWaiting() {
        progress.value.values.filter { it.state == TransferState.WAITING }.forEach { p ->
            repository.get(p.mediaId)?.let { enqueue(it, ExistingWorkPolicy.REPLACE) }
        }
    }

    companion object {
        const val TAG = "media-download"
        private const val ID_TAG = "media-id:"
        private const val NAME_TAG = "media-name:"
        private const val BACKOFF_SECONDS = 15L
        val ACTIVE = setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.WAITING)

        fun workName(mediaId: String) = "media-download-$mediaId"

        internal fun toProgress(info: WorkInfo): TransferProgress? {
            val id = info.tags.firstOrNull { it.startsWith(ID_TAG) }?.removePrefix(ID_TAG) ?: return null
            val state = when (info.state) {
                WorkInfo.State.ENQUEUED -> if (info.runAttemptCount > 0) TransferState.WAITING else TransferState.QUEUED
                WorkInfo.State.BLOCKED -> TransferState.QUEUED
                WorkInfo.State.RUNNING -> TransferState.RUNNING
                WorkInfo.State.SUCCEEDED -> TransferState.DONE
                WorkInfo.State.FAILED -> TransferState.FAILED
                WorkInfo.State.CANCELLED -> TransferState.CANCELLED
            }
            val total = info.progress.getLong(DownloadWorker.KEY_TOTAL, -1).takeIf { it >= 0 }
            return TransferProgress(
                mediaId = id,
                name = info.tags.firstOrNull { it.startsWith(NAME_TAG) }?.removePrefix(NAME_TAG).orEmpty(),
                state = state,
                bytes = info.progress.getLong(DownloadWorker.KEY_BYTES, 0),
                totalBytes = total,
                error = info.outputData.getString(DownloadWorker.KEY_ERROR),
            )
        }
    }
}

/**
 * Runs one download in the foreground (data sync) with a German progress notification and a cancel action. At most
 * [MAX_PARALLEL] transfers run at once; the recorder serves them over one Wi-Fi link.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: MediaDownloader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(KEY_ID) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME).orEmpty()
        return try {
            transfers.withPermit {
                // Not allowed from the background on Android 12+: then it runs as normal work.
                runCatching { setForeground(foregroundInfo(name, 0, null)) }
                var last = 0L
                downloader.download(mediaId) { bytes, total ->
                    val now = System.currentTimeMillis()
                    if (now - last >= PROGRESS_INTERVAL_MS || bytes == total) {
                        last = now
                        setProgress(workDataOf(KEY_BYTES to bytes, KEY_TOTAL to (total ?: -1L)))
                        updateNotification(name, bytes, total)
                    }
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) withContext(NonCancellable) { downloader.discardPartial(mediaId) }
            throw e
        } catch (e: IOException) { // includes RecorderNotBoundException: no recorder Wi-Fi yet
            if (e is DownloadException && e.permanent || runAttemptCount + 1 >= MAX_ATTEMPTS) {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
            } else {
                Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(inputData.getString(KEY_NAME).orEmpty(), 0, null)

    private val notificationId get() = id.hashCode()

    /** Without the notification permission (Android 13+) the transfer runs silently; the in-app sheet shows it. */
    private fun updateNotification(name: String, bytes: Long, total: Long?) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) notifications().notify(notificationId, notification(name, bytes, total))
    }

    private fun notifications() = NotificationManagerCompat.from(applicationContext).apply {
        createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.media_transfers_channel))
                .build(),
        )
    }

    private fun notification(name: String, bytes: Long, total: Long?) = NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(R.drawable.ic_media_download)
        .setContentTitle(applicationContext.getString(R.string.media_download_notification_title, name))
        .setContentText(transferText(applicationContext, bytes, total))
        .setProgress(100, total?.takeIf { it > 0 }?.let { (bytes * 100 / it).toInt() } ?: 0, total == null || total <= 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .addAction(0, applicationContext.getString(R.string.action_cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        .build()

    private fun foregroundInfo(name: String, bytes: Long, total: Long?): ForegroundInfo {
        notifications()
        val notification = notification(name, bytes, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_ID = "mediaId"
        const val KEY_NAME = "name"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "media_transfers"
        private const val MAX_PARALLEL = 2
        private const val MAX_ATTEMPTS = 10
        private const val PROGRESS_INTERVAL_MS = 500L
        private val transfers = Semaphore(MAX_PARALLEL)
    }
}

/** "12,3 MB von 86 MB" / "12,3 MB" (sizes of the phone's download, formatted by Android). */
fun transferText(context: Context, bytes: Long, total: Long?): String {
    val done = android.text.format.Formatter.formatShortFileSize(context, bytes)
    return if (total == null || total <= 0) done
    else context.getString(R.string.media_transfer_of, done, android.text.format.Formatter.formatShortFileSize(context, total))
}
