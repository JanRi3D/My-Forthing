package me.ri3d.dashcam.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.log.Log
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import me.ri3d.dashcam.dashcam.RecorderNotBoundException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Why a download stopped; shown in German, the raw HTTP code and detail as a second line. */
enum class DownloadFailure(@StringRes val text: Int) {
    UNKNOWN_ITEM(R.string.media_failure_unknown_item),
    NOT_ON_RECORDER(R.string.media_failure_not_on_recorder),
    HTTP(R.string.media_failure_http),
    NOT_MEDIA(R.string.media_failure_not_media),
    INCOMPLETE(R.string.media_failure_incomplete),
    NETWORK(R.string.media_failure_network),
}

/** A download that cannot finish now; [permanent] = retrying will not help. [contentType]: as the recorder sent it. */
class DownloadException(
    val failure: DownloadFailure,
    val permanent: Boolean,
    val httpCode: Int? = null,
    val contentType: String? = null,
) : IOException(failure.name + (httpCode?.let { " (HTTP $it)" } ?: "") + (contentType?.let { " Content-Type: $it" } ?: ""))

/** The raw second line of a failure: the Content-Type a download refused, or "Class: message" of any other error. */
fun failureDetail(e: IOException): String? =
    if (e is DownloadException) e.contentType?.let { "Content-Type: $it" } else "${e.javaClass.simpleName}: ${e.message}"

/** One recorder file → `files/media/<id>/<name>`. */
@Singleton
class MediaDownloader @Inject constructor(private val repository: MediaRepository, private val http: RecorderHttp) {

    /**
     * Only with a Ready session ([RecorderHttp.client]; otherwise [RecorderNotReadyException] before any request).
     * Bytes go to `<name>.part`, the size the recorder announced to `<name>.part.size`. With a part the request asks
     * for the rest (`Range: bytes=<n>-`) and appends only on a 206 whose Content-Range starts at n and announces the
     * same size (`If-Range` is not assumed); a 200 rewrites from the start; 416 or a non-fitting 206 restarts. An HTML
     * page (`text/html`, e.g. a captive portal) or an empty body is never saved; any other Content-Type, or none, is
     * accepted (the recorder's headers are unverified). Content-Length is optional: without it neither the size check
     * nor the `.part.size` consistency check applies. When complete: fsync, atomic rename, row updated. Failures are
     * noted for Diagnose. A file already on the phone is returned unchanged: no second copy, no new item.
     */
    suspend fun download(mediaId: String, onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> }): MediaItem {
        val item = repository.get(mediaId) ?: throw DownloadException(DownloadFailure.UNKNOWN_ITEM, permanent = true)
        if (item.localFile?.isFile == true) return item
        val path = item.recorderPath ?: throw DownloadException(DownloadFailure.NOT_ON_RECORDER, permanent = true)
        val client = http.client()
        val target = targetFile(item)
        val part = partFile(target)
        val sizeFile = sizeFile(part)
        target.parentFile?.mkdirs()
        val offset = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(http.url(path)).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val call = client.newCall(request)
        coroutineScope {
            // A blocked read does not see coroutine cancellation; cancelling the call ends it at once.
            val watchdog = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        val code = response.code
                        val range = response.header("Content-Range")
                        val append = code == 206 && offset > 0 && rangeStart(range) == offset
                        val length = response.body.contentLength().takeIf { it >= 0 }
                        val total = if (append) rangeTotal(range) ?: length?.let { offset + it } else length
                        val type = response.header("Content-Type")
                        val announced = sizeFile.takeIf { it.isFile }?.readText()?.toLongOrNull()
                        fun restart(): Nothing {
                            part.delete() // the part does not fit what the recorder offers now: start over
                            sizeFile.delete()
                            throw DownloadException(DownloadFailure.INCOMPLETE, permanent = false, httpCode = code)
                        }
                        when {
                            code == 416 || code == 206 && !append -> restart()
                            code != 200 && code != 206 -> throw DownloadException(
                                DownloadFailure.HTTP, permanent = code in 400..499 && code != 408 && code != 429, httpCode = code,
                            )
                            !acceptsContentType(type) ->
                                throw DownloadException(DownloadFailure.NOT_MEDIA, permanent = true, httpCode = code, contentType = type)
                            response.header("Content-Length") != null && response.body.contentLength() <= 0 ->
                                throw DownloadException(DownloadFailure.INCOMPLETE, permanent = false, httpCode = code)
                            // Consistency only where sizes are known (Content-Range / Content-Length are optional).
                            append && total != null && (total <= offset || announced != null && announced != total) -> restart()
                        }
                        if (!append) {
                            if (total != null) sizeFile.writeText(total.toString()) else sizeFile.delete()
                        }
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
                        if (total != null && done != total || done == 0L) {
                            throw DownloadException(DownloadFailure.INCOMPLETE, permanent = false, httpCode = code)
                        }
                    }
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    sizeFile.delete()
                }
            } catch (e: IOException) {
                http.note("download $path: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                watchdog.cancel()
            }
        }
        return repository.markDownloaded(mediaId, target) ?: run {
            target.delete() // the item was deleted meanwhile: keep no orphan file
            throw DownloadException(DownloadFailure.UNKNOWN_ITEM, permanent = true)
        }
    }

    /** User cancel: the partial file is not kept for a resume. */
    suspend fun discardPartial(mediaId: String) {
        val item = repository.get(mediaId) ?: return
        val target = targetFile(item)
        withContext(Dispatchers.IO) {
            partFile(target).delete()
            sizeFile(partFile(target)).delete()
            if (!target.exists()) target.parentFile?.delete()
        }
    }

    fun targetFile(item: MediaItem) = File(File(repository.mediaDir, item.id), safeName(item.originalFileName, item.id))

    companion object {
        private const val BUFFER = 64 * 1024
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        fun partFile(target: File) = File(target.path + ".part")
        private fun sizeFile(part: File) = File(part.path + ".size")

        /** The recorder's name, reduced to safe characters (it is also the name others see when sharing). */
        fun safeName(name: String, fallback: String) = name.replace(UNSAFE, "_").trim('.').ifBlank { fallback }

        /** `bytes <start>-<end>/<total>` → start. */
        fun rangeStart(contentRange: String?): Long? = contentRange?.let { Regex("""bytes\s+(\d+)-\d+/""").find(it) }?.groupValues?.get(1)?.toLongOrNull()

        /** `bytes <start>-<end>/<total>` → total; null for `*`. */
        fun rangeTotal(contentRange: String?): Long? = contentRange?.substringAfterLast('/', "")?.toLongOrNull()

        /**
         * Everything but an HTML page: the recorder's Content-Type for recordings is unverified (it may send none, a
         * generic or an odd one), while HTML means some other web server answered (captive portal, router).
         */
        fun acceptsContentType(contentType: String?): Boolean = contentType?.substringBefore(';')?.trim()?.lowercase() != "text/html"
    }
}

enum class TransferState { QUEUED, RUNNING, WAITING, DONE, FAILED, CANCELLED }

/**
 * One download as WorkManager reports it. [totalBytes] is null while unknown. [failure], [httpCode], [detail]
 * ([failureDetail]): why it failed, or for WAITING why the last attempt failed (null while it only waits for a session).
 */
data class TransferProgress(
    val mediaId: String,
    val name: String,
    val state: TransferState,
    val bytes: Long,
    val totalBytes: Long?,
    val failure: DownloadFailure?,
    val httpCode: Int?,
    val detail: String? = null,
)

/** The last real failure of a download waiting for its next attempt (WorkManager keeps no output for a retry). */
data class RetryReason(val failure: DownloadFailure, val httpCode: Int?, val detail: String?)

/**
 * Durable download queue: WorkManager unique work per media id (KEEP), no network constraint (the recorder Wi-Fi
 * has no internet), linear backoff. At most [MAX_PARALLEL] downloads are runnable; further ones are enqueued "held"
 * (far initial delay) and promoted when a slot frees, so no worker waits while running. Runs survive process death;
 * a work waiting for its next attempt starts again as soon as a session is Ready.
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
    private val mutex = Mutex()

    // ponytail: in memory, so after process death a waiting download shows no reason until its next attempt.
    private val retryReasons = MutableStateFlow<Map<String, RetryReason>>(emptyMap())

    val progress: StateFlow<Map<String, TransferProgress>> = combine(workManager.getWorkInfosByTagFlow(TAG), retryReasons) { infos, reasons ->
        // A replaced work may still be listed next to its successor: the unfinished one wins.
        infos.mapNotNull { toProgress(it, reasons) }.groupBy { it.mediaId }
            .mapValues { (_, all) -> all.firstOrNull { it.state in ACTIVE } ?: all.last() }
    }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Set by the worker when an attempt failed and another follows; null clears it. */
    fun retryReason(mediaId: String, reason: RetryReason?) = retryReasons.update { if (reason == null) it - mediaId else it + (mediaId to reason) }

    init {
        scope.launch { promote() }
        scope.launch {
            manager.state.collect { if (it is RecorderConnectionState.Ready) resumeWaiting() }
        }
    }

    /** False when nothing was queued: unknown item, not on the recorder, or already on the phone (no duplicate). */
    suspend fun enqueue(mediaId: String): Boolean {
        val item = repository.get(mediaId) ?: return false
        if (item.localFile?.isFile == true || item.recorderPath == null) return false
        mutex.withLock {
            val busy = infos().count { it.occupiesSlot() && idOf(it) != mediaId }
            enqueue(item, ExistingWorkPolicy.KEEP, held = busy >= MAX_PARALLEL)
        }
        return true
    }

    /** Stops the download and drops its partial file. */
    suspend fun cancel(mediaId: String) {
        workManager.cancelUniqueWork(workName(mediaId))
        retryReason(mediaId, null)
        downloader.discardPartial(mediaId)
        promote()
    }

    /** Fills free slots with held downloads. [excluding] = the calling worker, which is about to finish. */
    suspend fun promote(excluding: UUID? = null) = mutex.withLock {
        val infos = infos()
        val free = MAX_PARALLEL - infos.count { it.occupiesSlot() && it.id != excluding }
        // ponytail: WorkInfo has no enqueue time, so held downloads start in WorkManager's order, not strictly FIFO.
        infos.filter { HELD in it.tags && it.state == WorkInfo.State.ENQUEUED }.take(free.coerceAtLeast(0)).forEach { info ->
            idOf(info)?.let { repository.get(it) }?.let { enqueue(it, ExistingWorkPolicy.REPLACE, held = false) }
        }
    }

    private fun enqueue(item: MediaItem, policy: ExistingWorkPolicy, held: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to item.id, DownloadWorker.KEY_NAME to item.originalFileName))
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG).addTag(ID_TAG + item.id).addTag(NAME_TAG + item.originalFileName)
            .apply { if (held) addTag(HELD).setInitialDelay(HOLD_DAYS, TimeUnit.DAYS) }
            .build()
        workManager.enqueueUniqueWork(workName(item.id), policy, request)
    }

    /**
     * Waiting for the next attempt (e.g. no recorder Wi-Fi after a restart): try now. Only works still ENQUEUED when
     * checked under the lock are replaced; running ones are left alone.
     */
    private suspend fun resumeWaiting() = mutex.withLock {
        infos().filter { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 && HELD !in it.tags }.forEach { info ->
            val id = idOf(info) ?: return@forEach
            val current = workManager.getWorkInfosForUniqueWorkFlow(workName(id)).first()
            if (current.any { it.id == info.id && it.state == WorkInfo.State.ENQUEUED }) {
                repository.get(id)?.let { enqueue(it, ExistingWorkPolicy.REPLACE, held = false) }
            }
        }
    }

    private suspend fun infos() = workManager.getWorkInfosByTagFlow(TAG).first()

    private fun WorkInfo.occupiesSlot() = !state.isFinished && HELD !in tags

    companion object {
        const val TAG = "media-download"
        private const val ID_TAG = "media-id:"
        private const val NAME_TAG = "media-name:"
        private const val HELD = "media-held"
        private const val HOLD_DAYS = 3650L
        private const val BACKOFF_SECONDS = 15L
        const val MAX_PARALLEL = 2
        val ACTIVE = setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.WAITING)

        fun workName(mediaId: String) = "media-download-$mediaId"

        private fun idOf(info: WorkInfo) = info.tags.firstOrNull { it.startsWith(ID_TAG) }?.removePrefix(ID_TAG)

        internal fun toProgress(info: WorkInfo, reasons: Map<String, RetryReason> = emptyMap()): TransferProgress? {
            val id = idOf(info) ?: return null
            val state = when (info.state) {
                WorkInfo.State.ENQUEUED -> if (info.runAttemptCount > 0) TransferState.WAITING else TransferState.QUEUED
                WorkInfo.State.BLOCKED -> TransferState.QUEUED
                WorkInfo.State.RUNNING -> TransferState.RUNNING
                WorkInfo.State.SUCCEEDED -> TransferState.DONE
                WorkInfo.State.FAILED -> TransferState.FAILED
                WorkInfo.State.CANCELLED -> TransferState.CANCELLED
            }
            val total = info.progress.getLong(DownloadWorker.KEY_TOTAL, -1).takeIf { it >= 0 }
            val retry = reasons[id]?.takeIf { state == TransferState.WAITING }
            return TransferProgress(
                mediaId = id,
                name = info.tags.firstOrNull { it.startsWith(NAME_TAG) }?.removePrefix(NAME_TAG).orEmpty(),
                state = state,
                bytes = info.progress.getLong(DownloadWorker.KEY_BYTES, 0),
                totalBytes = total,
                failure = info.outputData.getString(DownloadWorker.KEY_ERROR)?.let { name -> DownloadFailure.entries.firstOrNull { it.name == name } }
                    ?: retry?.failure,
                httpCode = info.outputData.getInt(DownloadWorker.KEY_HTTP, -1).takeIf { it >= 0 } ?: retry?.httpCode,
                detail = info.outputData.getString(DownloadWorker.KEY_DETAIL) ?: retry?.detail,
            )
        }
    }
}

/**
 * Runs one download in the foreground (data sync) with a German progress notification and a cancel action.
 * Without a Ready session it retries without using up attempts ([MAX_ATTEMPTS] counts real failures only).
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: MediaDownloader,
    private val queue: DownloadQueue,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(KEY_ID) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME).orEmpty()
        // Not allowed from the background on Android 12+: then it runs as normal work, without a progress notification.
        val foreground = runCatching { setForeground(foregroundInfo(name, 0, null)) }.isSuccess
        try {
            val result = try {
                var last = 0L
                downloader.download(mediaId) { bytes, total ->
                    val now = System.currentTimeMillis()
                    if (now - last >= PROGRESS_INTERVAL_MS || bytes == total) {
                        last = now
                        setProgress(workDataOf(KEY_BYTES to bytes, KEY_TOTAL to (total ?: -1L)))
                        if (foreground) updateNotification(name, bytes, total)
                    }
                }
                attempts(mediaId, clear = true)
                queue.retryReason(mediaId, null)
                Result.success()
            } catch (e: CancellationException) {
                if (discardIfCancelled(mediaId)) withContext(NonCancellable) { queue.promote(excluding = id) }
                throw e
            } catch (e: IOException) {
                failed(mediaId, e)
            }
            // A retried work keeps its slot; a finished one frees it for a held download.
            if (result != Result.retry()) queue.promote(excluding = id) // Result equality is by kind
            return result
        } finally {
            // The progress notification belongs to this run: none may remain after success, retry, failure or stop.
            runCatching { NotificationManagerCompat.from(applicationContext).cancel(notificationId) }
        }
    }

    private suspend fun failed(mediaId: String, e: IOException): Result {
        if (discardIfCancelled(mediaId)) return Result.failure() // ignored by WorkManager: the work is cancelled
        if (e is RecorderNotReadyException || e is RecorderNotBoundException) return Result.retry() // waits, uses no attempt
        val download = e as? DownloadException
        val failure = download?.failure ?: DownloadFailure.NETWORK
        val detail = failureDetail(e)
        if (download?.permanent != true && attempts(mediaId) < MAX_ATTEMPTS) {
            queue.retryReason(mediaId, RetryReason(failure, download?.httpCode, detail))
            return Result.retry()
        }
        attempts(mediaId, clear = true)
        queue.retryReason(mediaId, null)
        return Result.failure(
            workDataOf(KEY_ERROR to failure.name, KEY_HTTP to (download?.httpCode ?: -1), KEY_DETAIL to detail),
        )
    }

    /**
     * Cancelled by the user (sheet, notification "Abbrechen"): the part is dropped. Checked through the work's own
     * state, so it works on every Android version; a REPLACE successor (resume after Ready) keeps the part.
     */
    private suspend fun discardIfCancelled(mediaId: String): Boolean = withContext(NonCancellable) {
        if (!isStopped) return@withContext false
        val works = WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWorkFlow(DownloadQueue.workName(mediaId)).first()
        val cancelled = works.any { it.id == id && it.state == WorkInfo.State.CANCELLED } && works.none { !it.state.isFinished }
        if (cancelled) downloader.discardPartial(mediaId)
        cancelled
    }

    /** Real failures per media id (not-ready retries are not counted); returns the new count. */
    private fun attempts(mediaId: String, clear: Boolean = false): Int {
        val prefs = applicationContext.getSharedPreferences(ATTEMPTS, Context.MODE_PRIVATE)
        if (clear) {
            prefs.edit { remove(mediaId) }
            return 0
        }
        val count = prefs.getInt(mediaId, 0) + 1
        prefs.edit { putInt(mediaId, count) }
        return count
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(inputData.getString(KEY_NAME).orEmpty(), 0, null)

    private val notificationId get() = id.hashCode()

    /**
     * Without the notification permission (Android 13+) the transfer runs silently; the in-app sheet shows it.
     * A failing notification never fails the transfer.
     */
    private fun updateNotification(name: String, bytes: Long, total: Long?) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        try {
            if (granted) notifications().notify(notificationId, notification(name, bytes, total))
        } catch (e: RuntimeException) {
            Log.w(TAG, "progress notification failed", e)
        }
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
        const val KEY_HTTP = "http"
        const val KEY_DETAIL = "detail"
        const val MAX_ATTEMPTS = 10
        private const val ATTEMPTS = "media_download_attempts"
        private const val TAG = "DownloadWorker"
        private const val CHANNEL = "media_transfers"
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}

/** "12,3 MB von 86 MB" / "12,3 MB" (sizes of the phone's download, formatted by Android). */
fun transferText(context: Context, bytes: Long, total: Long?): String {
    val done = android.text.format.Formatter.formatShortFileSize(context, bytes)
    return if (total == null || total <= 0) done
    else context.getString(R.string.media_transfer_of, done, android.text.format.Formatter.formatShortFileSize(context, total))
}
