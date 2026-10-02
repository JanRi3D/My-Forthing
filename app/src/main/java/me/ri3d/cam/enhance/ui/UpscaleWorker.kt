package me.ri3d.cam.enhance.ui

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.cam.R
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.enhance.ClipUpscaler
import me.ri3d.cam.enhance.EnhanceEngine
import me.ri3d.cam.enhance.EnhancedKind
import me.ri3d.cam.enhance.EnhancementInfo
import me.ri3d.cam.enhance.Resolution
import me.ri3d.cam.enhance.UpscaleError
import me.ri3d.cam.enhance.UpscaleProgress
import me.ri3d.cam.enhance.UpscaleRequest
import me.ri3d.cam.enhance.UpscaleResult
import me.ri3d.cam.enhance.awaitResult
import me.ri3d.cam.enhance.enhanceDir
import me.ri3d.cam.media.MediaKind
import me.ri3d.cam.media.MediaRepository
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why an upscale ended without a clip; German text for screen and notification. */
enum class UpscaleFailureKind(@StringRes val text: Int) {
    DECODER(R.string.upscale_failure_decoder),
    ENCODER(R.string.upscale_failure_encoder),
    STORAGE(R.string.upscale_failure_storage),
    MEMORY(R.string.upscale_failure_memory),
    TARGET_NOT_LARGER(R.string.upscale_failure_target_not_larger),
    CANCELLED(R.string.upscale_cancelled),
    SOURCE_GONE(R.string.upscale_failure_source_gone),
    INTERRUPTED(R.string.upscale_failure_interrupted),
    NOT_ORIGINAL(R.string.upscale_failure_not_original);

    companion object {
        fun of(error: UpscaleError) = when (error) {
            is UpscaleError.Decoder -> DECODER
            is UpscaleError.Encoder -> ENCODER
            is UpscaleError.Storage -> STORAGE
            is UpscaleError.Memory -> MEMORY
            is UpscaleError.TargetNotLarger -> TARGET_NOT_LARGER
            UpscaleError.Cancelled -> CANCELLED
        }
    }
}

/** The latest upscale as WorkManager knows it (it survives leaving the screen and restarts after process death). */
data class UpscaleJob(
    val workId: UUID,
    val mediaId: String,
    val target: Resolution,
    val engine: EnhanceEngine,
    val state: WorkInfo.State,
    val progress: UpscaleProgress?,
    /** Library id of the new clip once done. */
    val outputId: String?,
    val failure: UpscaleFailureKind?,
) {
    val active get() = !state.isFinished
}

/** One upscale at a time (hardware codecs are scarce): a unique WorkManager work. */
@Singleton
class UpscaleJobs @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Null when no upscale is known (finished ones are pruned by WorkManager after a while). */
    val current: Flow<UpscaleJob?> get() = workManager.getWorkInfosForUniqueWorkFlow(WORK).map { infos -> infos.lastOrNull()?.let(::toJob) }

    /** Does nothing while another upscale is pending or running (KEEP); a finished one is replaced. */
    fun start(mediaId: String, target: Resolution, engine: EnhanceEngine) {
        val request = OneTimeWorkRequestBuilder<UpscaleWorker>()
            .setInputData(
                workDataOf(
                    UpscaleWorker.KEY_ID to mediaId, UpscaleWorker.KEY_TARGET to target.name, UpscaleWorker.KEY_ENGINE to engine.name,
                    UpscaleWorker.KEY_ENQUEUED_AT to System.currentTimeMillis(),
                ),
            )
            .addTag(TAG_ID + mediaId).addTag(TAG_TARGET + target.name).addTag(TAG_ENGINE + engine.name)
            .build()
        workManager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK)
    }

    companion object {
        const val WORK = "enhance-upscale"
        private const val TAG_ID = "upscale-id:"
        private const val TAG_TARGET = "upscale-target:"
        private const val TAG_ENGINE = "upscale-engine:"

        internal fun toJob(info: WorkInfo): UpscaleJob? {
            fun tag(prefix: String) = info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            val fraction = info.progress.getFloat(UpscaleWorker.KEY_FRACTION, -1f)
            return UpscaleJob(
                workId = info.id,
                mediaId = tag(TAG_ID) ?: return null,
                target = Resolution.entries.firstOrNull { it.name == tag(TAG_TARGET) } ?: return null,
                engine = EnhanceEngine.entries.firstOrNull { it.name == tag(TAG_ENGINE) } ?: EnhanceEngine.CLASSICAL,
                state = info.state,
                progress = fraction.takeIf { it >= 0 }?.let {
                    UpscaleProgress(
                        it,
                        info.progress.getLong(UpscaleWorker.KEY_ETA, -1).takeIf { v -> v >= 0 },
                        info.progress.getLong(UpscaleWorker.KEY_BYTES, -1).takeIf { v -> v >= 0 },
                    )
                },
                outputId = info.outputData.getString(UpscaleWorker.KEY_OUTPUT_ID),
                failure = info.outputData.getString(UpscaleWorker.KEY_ERROR)?.let { name -> UpscaleFailureKind.entries.firstOrNull { it.name == name } },
            )
        }
    }
}

/**
 * Runs [ClipUpscaler] as a `dataSync` foreground service with a German progress notification ("Abbrechen" cancels the
 * work, which cancels the pipeline: nothing stays on disk). Done → the clip is registered as `UPSCALED_CLIP` linked
 * to its original. A stop by the system reschedules the work, which then starts over; after [MAX_ATTEMPTS] it gives up.
 */
@HiltWorker
class UpscaleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val upscaler: ClipUpscaler,
    private val repository: MediaRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        upscale()
    } finally {
        // When setForeground was not allowed (background restart) the progress notification is ours to remove.
        runCatching { NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_RUNNING) }
    }

    private suspend fun upscale(): Result {
        val mediaId = inputData.getString(KEY_ID) ?: return Result.failure()
        val target = Resolution.entries.firstOrNull { it.name == inputData.getString(KEY_TARGET) } ?: return Result.failure()
        val engine = EnhanceEngine.entries.firstOrNull { it.name == inputData.getString(KEY_ENGINE) } ?: EnhanceEngine.CLASSICAL
        if (runAttemptCount >= MAX_ATTEMPTS) return failed(UpscaleFailureKind.INTERRUPTED)
        val item = repository.get(mediaId)
        if (item != null && item.kind != MediaKind.ORIGINAL_VIDEO) return failed(UpscaleFailureKind.NOT_ORIGINAL)
        val file = item?.localFile?.takeIf { it.isFile } ?: return failed(UpscaleFailureKind.SOURCE_GONE)
        // A restart after process death: an earlier attempt may have finished the clip but not registered it.
        if (runAttemptCount > 0) unregisteredOutput(mediaId)?.let { (output, info) -> return done(mediaId, output, info) }
        // Not allowed when started from the background on Android 12+: then it runs as normal work.
        runCatching { setForeground(foregroundInfo(null)) }

        val progress = MutableStateFlow<UpscaleProgress?>(null) // written on the pipeline thread
        val job = upscaler.upscale(UpscaleRequest(Uri.fromFile(file), target, mediaId, engine)) { progress.value = it }
        val result = try {
            coroutineScope {
                val reporter = launch {
                    progress.filterNotNull().collect { p ->
                        setProgress(progressData(p))
                        notifySafely(NOTIFICATION_RUNNING) { running(p) }
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    job.awaitResult()
                } finally {
                    reporter.cancel()
                }
            }
        } catch (e: CancellationException) {
            // Cancelling the work stops the pipeline. If it had just finished, the clip exists: register it, not orphan it.
            job.cancel()
            withContext(NonCancellable) {
                job.join()
                if (!job.isCancelled) (runCatching { job.await() }.getOrNull() as? UpscaleResult.Done)?.let { register(mediaId, it.output.file, it.output.info) }
            }
            throw e
        }
        return when (result) {
            is UpscaleResult.Done -> done(mediaId, result.output.file, result.output.info)
            is UpscaleResult.Failed -> failed(UpscaleFailureKind.of(result.error))
        }
    }

    private suspend fun done(mediaId: String, output: File, info: EnhancementInfo): Result {
        val item = register(mediaId, output, info)
        notifySafely(NOTIFICATION_FINISHED) { finished(R.string.upscale_notification_done, applicationContext.getString(R.string.upscale_notification_done_text)) }
        return Result.success(workDataOf(KEY_OUTPUT_ID to item.id))
    }

    /** The file exists: registered even if the work is cancelled this very moment (idempotent per file UUID). */
    private suspend fun register(mediaId: String, output: File, info: EnhancementInfo) = withContext(NonCancellable) {
        repository.registerDerived(MediaKind.UPSCALED_CLIP, output, mediaId, null, info)
    }

    /** A clip of [mediaId] finished by an earlier attempt of this work (created after it was enqueued), not in the library. */
    private suspend fun unregisteredOutput(mediaId: String): Pair<File, EnhancementInfo>? = withContext(Dispatchers.IO) {
        val enqueuedAt = inputData.getLong(KEY_ENQUEUED_AT, Long.MAX_VALUE)
        enhanceDir(applicationContext).listFiles { f -> f.name.endsWith(".mp4") }.orEmpty().firstNotNullOfOrNull { f ->
            EnhancementInfo.read(f)
                ?.takeIf { it.kind == EnhancedKind.UPSCALED_CLIP && it.sourceMediaId == mediaId && it.createdAt >= enqueuedAt }
                ?.takeIf { repository.get(f.nameWithoutExtension) == null }
                ?.let { f to it }
        }
    }

    private fun failed(kind: UpscaleFailureKind): Result {
        Log.w(TAG, "upscale failed: $kind")
        if (kind != UpscaleFailureKind.CANCELLED) {
            notifySafely(NOTIFICATION_FINISHED) { finished(R.string.upscale_notification_failed, applicationContext.getString(kind.text)) }
        }
        return Result.failure(workDataOf(KEY_ERROR to kind.name))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(p: UpscaleProgress?): ForegroundInfo {
        val notification = running(p)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_RUNNING, notification)
        }
    }

    private fun running(p: UpscaleProgress?): Notification {
        val context = applicationContext
        val percent = ((p?.fraction ?: 0f) * 100).toInt()
        return builder()
            .setContentTitle(context.getString(R.string.upscale_notification_title))
            .setContentText(
                when {
                    p == null -> context.getString(R.string.upscale_waiting)
                    p.etaMs == null -> context.getString(R.string.upscale_percent, percent)
                    else -> context.getString(R.string.upscale_percent_eta, percent, durationText(context, p.etaMs))
                },
            )
            .setProgress(100, percent, p == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.action_cancel), WorkManager.getInstance(context).createCancelPendingIntent(id))
            .build()
    }

    private fun finished(@StringRes title: Int, text: String): Notification = builder()
        .setContentTitle(applicationContext.getString(title))
        .setContentText(text)
        .setAutoCancel(true)
        .build()

    private fun builder(): NotificationCompat.Builder {
        val context = applicationContext
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(context.getString(R.string.upscale_channel)).build(),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_upscale).setContentIntent(open)
    }

    /**
     * Without the notification permission (Android 13+) the job runs silently; the screen shows its progress.
     * A notification that cannot be built or posted never fails the job.
     */
    private fun notifySafely(id: Int, notification: () -> Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        try {
            if (granted) NotificationManagerCompat.from(applicationContext).notify(id, notification())
        } catch (e: RuntimeException) {
            Log.w(TAG, "notification failed", e)
        }
    }

    companion object {
        const val KEY_ID = "mediaId"
        const val KEY_TARGET = "target"
        const val KEY_ENGINE = "engine"
        const val KEY_ENQUEUED_AT = "enqueuedAt"
        const val KEY_FRACTION = "fraction"
        const val KEY_ETA = "eta"
        const val KEY_BYTES = "bytes"
        const val KEY_OUTPUT_ID = "outputId"
        const val KEY_ERROR = "error"

        /** ponytail: a rescheduled work starts the clip from 0 (no resume); after 3 starts it fails as INTERRUPTED. */
        const val MAX_ATTEMPTS = 3
        private const val TAG = "UpscaleWorker"
        private const val CHANNEL = "enhance_jobs"
        private const val NOTIFICATION_RUNNING = 0x5ca1e
        private const val NOTIFICATION_FINISHED = 0x5ca1f
        private const val PROGRESS_INTERVAL_MS = 1_000L

        internal fun progressData(p: UpscaleProgress): Data =
            workDataOf(KEY_FRACTION to p.fraction, KEY_ETA to (p.etaMs ?: -1L), KEY_BYTES to (p.outputBytesEstimate ?: -1L))
    }
}
