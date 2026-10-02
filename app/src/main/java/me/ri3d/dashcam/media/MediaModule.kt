package me.ri3d.dashcam.media

import android.content.Context
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.network.CacheStrategy
import coil3.network.ConnectivityChecker
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.serviceLoaderEnabled
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toOkioPath
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import me.ri3d.dashcam.drive.DriveAuth
import me.ri3d.dashcam.drive.DriveAuthInterceptor
import me.ri3d.dashcam.drive.DriveHttp
import me.ri3d.dashcam.drive.driveImageClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton

/** No Ready session on the bound recorder Wi-Fi: nothing is requested; downloads wait without using up attempts. */
class RecorderNotReadyException : IOException("no ready recorder session")

/**
 * Recorder media over HTTP: the client bound to the recorder Wi-Fi and the file URL. In the debug simulator mode
 * the files come from the desktop simulator's HTTP server ([simulatorBaseUrl], `:recorder:runSimulator`).
 */
class RecorderHttp(
    private val manager: RecorderConnectionManager,
    private val simulatorBaseUrl: String,
    context: Context,
    /** Drive images ([driveImageClient]: internet, token only for Google's hosts); null = none load. */
    private val driveImages: Call.Factory? = null,
) {
    /**
     * The bound client, only while a session is Ready on the network that client is bound to (or in simulator mode).
     * Any other device answering at 192.168.42.1 on some Wi-Fi is never asked. Throws [RecorderNotReadyException]
     * (or `RecorderNotBoundException`); never falls back to mobile data.
     */
    fun client(): OkHttpClient {
        val state = manager.state.value
        if (state !is RecorderConnectionState.Ready) throw RecorderNotReadyException()
        if (!manager.simulator.value && state.network != manager.recorderNetwork.value) throw RecorderNotReadyException()
        return manager.httpClient()
    }

    @Volatile private var thumbnails: Pair<OkHttpClient, OkHttpClient>? = null

    /**
     * [client] with a shorter read timeout for thumbnails (3–6 KB): it shares the client's one-request dispatcher, so
     * a thumbnail the recorder never answers must not hold back the others for the 90 s a download may wait.
     */
    private fun thumbnailClient(): OkHttpClient {
        val base = client()
        return thumbnails?.takeIf { it.first === base }?.second
            ?: base.newBuilder().readTimeout(MediaModule.THUMBNAIL_READ_TIMEOUT_S, TimeUnit.SECONDS).build().also { thumbnails = base to it }
    }

    fun url(recorderPath: String): String =
        if (manager.simulator.value) simulatorBaseUrl.trimEnd('/') + "/" + recorderPath.trimStart('/') else manager.mediaUrl(recorderPath)

    /** A line in the Diagnose export next to the HTTP request log. */
    fun note(message: String) = manager.note(RecorderConnectionManagerImpl.HTTP_NOTES, message)

    /** True while no recorder HTTP request runs or waits: the thumbnail prefetch only asks then (lowest priority). */
    fun idle(): Boolean = runCatching { client().dispatcher.run { runningCallsCount() + queuedCallsCount() == 0 } }.getOrDefault(false)

    /**
     * Thumbnails from the recorder. Every request asks [client] at call time, so a new or lost session applies at
     * once and without a Ready session the placeholder stays. Coil's own connectivity check is off: the recorder
     * Wi-Fi has no internet. No service-loaded fetchers: nothing may load recorder URLs unbound. Coil decodes by
     * content, so the recorder's `.thm` thumbnails (served as `application/binary`) load whatever their extension or
     * Content-Type; [ThmDecoderFactory] also finds a JPEG behind a header. Rows use the local thumbnail instead once
     * the file is on the phone. Requests come from [RecorderThumb]: stable cache keys, and no network while a download
     * runs or without a session (then only the disk cache answers).
     *
     * Disk cache: `cacheDir/recorder_thumbs`, LRU, [MediaModule.THUMB_CACHE_BYTES]; [KeepThumbnails] stores every 2xx
     * answer and always uses a stored one, whatever the recorder's headers say.
     *
     * Drive thumbnails and photos (feature/drive-restore) use the same loader and cache: requests for Google's hosts go
     * to [driveImages] over the internet, never to the recorder client; everything else stays recorder-bound.
     */
    @OptIn(ExperimentalCoilApi::class)
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .serviceLoaderEnabled(false)
            .diskCache { thumbnailDiskCache(context.cacheDir.resolve(MediaModule.THUMB_CACHE_DIR)) }
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            Call.Factory { request ->
                                if (DriveAuthInterceptor.isGoogleHost(request.url.host)) {
                                    (driveImages ?: throw IOException("no Drive image client")).newCall(request)
                                } else {
                                    thumbnailClient().newCall(request)
                                }
                            }
                        },
                        cacheStrategy = { KeepThumbnails },
                        connectivityChecker = { ConnectivityChecker.ONLINE },
                    ),
                )
                add(ThmDecoderFactory(::note))
            }
            .build()
    }

    /** The recorder thumbnail of [item] ([network]: may ask the recorder); null without one. */
    fun thumb(item: MediaItem, network: Boolean): RecorderThumb? {
        val path = item.recorderPath ?: return null
        val thm = item.recorderThumbPath ?: return null
        return RecorderThumb(thumbKey(path, item.recorderTime), url(thm), network)
    }
}

/** Thumbnail disk cache in [dir]: Coil's DiskLruCache, least recently used entries go beyond [maxBytes]. */
fun thumbnailDiskCache(dir: File, maxBytes: Long = MediaModule.THUMB_CACHE_BYTES): DiskCache =
    DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(maxBytes).build()

/**
 * Disk and memory cache key of a recorder thumbnail: the recording's path and recorder time, so a path the recorder
 * reuses for another recording (format, clock reset) gets its own entry, and the URL (recorder or simulator) does not
 * matter.
 */
fun thumbKey(recorderPath: String, recorderTime: String?): String = "thm:$recorderPath@${recorderTime.orEmpty()}"

/**
 * A recorder thumbnail for [MediaThumb] and the prefetch (also a Drive thumbnail, key `drive-thumb:<fileId>`, see
 * `DriveRestore.thumbnail`). [network] false: only the caches answer (no session, or a download runs: downloads
 * first); a changed flag is a new model, so the image loads once the network is allowed.
 */
data class RecorderThumb(val key: String, val url: String, val network: Boolean) {
    fun request(context: Context, memory: Boolean = true): ImageRequest = ImageRequest.Builder(context)
        .data(url)
        .diskCacheKey(key)
        .memoryCacheKey(key)
        .placeholderMemoryCacheKey(key)
        .networkCachePolicy(if (network) CachePolicy.ENABLED else CachePolicy.DISABLED)
        .memoryCachePolicy(if (memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
        .build()
}

/**
 * The recorder's HTTP headers are not established (hardware 2026-10-02: `application/binary`, nothing about caching):
 * a stored thumbnail is always used and every 2xx answer stored; errors (404 …) are not, so they are asked again later.
 */
@OptIn(ExperimentalCoilApi::class)
internal object KeepThumbnails : CacheStrategy {
    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options) =
        CacheStrategy.ReadResult(cacheResponse)

    override suspend fun write(cacheResponse: NetworkResponse?, networkRequest: NetworkRequest, networkResponse: NetworkResponse, options: Options) =
        if (networkResponse.code in 200..299) CacheStrategy.WriteResult(networkResponse) else CacheStrategy.WriteResult.DISABLED
}

/**
 * Recorder `.thm` thumbnails (hardware 2026-10-02: `application/binary`, 3–6 KB, contents not yet seen). A body that
 * starts as a JPEG goes to Coil's own decoders; one with a JPEG after some header (a container) is decoded from its
 * SOI marker; one without any JPEG is reported once to [note] with its first bytes and left to Coil (another image
 * format still decodes, anything else shows the placeholder).
 */
internal class ThmDecoderFactory(private val note: (String) -> Unit) : Decoder.Factory {
    private val reported = AtomicBoolean(false)

    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val source = result.source.source()
        if (source.peek().rangeEquals(0, JPEG_SOI) || source.peek().request(MAX_THM_BYTES + 1)) return null
        val bytes = source.peek().readByteArray()
        val at = jpegStart(bytes)
        if (at < 0) {
            if (reported.compareAndSet(false, true)) {
                note("thumbnail: no JPEG in ${bytes.size} bytes (${result.mimeType}), starts ${bytes.take(16).joinToString(" ") { "%02x".format(it) }}")
            }
            return null
        }
        return object : Decoder {
            override suspend fun decode(): DecodeResult {
                val bitmap = BitmapFactory.decodeByteArray(bytes, at, bytes.size - at) ?: throw IOException("thumbnail: JPEG at $at not decodable")
                return DecodeResult(bitmap.asImage(), isSampled = false)
            }
        }
    }

    companion object {
        private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()).toByteString()
        private const val MAX_THM_BYTES = 256L * 1024

        /** Offset of the first JPEG start marker (FF D8 FF), -1 if none. */
        fun jpegStart(bytes: ByteArray): Int =
            (0..bytes.size - 3).firstOrNull { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xD8.toByte() && bytes[it + 2] == 0xFF.toByte() } ?: -1
    }
}

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    // ponytail: thumbnail read timeout, a guess; tune if recorder thumbnails time out while nothing else runs.
    const val THUMBNAIL_READ_TIMEOUT_S = 15L

    /** Recorder thumbnails on disk: ~64 MB LRU ≈ 13,000 `.thm` of 3–6 KB, more than a full card lists. */
    const val THUMB_CACHE_DIR = "recorder_thumbs"
    const val THUMB_CACHE_BYTES = 64L * 1024 * 1024

    /** `:recorder:runSimulator` serves the simulated files here (emulator alias of the development machine). */
    const val SIMULATOR_BASE_URL = "http://10.0.2.2:8080"

    @Provides
    @Singleton
    fun recorderHttp(manager: RecorderConnectionManager, @ApplicationContext context: Context, @DriveHttp drive: OkHttpClient, auth: DriveAuth) =
        RecorderHttp(manager, SIMULATOR_BASE_URL, context, driveImageClient(drive, auth))
}
