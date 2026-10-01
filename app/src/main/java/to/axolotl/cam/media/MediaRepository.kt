package to.axolotl.cam.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.FileObserver
import androidx.core.graphics.scale
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.axolotl.cam.core.data.AppDatabase
import to.axolotl.cam.core.log.Log
import to.axolotl.cam.dashcam.RecorderConnectionManager
import to.axolotl.cam.enhance.EnhancementInfo
import to.axolotl.cam.recorder.RecorderCommand
import to.axolotl.cam.recorder.RecorderFile
import to.axolotl.cam.recorder.RecorderResult
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local library (CONTRACTS §8). Three copies, three explicit actions: [deleteLocalCopy], [deleteOnRecorder] and
 * (feature/drive-backup) `deleteOnDrive` followed by [markDriveDeleted]. None cascades; a row is removed only when
 * no copy is left. Phone copies live in app storage: downloads in `files/media/<id>/`, screenshots (written by
 * feature/live-view) in `files/screenshots/`, enhanced outputs in `files/enhance/`, thumbnails in `files/thumbs/`.
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val manager: RecorderConnectionManager,
) {
    private val dao = db.mediaDao()
    private val mutex = Mutex() // read-modify-write of rows
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var screenshotObserver: FileObserver? = null // strong reference: a collected observer stops watching

    val mediaDir get() = File(context.filesDir, "media")
    val screenshotDir get() = File(context.filesDir, "screenshots")
    private val thumbDir get() = File(context.filesDir, "thumbs")

    fun observe(id: String): Flow<MediaItem?> = dao.observe(id)
    fun observe(kind: MediaKind? = null, category: MediaCategory? = null): Flow<List<MediaItem>> = dao.observe(kind, category)
    fun observeLocal(): Flow<List<MediaItem>> = dao.observeLocal()
    fun observeRecorderType(type: Int): Flow<List<MediaItem>> = dao.observeRecorderType(type)
    fun observeChildren(id: String): Flow<List<MediaItem>> = dao.observeChildren(id)
    suspend fun get(id: String): MediaItem? = dao.get(id)
    suspend fun localItems(kinds: Collection<MediaKind>): List<MediaItem> = dao.localOf(kinds.toList())

    /**
     * Gives every listed 4100 entry of [type] a row (id stable per recorder path) so it can be downloaded, opened and
     * backed up; updates thumbnail path and time when the recorder reports new ones. Entries without fileName are
     * skipped (nothing could address them).
     *
     * Paths can be reused (format, clock reset): a known path with another `fileTime` whose row has a phone or Drive
     * copy is another recording, so the old row is detached (`recorderPath = null`) and a new row inserted. A path
     * without a row re-links a detached row of the same type, name and time (the file is back on the recorder).
     */
    suspend fun upsertFromRecorderListing(type: Int, files: List<RecorderFile>) = mutex.withLock {
        db.withTransaction {
            for (file in files) {
                val path = file.fileName ?: continue
                val existing = dao.byRecorderPath(path)
                when {
                    existing == null -> {
                        val detached = dao.detached(type, path.substringAfterLast('/'), file.fileTime)
                        if (detached != null) dao.update(detached.listed(type, path, file)) else dao.insert(fromListing(type, path, file))
                    }
                    existing.recorderTime != null && file.fileTime != null && existing.recorderTime != file.fileTime &&
                        (existing.localUri != null || existing.driveFileId != null) -> {
                        dao.update(existing.copy(recorderPath = null, recorderThumbPath = null))
                        dao.insert(fromListing(type, path, file))
                    }
                    existing.recorderThumbPath != file.fileThm || existing.recorderTime != file.fileTime || existing.recorderType != type ->
                        dao.update(existing.listed(type, path, file))
                }
            }
        }
    }

    /**
     * A listing of [type] that reached the recorder's `totalFileNum`: rows whose path was not listed are no longer on
     * the recorder (loop overwrite, deleted elsewhere). Rows for which [keep] is true (download queued or running)
     * are left alone.
     */
    suspend fun reconcileRecorderListing(type: Int, listed: Set<String>, keep: (String) -> Boolean = { false }) {
        dao.recorderType(type).filter { it.recorderPath !in listed && !keep(it.id) }.forEach { markRecorderDeleted(it.recorderPath!!) }
    }

    private fun MediaItem.listed(type: Int, path: String, file: RecorderFile) = copy(
        recorderType = type, category = MediaCategory.of(type), recorderPath = path, recorderThumbPath = file.fileThm,
        recorderTime = file.fileTime, recorderTimeEpochGuess = epochGuess(file.fileTime),
    )

    /**
     * Registers an output of feature/enhance-ui as its own item linked to [parentId]. The original is never modified;
     * the output's file name is reused as id when it is a UUID (as `EnhancedOutput.id`).
     */
    suspend fun registerDerived(kind: MediaKind, file: File, parentId: String, parentPositionMs: Long?, info: EnhancementInfo?): MediaItem {
        require(kind == MediaKind.ENHANCED_FRAME || kind == MediaKind.UPSCALED_CLIP) { "not a derived kind: $kind" }
        val id = file.nameWithoutExtension.takeIf(::isUuid) ?: UUID.randomUUID().toString()
        val parent = dao.get(parentId)
        val item = newItem(
            id, kind, parent?.category ?: MediaCategory.UNKNOWN, file.name, createdAt = info?.createdAt ?: System.currentTimeMillis(),
        ).copy(
            localUri = file.toURI().toString(), localSizeBytes = file.length(),
            localThumbPath = thumbnail(id, file, kind == MediaKind.UPSCALED_CLIP), parentId = parentId, parentPositionMs = parentPositionMs,
        )
        return mutex.withLock { if (dao.insert(item) == -1L) dao.get(id)!! else item }
    }

    /**
     * Imports `files/screenshots/<uuid>.jpg` + `<uuid>.json` (`{ id, capturedAt, source, width, height }`, written by
     * feature/live-view; the JSON is the commit marker and comes last). Already known ids are skipped. Returns the
     * number of new items.
     */
    suspend fun importScreenshots(): Int = withContext(Dispatchers.IO) {
        screenshotDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().count { importScreenshot(it) }
    }

    /** Imports new screenshots as they are written (once per process; also call [importScreenshots] on open). */
    fun watchScreenshots() = synchronized(this) {
        if (screenshotObserver != null) return
        val dir = screenshotDir.apply { mkdirs() }
        @Suppress("DEPRECATION") // the File constructor needs API 29
        screenshotObserver = object : FileObserver(dir.path, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path?.endsWith(".json") == true) scope.launch { importScreenshot(File(dir, path)) }
            }
        }.also { it.startWatching() }
    }

    private suspend fun importScreenshot(json: File): Boolean {
        val jpg = File(json.path.removeSuffix(".json") + ".jpg")
        if (!jpg.isFile) return false
        val meta = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return false
        val id = meta.optString("id").ifBlank { jpg.nameWithoutExtension }
        if (dao.get(id) != null) return false
        val item = newItem(id, MediaKind.SCREENSHOT, MediaCategory.UNKNOWN, jpg.name, createdAt = capturedAt(meta) ?: jpg.lastModified())
            .copy(localUri = jpg.toURI().toString(), localSizeBytes = jpg.length(), localThumbPath = thumbnail(id, jpg, video = false))
        return mutex.withLock { dao.insert(item) != -1L }
    }

    /** Called by the download worker after the atomic rename. */
    suspend fun markDownloaded(id: String, file: File): MediaItem? = mutex.withLock {
        val item = dao.get(id) ?: return null
        item.copy(
            localUri = file.toURI().toString(), localSizeBytes = file.length(), downloadedAt = System.currentTimeMillis(),
            localThumbPath = thumbnail(id, file, item.isVideo),
        ).also { dao.update(it) }
    }

    /**
     * Deletes the phone copy (file, thumbnail, screenshot JSON / enhancement sidecar). The row stays while a recorder
     * or Drive copy is known, otherwise it is removed. Recorder and Drive copies are not touched.
     */
    suspend fun deleteLocalCopy(id: String) = mutex.withLock {
        val item = dao.get(id) ?: return@withLock
        withContext(Dispatchers.IO) {
            item.localFile?.let { file ->
                file.delete()
                when (item.kind) {
                    MediaKind.SCREENSHOT -> File(file.path.substringBeforeLast('.') + ".json").delete()
                    MediaKind.ENHANCED_FRAME, MediaKind.UPSCALED_CLIP -> EnhancementInfo.sidecarOf(file).delete()
                    else -> file.parentFile?.takeIf { it.parentFile == mediaDir }?.delete() // media/<id>/, if empty
                }
            }
            item.localThumbPath?.let { File(it).delete() }
        }
        val rest = item.copy(localUri = null, localSizeBytes = null, localThumbPath = null, downloadedAt = null)
        if (rest.hasCopy()) dao.update(rest) else dao.delete(id)
    }

    /**
     * 4101 for [paths] (device paths, never the HTTP URL). On rval 0 the recorder copies are forgotten; phone and
     * Drive copies stay.
     */
    suspend fun deleteOnRecorder(paths: List<String>): RecorderResult<Unit> {
        val result = manager.request(RecorderCommand.DeleteFiles(paths), { })
        if (result is RecorderResult.Ok) paths.forEach { markRecorderDeleted(it) }
        return result
    }

    /** The recorder no longer has [path] (4101 ok, `fileDel`, or missing from a complete listing). */
    suspend fun markRecorderDeleted(path: String) = mutex.withLock {
        val item = dao.byRecorderPath(path) ?: return@withLock
        val rest = item.copy(recorderPath = null, recorderThumbPath = null)
        if (rest.hasCopy()) dao.update(rest) else dao.delete(item.id)
    }

    /** For feature/drive-backup after `deleteOnDrive(id)`: forgets the Drive copy, removes the row if none is left. */
    suspend fun markDriveDeleted(id: String) = mutex.withLock {
        val item = dao.get(id) ?: return@withLock
        val rest = item.copy(driveFileId = null, driveMd5 = null, backupState = BackupState.NONE, backupError = null)
        if (rest.hasCopy()) dao.update(rest) else dao.delete(id)
    }

    /** For feature/drive-backup: backup columns (state, Drive id, md5, error). Returns the updated row. */
    suspend fun update(id: String, transform: (MediaItem) -> MediaItem): MediaItem? = mutex.withLock {
        dao.get(id)?.let(transform)?.also { dao.update(it) }
    }

    private fun MediaItem.hasCopy() = localUri != null || recorderPath != null || driveFileId != null

    private fun fromListing(type: Int, path: String, file: RecorderFile) = newItem(
        UUID.randomUUID().toString(), kindOf(path), MediaCategory.of(type), path.substringAfterLast('/'),
        createdAt = System.currentTimeMillis(),
    ).copy(
        recorderType = type, recorderPath = path, recorderThumbPath = file.fileThm,
        recorderTime = file.fileTime, recorderTimeEpochGuess = epochGuess(file.fileTime),
    )

    /** JPEG thumbnail (≤ 320 px) in `files/thumbs/<id>.jpg`; null when the file cannot be decoded. */
    private suspend fun thumbnail(id: String, file: File, video: Boolean): String? = withContext(Dispatchers.IO) {
        val bitmap = runCatching { if (video) videoFrame(file) else decodeSampled(file) }.getOrNull() ?: return@withContext null
        val target = File(thumbDir.apply { mkdirs() }, "$id.jpg")
        try {
            val scale = THUMB_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            val small = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
            target.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            target.path
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail failed", e)
            target.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun videoFrame(file: File): Bitmap? = MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            getFrameAtTime(0)
        } finally {
            release()
        }
    }

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val TAG = "MediaRepository"
        private const val THUMB_PX = 320
        private val RECORDER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "heic", "webp")

        /** Raw recorder time as local date-time; null when it is not "yyyy-MM-dd HH:mm:ss". */
        fun parseRecorderTime(raw: String?): LocalDateTime? = raw?.let { runCatching { LocalDateTime.parse(it.trim(), RECORDER_TIME) }.getOrNull() }

        /** The recorder's zone is unknown: read in the phone's zone, a guess. */
        fun epochGuess(raw: String?): Long? = parseRecorderTime(raw)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

        fun kindOf(path: String): MediaKind =
            if (path.substringAfterLast('.', "").lowercase() in PHOTO_EXTENSIONS) MediaKind.ORIGINAL_PHOTO else MediaKind.ORIGINAL_VIDEO

        private fun isUuid(text: String) = runCatching { UUID.fromString(text).toString() == text.lowercase() }.getOrDefault(false)

        /** `capturedAt` as epoch milliseconds or ISO-8601 text (with offset or as an instant). */
        private fun capturedAt(meta: JSONObject): Long? = when (val v = meta.opt("capturedAt")) {
            is Number -> v.toLong()
            is String -> runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }
                .recoverCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            else -> null
        }

        private fun newItem(id: String, kind: MediaKind, category: MediaCategory, name: String, createdAt: Long) = MediaItem(
            id = id, kind = kind, category = category, recorderType = null, recorderPath = null, recorderThumbPath = null,
            originalFileName = name, recorderTime = null, recorderTimeEpochGuess = null, localUri = null, localSizeBytes = null,
            localThumbPath = null, downloadedAt = null, parentId = null, parentPositionMs = null, driveFileId = null,
            backupState = BackupState.NONE, backupError = null, driveMd5 = null, createdAt = createdAt,
        )
    }
}
