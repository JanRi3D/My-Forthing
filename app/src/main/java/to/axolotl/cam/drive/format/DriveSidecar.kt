package to.axolotl.cam.drive.format

import kotlinx.serialization.Serializable

/**
 * Sidecar `<mediaId>.json`, Drive format v1 (CONTRACTS §10, docs/DRIVE_FORMAT.md). Written only after the media
 * upload is verified. Every field is always written (nulls explicitly), so web readers see a stable shape.
 * [kind] / [category] are the `MediaItem` enum names as strings, so unknown future values survive a round trip.
 */
@Serializable
data class DriveSidecar(
    val format: Int = DriveFormat.VERSION,
    val id: String,
    /** ORIGINAL_VIDEO, ORIGINAL_PHOTO, SCREENSHOT, ENHANCED_FRAME, UPSCALED_CLIP */
    val kind: String,
    /** NORMAL, EVENT, USER, UNKNOWN */
    val category: String,
    /** Raw recorder listing type (0 normal video, 1 event video, 2 user data such as photos); null for app-made files. */
    val recorderType: Int? = null,
    val originalFileName: String,
    val recorderPath: String? = null,
    /** Raw recorder wall-clock time "yyyy-MM-dd HH:mm:ss"; the recorder reports no time zone. */
    val recorderTime: String? = null,
    /** Stays null unless the recorder ever reports a zone. */
    val recorderTimeZone: String? = null,
    /** ISO-8601 with offset, e.g. 2026-10-01T12:03:00+02:00. */
    val downloadedAt: String? = null,
    val sizeBytes: Long,
    /** Lower-case hex MD5 of the uploaded bytes; equals Drive's `md5Checksum` of the media file. */
    val md5: String,
    val mime: String,
    val durationMs: Long? = null,
    /** Source of an ENHANCED_FRAME / UPSCALED_CLIP. */
    val parent: Parent? = null,
    /** Present only when the user enabled plate metadata in backups. */
    val plates: List<Plate>? = null,
    val backup: Backup,
) {
    @Serializable
    data class Parent(val id: String, val positionMs: Long? = null)

    @Serializable
    data class Plate(
        /** As displayed; unreadable characters are `?`. */
        val text: String,
        val normalized: String,
        val positionMs: Long? = null,
        /** Only when the recogniser returned one. */
        val confidence: Float? = null,
        /** [left, top, right, bottom] in pixels of the decoded frame; null when not recorded. */
        val box: List<Int>? = null,
    )

    @Serializable
    data class Backup(val complete: Boolean, val completedAt: String? = null)

    fun toJson(): String = DriveFormat.json.encodeToString(this)

    companion object {
        fun fromJson(text: String): DriveSidecar = DriveFormat.json.decodeFromString(text)
    }
}

/** `axolotlcam.json` in the root folder. */
@Serializable
data class DriveManifest(val format: Int = DriveFormat.VERSION, val app: String = DriveFormat.APP_ID, val createdAt: String)
