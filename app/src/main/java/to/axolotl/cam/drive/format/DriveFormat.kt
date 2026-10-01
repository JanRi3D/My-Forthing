package to.axolotl.cam.drive.format

import kotlinx.serialization.json.Json
import to.axolotl.cam.drive.DriveApi
import to.axolotl.cam.drive.DriveFile
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Drive format v1: names, appProperties and queries. Normative description for the web app: docs/DRIVE_FORMAT.md. */
object DriveFormat {
    const val VERSION = 1
    const val APP_ID = "to.axolotl.cam"
    const val MANIFEST_NAME = "axolotlcam.json"
    const val MEDIA_FOLDER_NAME = "media"
    const val FOLDER_MIME = "application/vnd.google-apps.folder"
    const val JSON_MIME = "application/json"

    const val KEY_FORMAT = "axo.format"
    const val KEY_ROLE = "axo.role"
    const val KEY_ID = "axo.id"
    const val KEY_KIND = "axo.kind"
    const val KEY_CATEGORY = "axo.category"
    const val KEY_PARENT = "axo.parent"

    const val ROLE_ROOT = "root"
    const val ROLE_MANIFEST = "manifest"
    const val ROLE_FOLDER = "folder"
    const val ROLE_MEDIA = "media"
    const val ROLE_SIDECAR = "sidecar"

    /** Every format v1 file and folder the app can see (drive.file: only what this Cloud project created). */
    const val DISCOVERY_QUERY = "appProperties has { key='$KEY_FORMAT' and value='$VERSION' } and trashed = false"

    /** Media files and sidecars only. */
    const val BACKUP_FILES_QUERY = "$DISCOVERY_QUERY and mimeType != '$FOLDER_MIME'"

    /** The root folder, whatever the user renamed it to. */
    const val ROOT_FOLDER_QUERY =
        "mimeType = '$FOLDER_MIME' and appProperties has { key='$KEY_ROLE' and value='$ROLE_ROOT' } and trashed = false"

    /** Sidecar JSON: every field always written, unknown fields from newer writers ignored. */
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /** `<mediaId>.<ext>`; the extension comes from the recorder file name (lower-cased), else from [mime]. */
    fun mediaFileName(mediaId: String, originalFileName: String, mime: String): String {
        val ext = originalFileName.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
            ?: when (mime) {
                "video/mp4" -> "mp4"
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                else -> "bin"
            }
        return "$mediaId.$ext"
    }

    fun sidecarFileName(mediaId: String): String = "$mediaId.json"

    fun mediaAppProperties(mediaId: String, kind: String, category: String, parentId: String?): Map<String, String> = buildMap {
        put(KEY_FORMAT, VERSION.toString())
        put(KEY_ROLE, ROLE_MEDIA)
        put(KEY_ID, mediaId)
        put(KEY_KIND, kind)
        put(KEY_CATEGORY, category)
        if (parentId != null) put(KEY_PARENT, parentId)
    }

    fun sidecarAppProperties(mediaId: String): Map<String, String> =
        mapOf(KEY_FORMAT to VERSION.toString(), KEY_ROLE to ROLE_SIDECAR, KEY_ID to mediaId)

    fun roleAppProperties(role: String): Map<String, String> = mapOf(KEY_FORMAT to VERSION.toString(), KEY_ROLE to role)

    /**
     * `yyyy-MM` month folder below `media/`: the recorder time guess, else [fallbackEpochMs] (download or creation
     * time). The guess was parsed in the phone's zone, so formatting in the same zone gives the recorder's wall clock.
     */
    fun monthFolderName(recorderTimeEpochGuess: Long?, fallbackEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        YearMonth.from(Instant.ofEpochMilli(recorderTimeEpochGuess ?: fallbackEpochMs).atZone(zone)).toString()

    /** ISO-8601 with offset, e.g. `2026-10-01T12:03:00+02:00`. */
    fun isoTimestamp(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun manifestJson(createdAtEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        json.encodeToString(DriveManifest(createdAt = isoTimestamp(createdAtEpochMs, zone)))

    /** The media file(s) of one item, for duplicate prevention before an upload. */
    fun mediaByIdQuery(mediaId: String): String =
        "appProperties has { key='$KEY_ID' and value='${escape(mediaId)}' } and " +
            "appProperties has { key='$KEY_ROLE' and value='$ROLE_MEDIA' } and trashed = false"

    /** Non-trashed children of [parentId] called [name]; [folder] restricts to folders (true) or files (false). */
    fun childQuery(name: String, parentId: String, folder: Boolean): String =
        "name = '${escape(name)}' and '${escape(parentId)}' in parents and " +
            "mimeType ${if (folder) "=" else "!="} '$FOLDER_MIME' and trashed = false"

    /** Escapes a value for a single-quoted Drive query literal. */
    fun escape(literal: String): String = literal.replace("\\", "\\\\").replace("'", "\\'")

    /** Lower-case hex MD5, compared with Drive's `md5Checksum` before the sidecar is written. */
    fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** One media id found on Drive. Complete = media file and sidecar exist (the sidecar is written only after verification). */
data class DriveBackupEntry(val mediaId: String, val media: DriveFile?, val sidecar: DriveFile?) {
    val complete: Boolean get() = media != null && sidecar != null
}

/** Lists format v1 backups and pairs media files with their sidecars (backup status, and the web app's algorithm). */
object DriveFormatReader {
    suspend fun scan(api: DriveApi): Result<List<DriveBackupEntry>> = api.list(DriveFormat.BACKUP_FILES_QUERY).map(::pair)

    /** Groups by `axo.id`; with duplicates the oldest file of each role wins. Entries are sorted by media id. */
    fun pair(files: List<DriveFile>): List<DriveBackupEntry> =
        files.filter { it.appProperties[DriveFormat.KEY_FORMAT] == DriveFormat.VERSION.toString() }
            .groupBy { it.appProperties[DriveFormat.KEY_ID] }
            .mapNotNull { (id, group) ->
                if (id == null) return@mapNotNull null
                val oldestFirst = group.sortedBy { it.createdTime }
                val media = oldestFirst.firstOrNull { it.role == DriveFormat.ROLE_MEDIA }
                val sidecar = oldestFirst.firstOrNull { it.role == DriveFormat.ROLE_SIDECAR }
                if (media == null && sidecar == null) null else DriveBackupEntry(id, media, sidecar)
            }
            .sortedBy { it.mediaId }

    /** `axo.role`; files from writers that omitted it count as media when they carry `axo.kind`. */
    private val DriveFile.role: String?
        get() = appProperties[DriveFormat.KEY_ROLE] ?: DriveFormat.ROLE_MEDIA.takeIf { DriveFormat.KEY_KIND in appProperties }
}
