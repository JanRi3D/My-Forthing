package me.ri3d.dashcam.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import me.ri3d.dashcam.core.branding.Branding
import me.ri3d.dashcam.drive.format.DriveFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** [DriveApi] on Drive REST v3 over OkHttp (no google-api-client). */
class DriveRestApi(
    private val http: OkHttpClient,
    private val auth: DriveAuth,
    baseUrl: HttpUrl = GOOGLE_APIS,
    /** Must be a multiple of 256 KiB (Drive requirement for every chunk but the last). */
    private val chunkSize: Long = 8L * 1024 * 1024,
    private val maxAttempts: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
) : DriveApi {
    private val filesUrl = baseUrl.resolve("drive/v3/files")!!
    private val uploadUrl = baseUrl.resolve("upload/drive/v3/files")!!
    private val aboutUrl = baseUrl.resolve("drive/v3/about")!!

    override suspend fun ensureRootFolder(): Result<String> = drive {
        val roots = listAll(DriveFormat.ROOT_FOLDER_QUERY).sortedBy { it.createdTime }
        val rootId = (roots.firstOrNull { it.name == Branding.driveRootFolderName } ?: roots.firstOrNull())?.id
            ?: createFolder(Branding.driveRootFolderName, parentId = null, DriveFormat.ROLE_ROOT)
        // Also repairs a root whose manifest write was interrupted.
        if (listAll(DriveFormat.childQuery(DriveFormat.MANIFEST_NAME, rootId, folder = false)).isEmpty()) {
            putJson(
                DriveFormat.MANIFEST_NAME, rootId, DriveFormat.manifestJson(clock()),
                DriveFormat.roleAppProperties(DriveFormat.ROLE_MANIFEST), existingId = null,
            )
        }
        rootId
    }

    // ponytail: no folder cache and no lock; two parallel uploads may create the same month folder twice
    // (harmless for discovery, which ignores folders). The backup queue runs uploads serially.
    override suspend fun ensureMonthFolder(rootId: String, month: String): Result<String> = drive {
        ensureFolder(month, ensureFolder(DriveFormat.MEDIA_FOLDER_NAME, rootId))
    }

    override suspend fun uploadResumable(
        file: File,
        name: String,
        mime: String,
        parentId: String,
        appProperties: Map<String, String>,
        sessionUri: String?,
        onSessionUri: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): Result<DriveFile> = drive { upload(file, name, mime, parentId, appProperties, sessionUri, onSessionUri, onProgress) }

    override suspend fun writeJson(name: String, parentId: String, json: String, appProperties: Map<String, String>): Result<DriveFile> =
        drive {
            val existing = listAll(DriveFormat.childQuery(name, parentId, folder = false)).minByOrNull { it.createdTime.orEmpty() }
            putJson(name, parentId, json, appProperties, existing?.id)
        }

    override suspend fun list(query: String): Result<List<DriveFile>> = drive { listAll(query) }

    override suspend fun delete(id: String): Result<Unit> = drive {
        val reply = send { url(filesUrl.newBuilder().addPathSegment(id).build()).delete() }
        if (!reply.isSuccessful && reply.code != 404) throw reply.error()
    }

    override suspend fun about(): Result<DriveQuota> = drive {
        val url = aboutUrl.newBuilder().addQueryParameter("fields", "storageQuota(limit,usage)").build()
        val quota = driveJson.decodeFromString<About>(send { url(url) }.successBody()).storageQuota
        DriveQuota(quota.limit, quota.usage)
    }

    override suspend fun readJson(id: String): Result<String> = drive { send { url(mediaUrl(id)) }.successBody() }

    override suspend fun download(id: String, target: File, onProgress: (Long, Long?) -> Unit): Result<Unit> = drive {
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
        var restarted = false
        while (true) {
            val offset = if (part.isFile) part.length() else 0L
            val complete = exchange(
                retryTransient = true,
                build = { url(mediaUrl(id)).apply { if (offset > 0) header("Range", "bytes=$offset-") } },
                onError = { throw it.error() },
            ) { response ->
                when {
                    // The part does not fit what Drive offers now (e.g. a longer part, or a range answer elsewhere).
                    response.code == 416 || response.code == 206 && rangeStart(response.header("Content-Range")) != offset -> false
                    !response.isSuccessful -> throw DriveError.Http(response.code, driveErrorReason(response.body.string()))
                    else -> {
                        receive(response, part, if (response.code == 206) offset else 0L, onProgress)
                        true
                    }
                }
            }
            if (complete) break
            if (restarted) throw DriveError.Http(416, "range does not continue the part")
            restarted = true
            part.delete()
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun mediaUrl(id: String): HttpUrl = filesUrl.newBuilder().addPathSegment(id).addQueryParameter("alt", "media").build()

    /** Writes the body to [part] from byte [from] (appending to the part, or replacing it from 0). */
    private fun receive(response: Response, part: File, from: Long, onProgress: (Long, Long?) -> Unit) {
        val length = response.body.contentLength().takeIf { it >= 0 }
        val total = if (response.code == 206) response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: length?.plus(from) else length
        var done = from
        FileOutputStream(part, from > 0).use { out ->
            val source = response.body.byteStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                done += n
                onProgress(done, total)
            }
            out.fd.sync()
        }
        // Kept for the resume: the next call asks for the rest.
        if (total != null && done != total) throw IOException("incomplete: $done of $total bytes")
    }

    private suspend fun upload(
        file: File,
        name: String,
        mime: String,
        parentId: String,
        appProperties: Map<String, String>,
        sessionUri: String?,
        onSessionUri: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): DriveFile {
        val total = file.length()
        val type = mime.toMediaType()
        var session = sessionUri
        var offset: Long? = null // null: ask the session how much it already has
        var failures = 0
        var restarted = false
        while (true) {
            val uri = session ?: startSession(file, name, mime, parentId, appProperties).also {
                session = it
                onSessionUri(it)
                offset = 0
            }
            val start = offset
            val reply = if (start == null || start >= total) {
                send(retryTransient = false) { url(uri).header("Content-Range", "bytes */$total").put(EMPTY_BODY) }
            } else {
                val end = minOf(start + chunkSize, total)
                val body = FileChunkBody(file, start, end - start, type) { onProgress(start + it, total) }
                send(retryTransient = false) { url(uri).header("Content-Range", "bytes $start-${end - 1}/$total").put(body) }
            }
            when {
                reply.isSuccessful -> {
                    val uploaded = driveJson.decodeFromString<DriveFile>(reply.body)
                    onProgress(total, total)
                    // The final response may omit requested fields; md5Checksum is needed for verification.
                    return if (uploaded.md5Checksum != null || total == 0L) uploaded else getFile(uploaded.id)
                }
                reply.code == RESUME_INCOMPLETE -> {
                    val next = reply.nextOffset()
                    when {
                        start == null -> Unit // a status answer neither proves nor disproves progress
                        next > start -> failures = 0 // the chunk (or part of it) arrived
                        else -> {
                            if (++failures >= maxAttempts) throw DriveError.Http(reply.code, "upload makes no progress")
                            delay(backoffMs(failures))
                        }
                    }
                    offset = next
                    onProgress(next, total)
                }
                reply.code == 404 || reply.code == 410 -> {
                    // Upload sessions expire after about a week: start over, once.
                    if (restarted) throw reply.error()
                    restarted = true
                    session = null
                }
                reply.isTransient -> {
                    if (++failures >= maxAttempts) throw reply.error()
                    delay(backoffMs(failures))
                    offset = null // Drive may have kept part of the chunk
                }
                else -> throw reply.error()
            }
        }
    }

    private suspend fun listAll(query: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = filesUrl.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("spaces", "drive")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = driveJson.decodeFromString<FileList>(send { url(url) }.successBody())
            files += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return files
    }

    private suspend fun getFile(id: String): DriveFile {
        val url = filesUrl.newBuilder().addPathSegment(id).addQueryParameter("fields", FILE_FIELDS).build()
        return driveJson.decodeFromString(send { url(url) }.successBody())
    }

    private suspend fun ensureFolder(name: String, parentId: String): String =
        listAll(DriveFormat.childQuery(name, parentId, folder = true)).minByOrNull { it.createdTime.orEmpty() }?.id
            ?: createFolder(name, parentId, DriveFormat.ROLE_FOLDER)

    private suspend fun createFolder(name: String, parentId: String?, role: String): String {
        val metadata = metadata(name, DriveFormat.FOLDER_MIME, parentId, DriveFormat.roleAppProperties(role))
        val url = filesUrl.newBuilder().addQueryParameter("fields", FILE_FIELDS).build()
        return driveJson.decodeFromString<DriveFile>(send { url(url).post(metadata.toString().toRequestBody(JSON)) }.successBody()).id
    }

    /** Multipart create in [parentId], or content + appProperties update of [existingId]. */
    private suspend fun putJson(name: String, parentId: String, json: String, appProperties: Map<String, String>, existingId: String?): DriveFile {
        // On update the parents field is not writable (it would need addParents), so it is left out.
        val metadata = metadata(name, DriveFormat.JSON_MIME, parentId.takeIf { existingId == null }, appProperties)
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toString().toRequestBody(JSON))
            .addPart(json.toRequestBody(JSON))
            .build()
        val url = uploadUrl.newBuilder()
            .apply { if (existingId != null) addPathSegment(existingId) }
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        return driveJson.decodeFromString(send { url(url).method(if (existingId == null) "POST" else "PATCH", body) }.successBody())
    }

    private suspend fun startSession(file: File, name: String, mime: String, parentId: String, appProperties: Map<String, String>): String {
        val url = uploadUrl.newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val reply = send {
            url(url)
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", file.length().toString())
                .post(metadata(name, mime, parentId, appProperties).toString().toRequestBody(JSON))
        }
        if (!reply.isSuccessful) throw reply.error()
        return reply.headers["Location"] ?: throw DriveError.Http(reply.code, "no upload session")
    }

    /**
     * One authorised request: a 401 refreshes the token once, a second 401 means [DriveError.NeedsReconnect]; a full
     * Drive is [DriveError.InsufficientStorage]; with [retryTransient] 429/5xx/rate limits are retried with backoff.
     * Every other status goes back to the caller.
     */
    private suspend fun send(retryTransient: Boolean = true, build: Request.Builder.() -> Unit): Reply =
        exchange(retryTransient, build, onError = { it }) { Reply(it.code, it.body.string(), it.headers) }

    /**
     * [send] for any body: [read] gets the open response (it may stream it) unless the status is one handled here
     * (401, 403, 429, 5xx); such a status that is not retried goes to [onError] as a read [Reply].
     */
    private suspend fun <T> exchange(
        retryTransient: Boolean,
        build: Request.Builder.() -> Unit,
        onError: (Reply) -> T,
        read: (Response) -> T,
    ): T {
        var refreshed = false
        var attempt = 0
        // Bounded: one token refresh plus maxAttempts - 1 transient retries.
        repeat(maxAttempts + 1) {
            val token = auth.accessToken().getOrThrow()
            val request = Request.Builder().apply(build).header("Authorization", "Bearer $token").build()
            val outcome = try {
                http.newCall(request).await { response ->
                    if (response.code == 401 || response.code == 403 || response.code == 429 || response.code >= 500) {
                        Outcome.Status(Reply(response.code, response.body.string(), response.headers))
                    } else {
                        Outcome.Read(read(response))
                    }
                }
            } catch (e: IOException) {
                throw DriveError.Offline(e)
            }
            val reply = when (outcome) {
                is Outcome.Read -> return outcome.value
                is Outcome.Status -> outcome.reply
            }
            when {
                reply.code == 401 && !refreshed -> {
                    refreshed = true
                    auth.invalidate(token)
                }
                reply.code == 401 -> {
                    auth.invalidate(token, revoked = true)
                    throw DriveError.NeedsReconnect()
                }
                reply.code == 403 && reply.reason == "storageQuotaExceeded" -> throw DriveError.InsufficientStorage()
                retryTransient && reply.isTransient && ++attempt < maxAttempts -> delay(backoffMs(attempt))
                else -> return onError(reply)
            }
        }
        throw DriveError.Http(-1, "too many attempts")
    }

    /** 1 s, 2 s, 4 s, 8 s, 16 s plus up to 0.5 s jitter. */
    private fun backoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 4)) + Random.nextLong(500)

    private fun metadata(name: String, mime: String, parentId: String?, appProperties: Map<String, String>): JsonObject =
        buildJsonObject {
            put("name", name)
            put("mimeType", mime)
            if (parentId != null) putJsonArray("parents") { add(parentId) }
            putJsonObject("appProperties") { appProperties.forEach { (key, value) -> put(key, value) } }
        }

    /** Streams one chunk straight from the file; one-shot, so OkHttp never re-sends it on its own. */
    private class FileChunkBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val type: MediaType,
        private val onWritten: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (written < length) {
                    val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                    if (n < 0) throw IOException("File shrank during upload")
                    sink.write(buffer, 0, n)
                    written += n
                    onWritten(written)
                }
            }
        }
    }

    @Serializable
    private class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

    @Serializable
    private class About(val storageQuota: Quota = Quota()) {
        @Serializable
        class Quota(val limit: Long? = null, val usage: Long = 0)
    }

    companion object {
        val GOOGLE_APIS = "https://www.googleapis.com/".toHttpUrl()

        /** Content URL of a Drive file (`files/<id>?alt=media`); images load it through [driveImageClient]. */
        fun contentUrl(id: String): String =
            GOOGLE_APIS.resolve("drive/v3/files")!!.newBuilder().addPathSegment(id).addQueryParameter("alt", "media").build().toString()
        private const val RESUME_INCOMPLETE = 308
        private const val FILE_FIELDS = "id,name,mimeType,size,md5Checksum,appProperties,parents,createdTime,modifiedTime,thumbnailLink"

        /** `bytes <start>-<end>/<total>` → start. */
        private fun rangeStart(contentRange: String?): Long? =
            contentRange?.let { Regex("""bytes\s+(\d+)-\d+/""").find(it) }?.groupValues?.get(1)?.toLongOrNull()
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val MULTIPART_RELATED = "multipart/related".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody()
    }
}

/** A fully read HTTP response (Drive answers are small JSON). */
private class Reply(val code: Int, val body: String, val headers: Headers) {
    val isSuccessful get() = code in 200..299

    val reason: String? by lazy { driveErrorReason(body) }

    val isTransient get() = code == 429 || code >= 500 || (code == 403 && reason in RATE_LIMIT_REASONS)

    fun successBody(): String = if (isSuccessful) body else throw error()

    fun error() = DriveError.Http(code, reason)

    /** From a 308's `Range: bytes=0-<last>` header; no header = nothing stored yet. */
    fun nextOffset(): Long = headers["Range"]?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L

    private companion object {
        val RATE_LIMIT_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded")
    }
}

/** First `error.errors[].reason` of a Drive error body, e.g. `storageQuotaExceeded` or `accessNotConfigured`. */
internal fun driveErrorReason(body: String): String? = runCatching {
    driveJson.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["errors"]!!.jsonArray[0]
        .jsonObject["reason"]!!.jsonPrimitive.content
}.getOrNull()

/** Runs [block]; [DriveError]s and parse errors become `Result.failure`, cancellation propagates. */
private suspend fun <T> drive(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** A response [DriveRestApi] handles itself ([Status]) or one its caller read ([Read]). */
private sealed interface Outcome<out T> {
    class Read<T>(val value: T) : Outcome<T>

    class Status(val reply: Reply) : Outcome<Nothing>
}

/** Enqueues the call so that coroutine cancellation cancels the HTTP exchange; [read] runs on OkHttp's thread. */
private suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
            runCatching { response.use(read) }
                .onSuccess { continuation.resume(it) }
                .onFailure { continuation.resumeWithException(it) }
        }
    })
}
