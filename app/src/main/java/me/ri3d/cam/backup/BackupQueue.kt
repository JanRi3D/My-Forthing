package me.ri3d.cam.backup

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.startup.Initializer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ri3d.cam.R
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.drive.DriveAuth
import me.ri3d.cam.drive.DriveAuthState
import me.ri3d.cam.media.BackupState
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaRepository
import me.ri3d.cam.media.transferText
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The running or waiting upload of one item, as WorkManager reports it. */
data class BackupProgress(val running: Boolean, val bytes: Long, val total: Long)

/** Outcome of a manual "Sichern". */
data class EnqueueResult(val queued: Int, val notOnPhone: Int, val alreadyDone: Int)

/**
 * Durable backup queue (CONTRACTS §10). The queue itself is the `backupState` column (QUEUED / UPLOADING); WorkManager
 * holds exactly one unique work per media id at a time – the item uploading now – with the network constraints of
 * [BackupRules.constraints]. When it ends, the next pending item is enqueued. Nothing runs while Drive is not
 * connected, needs a reconnect or is full.
 */
@Singleton
class BackupQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MediaRepository,
    private val preferences: PreferencesRepository,
    private val auth: DriveAuth,
    private val backup: DriveBackup,
    private val store: BackupStore,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var started = false

    val storageFull: StateFlow<Boolean> = store.storageFull

    val progress: StateFlow<Map<String, BackupProgress>> = workManager.getWorkInfosByTagFlow(TAG)
        .map { infos ->
            infos.filter { !it.state.isFinished }.mapNotNull { info ->
                idOf(info)?.let {
                    it to BackupProgress(info.state == WorkInfo.State.RUNNING, info.progress.getLong(KEY_BYTES, 0), info.progress.getLong(KEY_TOTAL, 0))
                }
            }.toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Applies the automatic rules while the process lives ([BackupInitializer]). */
    fun start() = synchronized(this) {
        if (!started) {
            started = true
            Log.d(LOG_TAG, "automatic backup rules started")
            scope.launch { observe() }
        }
    }

    /**
     * Library × preferences × Drive state × storage pause: resets on an account switch, queues what the automatic
     * rules select once it is on the phone, re-applies changed network conditions to the waiting work, and starts the
     * next upload.
     */
    suspend fun observe() {
        var applied: Constraints? = null
        combine(repository.observe(), preferences.preferences, auth.state, store.storageFull) { items, prefs, state, full ->
            val email = DriveBackup.accountOf(state)
            if (email != null) {
                if (backup.adoptAccount(email)) workManager.cancelAllWorkByTag(TAG).await()
                val byId = items.associateBy { it.id }
                // ponytail: scans the whole library on every change; a DAO query for NONE rows if libraries get huge.
                items.filter { it.backupState == BackupState.NONE && !store.isExcluded(it.id) && BackupRules.automatic(it, prefs.backupMode, byId[it.parentId]) }
                    .forEach { queue(it.id) }
                if (!full) BackupNotifications.cancelAlert(context) // reconnected / room made
            }
            val constraints = BackupRules.constraints(prefs)
            schedule(replace = applied != null && constraints != applied)
            applied = constraints
        }.collect {}
    }

    /** Manual "Sichern": queues the phone copies among [ids]. Lifts a storage pause (the user may have made room). */
    suspend fun enqueue(ids: Collection<String>): EnqueueResult {
        var queued = 0
        var notOnPhone = 0
        var done = 0
        for (id in ids) {
            val item = repository.get(id) ?: continue
            when {
                item.backupState == BackupState.DONE -> done++
                !BackupRules.hasPhoneCopy(item) -> notOnPhone++
                else -> {
                    store.include(id)
                    store.clearFailures(id)
                    queue(id)
                    queued++
                }
            }
        }
        if (queued > 0) store.setStorageFull(false)
        schedule()
        return EnqueueResult(queued, notOnPhone, done)
    }

    suspend fun retry(id: String) {
        enqueue(listOf(id))
    }

    /** "Fortsetzen" after Drive was full. */
    suspend fun resume() {
        store.setStorageFull(false)
        BackupNotifications.cancelAlert(context)
        schedule()
    }

    /** "Jetzt prüfen": applies the automatic rules now, including items that failed before, and lifts a storage pause. */
    suspend fun checkNow() {
        store.setStorageFull(false)
        val mode = preferences.preferences.first().backupMode
        val items = repository.observe().first()
        val byId = items.associateBy { it.id }
        items.filter {
            (it.backupState == BackupState.NONE || it.backupState == BackupState.FAILED) && !store.isExcluded(it.id) &&
                BackupRules.automatic(it, mode, byId[it.parentId])
        }.forEach {
            store.clearFailures(it.id)
            queue(it.id)
        }
        schedule()
    }

    /** Stops and forgets the upload of [id] (also a failed one); the automatic rules skip it until "Sichern". */
    suspend fun cancel(id: String) {
        store.exclude(id)
        store.clearSession(id)
        store.clearFailures(id)
        repository.update(id) {
            if (it.backupState in BackupRules.PENDING || it.backupState == BackupState.FAILED) it.copy(backupState = BackupState.NONE, backupError = null) else it
        }
        workManager.cancelUniqueWork(workName(id)).await()
        schedule()
    }

    /** "Drive-Kopie löschen": media file and sidecar; phone and recorder copies stay. */
    suspend fun deleteOnDrive(id: String): Result<Unit> = backup.deleteOnDrive(id)

    /** The default network right now fits the network conditions (checked by the worker before it uploads). */
    suspend fun networkFits(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return BackupRules.networkFits(connectivity.getNetworkCapabilities(connectivity.activeNetwork), preferences.preferences.first())
    }

    /** "Drive-Status prüfen"; returns how many DONE items were missing in Drive. */
    suspend fun reconcile(): Result<Int> = backup.reconcile()

    /**
     * Enqueues the next pending item (an interrupted UPLOADING one first) unless one is active. [replace] restarts the
     * active one with the current constraints (it resumes from its session URI). [excluding] = the calling worker.
     */
    suspend fun schedule(replace: Boolean = false, excluding: UUID? = null) = mutex.withLock {
        if (auth.state.value !is DriveAuthState.Connected || store.storageFull.value) return@withLock
        val active = workManager.getWorkInfosByTagFlow(TAG).first().filter { !it.state.isFinished && it.id != excluding }.mapNotNull(::idOf)
        if (active.isNotEmpty() && !replace) return@withLock
        // ponytail: newest recordings first (library order), not strictly first in, first out: there is no queue time.
        val items = repository.observe().first().filter { it.backupState in BackupRules.PENDING }
        val next = items.firstOrNull { it.id in active } ?: items.firstOrNull { it.backupState == BackupState.UPLOADING } ?: items.firstOrNull()
            ?: return@withLock
        enqueueWork(next, BackupRules.constraints(preferences.preferences.first()), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    private suspend fun queue(id: String) {
        repository.update(id) {
            if (it.backupState == BackupState.NONE || it.backupState == BackupState.FAILED) it.copy(backupState = BackupState.QUEUED, backupError = null) else it
        }
    }

    private suspend fun enqueueWork(item: MediaItem, constraints: Constraints, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf(KEY_ID to item.id, KEY_NAME to item.originalFileName))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(ID_TAG + item.id)
            .build()
        workManager.enqueueUniqueWork(workName(item.id), policy, request).await()
    }

    companion object {
        const val TAG = "backup"
        private const val ID_TAG = "backup-id:"
        const val KEY_ID = "mediaId"
        const val KEY_NAME = "name"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        private const val BACKOFF_SECONDS = 30L
        private const val LOG_TAG = "BackupQueue"

        fun workName(mediaId: String) = "backup-$mediaId"

        private fun idOf(info: WorkInfo) = info.tags.firstOrNull { it.startsWith(ID_TAG) }?.removePrefix(ID_TAG)
    }
}

/** Uploads one item in the foreground (data sync) with a German progress notification; then starts the next one. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backup: DriveBackup,
    private val queue: BackupQueue,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(BackupQueue.KEY_ID) ?: return Result.success()
        val name = inputData.getString(BackupQueue.KEY_NAME).orEmpty()
        // JobScheduler decided on the network a moment ago; the default network may have changed since.
        if (!queue.networkFits()) return Result.retry()
        // Not allowed from the background on Android 12+: then it runs as normal work (and may be stopped and resumed).
        runCatching { setForeground(foregroundInfo(name, 0, 0)) }
        var last = 0L
        val outcome = try {
            backup.upload(mediaId) { sent, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - last >= PROGRESS_INTERVAL_MS || sent == total) {
                    last = now
                    setProgressAsync(workDataOf(BackupQueue.KEY_BYTES to sent, BackupQueue.KEY_TOTAL to total))
                    BackupNotifications.show(applicationContext, id.hashCode()) { BackupNotifications.progress(applicationContext, name, sent, total) }
                }
            }
        } finally {
            BackupNotifications.cancel(applicationContext, id.hashCode()) // never leave an ongoing progress notification
        }
        if (outcome is BackupOutcome.Paused && outcome.reason != PauseReason.NOT_CONNECTED) {
            BackupNotifications.alert(applicationContext, outcome.reason)
        }
        // Right after process start Drive reads "not connected" until the account record is loaded: try again then.
        if (outcome == BackupOutcome.Retry || outcome == BackupOutcome.Paused(PauseReason.NOT_CONNECTED) && backup.connected) return Result.retry()
        queue.schedule(excluding = id)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(inputData.getString(BackupQueue.KEY_NAME).orEmpty(), 0, 0)

    private fun foregroundInfo(name: String, sent: Long, total: Long): ForegroundInfo {
        val notification = BackupNotifications.progress(applicationContext, name, sent, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
    }
}

/** Progress (silent) and "needs you" notifications; without the permission (Android 13+) nothing is shown. */
internal object BackupNotifications {
    private const val CHANNEL = "backup"
    private const val ALERTS = "backup_alerts"
    private const val ALERT_ID = 0x4246 // one alert at a time
    private const val TAG = "BackupNotifications"

    fun progress(context: Context, name: String, sent: Long, total: Long): Notification {
        channels(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(context.getString(R.string.backup_notification_title, name))
            .setContentText(transferText(context, sent, total.takeIf { it > 0 }))
            .setProgress(100, if (total > 0) (sent * 100 / total).toInt() else 0, total <= 0)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    /** "Google Drive erneut verbinden" / "Google-Drive-Speicher voll"; tapping opens the app. */
    fun alert(context: Context, reason: PauseReason) {
        channels(context)
        val (title, text) = when (reason) {
            PauseReason.STORAGE_FULL -> R.string.backup_notification_storage_title to R.string.backup_notification_storage_text
            else -> R.string.backup_notification_reconnect_title to R.string.backup_notification_reconnect_text
        }
        show(context, ALERT_ID) {
            NotificationCompat.Builder(context, ALERTS)
                .setSmallIcon(R.drawable.ic_cloud_upload)
                .setContentTitle(context.getString(title))
                .setContentText(context.getString(text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
        }
    }

    fun cancelAlert(context: Context) = cancel(context, ALERT_ID)

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /** A failing notification never fails the backup. */
    fun show(context: Context, id: Int, build: () -> Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        try {
            if (granted) NotificationManagerCompat.from(context).notify(id, build())
        } catch (e: RuntimeException) {
            Log.w(TAG, "notification failed", e)
        }
    }

    private fun channels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(context.getString(R.string.backup_channel)).build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(ALERTS, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(context.getString(R.string.backup_channel_alerts)).build(),
        )
    }

    private fun openApp(context: Context): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupEntryPoint {
    fun backupQueue(): BackupQueue
}

/**
 * Starts [BackupQueue.start] with every process (also one started only for a download in the background), so items
 * that become local are queued by the automatic rules. Registered in the manifest under androidx.startup.
 */
class BackupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Providers run before Application.onCreate, which injects the Hilt worker factory WorkManager needs: post.
        Handler(Looper.getMainLooper()).post {
            EntryPointAccessors.fromApplication(context, BackupEntryPoint::class.java).backupQueue().start()
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
