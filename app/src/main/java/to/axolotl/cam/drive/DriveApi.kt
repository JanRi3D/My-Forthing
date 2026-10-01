package to.axolotl.cam.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Drive REST v3 (CONTRACTS §10). Every call authorises through [DriveAuth]; failures are [DriveError]s:
 * 401 → one token refresh, then [DriveError.NeedsReconnect]; 403 `storageQuotaExceeded` → [DriveError.InsufficientStorage];
 * 429 / 5xx / rate-limit 403 → bounded exponential backoff; network failures → [DriveError.Offline].
 */
interface DriveApi {
    /** Finds (or creates) the `Axolotl Cam` root folder including its `axolotlcam.json` manifest. Returns the folder id. */
    suspend fun ensureRootFolder(): Result<String>

    /** Finds (or creates) `media/<month>` below [rootId], e.g. month = `2026-10` (see `DriveFormat.monthFolderName`). */
    suspend fun ensureMonthFolder(rootId: String, month: String): Result<String>

    /**
     * Resumable upload in 8 MiB chunks. Pass a persisted [sessionUri] to resume after an interruption or restart;
     * [onSessionUri] receives a newly started session URI to persist. [onProgress] (bytes sent, total) is called on a
     * background thread. The returned file carries Drive's `md5Checksum` for verification.
     */
    suspend fun uploadResumable(
        file: File,
        name: String,
        mime: String,
        parentId: String,
        appProperties: Map<String, String>,
        sessionUri: String?,
        onSessionUri: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): Result<DriveFile>

    /** Creates [name] in [parentId], or replaces the content and appProperties of the existing file with that name. */
    suspend fun writeJson(name: String, parentId: String, json: String, appProperties: Map<String, String>): Result<DriveFile>

    /** All pages of `files.list` for the Drive query [query] (see `DriveFormat` for the format v1 queries). */
    suspend fun list(query: String): Result<List<DriveFile>>

    /** Deletes the file permanently (not to the trash, so the quota is freed). An already missing file counts as deleted. */
    suspend fun delete(id: String): Result<Unit>

    suspend fun about(): Result<DriveQuota>
}

@Serializable
data class DriveFile(
    val id: String,
    val name: String = "",
    val mimeType: String = "",
    val size: Long? = null,
    val md5Checksum: String? = null,
    val appProperties: Map<String, String> = emptyMap(),
    val parents: List<String> = emptyList(),
    val createdTime: String? = null,
    val modifiedTime: String? = null,
)

/** [limit] is null for unlimited storage. Bytes. */
data class DriveQuota(val limit: Long?, val usage: Long) {
    val free: Long? get() = limit?.let { (it - usage).coerceAtLeast(0) }
}

/** Drive responses and the encrypted account record; tolerant to new fields. */
internal val driveJson = Json { ignoreUnknownKeys = true }
