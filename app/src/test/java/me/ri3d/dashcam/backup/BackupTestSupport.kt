package me.ri3d.dashcam.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import me.ri3d.dashcam.core.data.AppDatabase
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.dashcam.managerFor
import me.ri3d.dashcam.drive.DriveApi
import me.ri3d.dashcam.drive.DriveError
import me.ri3d.dashcam.drive.DriveFile
import me.ri3d.dashcam.drive.DriveQuota
import me.ri3d.dashcam.drive.FakeDriveAuth
import me.ri3d.dashcam.drive.format.DriveFormat
import me.ri3d.dashcam.drive.format.DriveSidecar
import me.ri3d.dashcam.media.BackupState
import me.ri3d.dashcam.media.DownloadQueue
import me.ri3d.dashcam.media.MediaDownloader
import me.ri3d.dashcam.media.MediaItem
import me.ri3d.dashcam.media.MediaRepository
import me.ri3d.dashcam.media.RecorderHttp
import me.ri3d.dashcam.media.recorderFile
import me.ri3d.dashcam.plates.PlateExport
import me.ri3d.dashcam.plates.PlateRepository
import me.ri3d.dashcam.recorder.RecorderSimulator
import java.io.File
import java.security.MessageDigest
import java.time.Instant

fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

/** The stored session of [id] for the current account. */
fun BackupStore.currentSession(id: String): String? = session(id, account.orEmpty())

/** Drive in memory: files with appProperties, sidecar contents, scripted upload failures. */
class FakeDriveApi : DriveApi {
    val files = mutableListOf<DriveFile>()
    val json = mutableMapOf<String, String>()
    val deleted = mutableListOf<String>()

    /** The `sessionUri` each upload was called with. */
    val sessions = mutableListOf<String?>()

    /** Each upload takes the next failure (after it started its session). */
    val uploadFailures = ArrayDeque<() -> Throwable>()

    /** Each JSON write runs the next hook first; a returned error fails the write. */
    val writeHooks = ArrayDeque<() -> Throwable?>()

    /** Each delete runs the next hook first; a returned error fails the delete. */
    val deleteHooks = ArrayDeque<() -> Throwable?>()

    /** Drive reports another md5Checksum than the bytes sent. */
    var corrupt = false
    private var next = 0

    private fun newId(prefix: String) = "$prefix${++next}"

    override suspend fun ensureRootFolder() = Result.success("root")

    override suspend fun ensureMonthFolder(rootId: String, month: String) = Result.success("month-$month")

    override suspend fun uploadResumable(
        file: File, name: String, mime: String, parentId: String, appProperties: Map<String, String>,
        sessionUri: String?, onSessionUri: (String) -> Unit, onProgress: (Long, Long) -> Unit,
    ): Result<DriveFile> {
        sessions += sessionUri
        if (sessionUri == null) onSessionUri("https://upload.example/session-${++next}")
        uploadFailures.removeFirstOrNull()?.let { return Result.failure(it()) }
        onProgress(file.length(), file.length())
        val md5 = if (corrupt) "0".repeat(32) else DriveFormat.md5Hex(file)
        return Result.success(driveFile(newId("media"), name, appProperties, parentId, md5).also { files += it })
    }

    override suspend fun writeJson(name: String, parentId: String, json: String, appProperties: Map<String, String>): Result<DriveFile> {
        writeHooks.removeFirstOrNull()?.invoke()?.let { return Result.failure(it) }
        val file = files.firstOrNull { it.name == name && parentId in it.parents }
            ?: driveFile(newId("json"), name, appProperties, parentId, null).also { files += it }
        this.json[file.id] = json
        return Result.success(file)
    }

    override suspend fun list(query: String): Result<List<DriveFile>> {
        listCalls++
        val id = Regex("key='mf.id' and value='([^']*)'").find(query)?.groupValues?.get(1)
        return Result.success(files.filter { if (id != null) it.appProperties[DriveFormat.KEY_ID] == id else it.appProperties[DriveFormat.KEY_FORMAT] == "1" })
    }

    override suspend fun delete(id: String): Result<Unit> {
        deleteHooks.removeFirstOrNull()?.invoke()?.let { return Result.failure(it) }
        deleted += id
        files.removeAll { it.id == id }
        return Result.success(Unit)
    }

    override suspend fun about() = Result.success(DriveQuota(limit = 100, usage = 100))

    /** Content of media files by Drive id, for [download]. */
    val contents = mutableMapOf<String, ByteArray>()

    /** Each sidecar read runs the next hook first; a returned error fails the read. */
    val readHooks = ArrayDeque<() -> Throwable?>()
    var listCalls = 0

    // The import reads sidecars in parallel.
    override suspend fun readJson(id: String): Result<String> = synchronized(this) {
        readHooks.removeFirstOrNull()?.invoke()?.let { return Result.failure(it) }
        json[id]?.let { Result.success(it) } ?: Result.failure(DriveError.Http(404, "notFound"))
    }

    override suspend fun download(id: String, target: File, onProgress: (Long, Long?) -> Unit): Result<Unit> {
        val bytes = contents[id] ?: return Result.failure(DriveError.Http(404, "notFound"))
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return Result.success(Unit)
    }

    /**
     * A complete backup as an earlier installation left it: media file (with [content], its md5 and a thumbnail link)
     * and the sidecar [sidecar] (its md5 set to the content's unless [sidecarJson] is given). Returns the media file.
     */
    fun backup(
        sidecar: DriveSidecar,
        content: ByteArray = ByteArray(100) { it.toByte() },
        createdTime: Instant = Instant.parse("2026-10-01T10:00:00Z"),
        sidecarJson: String? = null,
    ): DriveFile {
        val md5 = md5(content)
        val props = DriveFormat.mediaAppProperties(sidecar.id, sidecar.kind, sidecar.category, sidecar.parent?.id)
        val media = driveFile(newId("media"), "${sidecar.id}.mp4", props, "month", md5, createdTime)
            .copy(thumbnailLink = "https://lh3.googleusercontent.com/t/${sidecar.id}")
        files += media
        contents[media.id] = content
        val json = driveFile(newId("json"), "${sidecar.id}.json", DriveFormat.sidecarAppProperties(sidecar.id), "month", null, createdTime)
        files += json
        this.json[json.id] = sidecarJson ?: sidecar.copy(md5 = md5, sizeBytes = content.size.toLong()).toJson()
        return media
    }

    /** [createdTime] defaults to now (later files are newer). */
    fun driveFile(
        id: String,
        name: String,
        appProperties: Map<String, String>,
        parent: String,
        md5: String?,
        createdTime: Instant = Instant.now().plusMillis((++next).toLong()),
    ) = DriveFile(id, name, md5Checksum = md5, appProperties = appProperties, parents = listOf(parent), createdTime = createdTime.toString())

    fun of(mediaId: String, role: String) = files.filter { it.appProperties[DriveFormat.KEY_ID] == mediaId && it.appProperties[DriveFormat.KEY_ROLE] == role }

    fun sidecar(mediaId: String): DriveSidecar = DriveSidecar.fromJson(json.getValue(of(mediaId, DriveFormat.ROLE_SIDECAR).single().id))
}

/** Everything [DriveBackup] needs, on an in-memory database and DataStore. */
class BackupFixture(val context: Context, val db: AppDatabase, scope: TestScope, dataStore: File) {
    val api = FakeDriveApi()
    val auth = FakeDriveAuth("token")
    val manager = scope.managerFor(RecorderSimulator()).apply { setSimulator(true) }
    val repository = MediaRepository(context, db, manager)
    val preferences = PreferencesRepository(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { dataStore })
    val store = BackupStore(context)
    val backup = DriveBackup(api, auth, repository, preferences, PlateExport(db.plateDao(), preferences), store)
    val http = RecorderHttp(manager, "http://127.0.0.1:1", context)
    val downloader = MediaDownloader(repository, http)

    /** Needs WorkManager (test driver) initialised: the import asks the download queue about running downloads. */
    val downloads by lazy { DownloadQueue(context, repository, downloader, manager) }
    val restore by lazy { DriveRestore(api, auth, repository, backup, store, preferences, PlateRepository(context, db.plateDao())) { downloads } }

    /** A downloaded recording of recorder [type] with [bytes] on the phone. */
    suspend fun local(path: String, type: Int = 1, bytes: ByteArray = ByteArray(1000) { it.toByte() }): MediaItem {
        repository.upsertFromRecorderListing(type, listOf(recorderFile(path)))
        val item = db.mediaDao().byRecorderPath(path)!!
        val file = File(repository.mediaDir, "${item.id}/${path.substringAfterLast('/')}").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
        return repository.markDownloaded(item.id, file)!!
    }

    suspend fun queued(path: String, type: Int = 1): MediaItem =
        local(path, type).let { item -> repository.update(item.id) { it.copy(backupState = BackupState.QUEUED) }!! }

    suspend fun item(id: String) = repository.get(id)

    fun clear() {
        context.getSharedPreferences("backup_queue", Context.MODE_PRIVATE).edit().clear().commit()
        listOf("media", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }
}
