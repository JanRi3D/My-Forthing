package me.ri3d.cam.live

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import me.ri3d.cam.core.branding.Branding
import me.ri3d.cam.core.log.Log
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Folder contract with feature/media (docs/features/live.md): `filesDir/screenshots/<id>.jpg` plus
 * `<id>.json` `{ "id", "capturedAt", "source": "live", "width", "height" }`. Both files appear by atomic rename,
 * the JSON last: a JSON means its JPEG is complete.
 */
const val SCREENSHOT_DIR = "screenshots"

data class Screenshot(val id: String, val file: File, val inGallery: Boolean)

/** Blocking (call off the main thread). Writes the contract files, then a copy into Pictures/<app name>. */
fun saveScreenshot(
    context: Context,
    bitmap: Bitmap,
    now: ZonedDateTime = ZonedDateTime.now(),
    id: String = UUID.randomUUID().toString(),
): Screenshot {
    val at = now.truncatedTo(ChronoUnit.SECONDS)
    val dir = File(context.filesDir, SCREENSHOT_DIR).apply { mkdirs() }
    val jpg = File(dir, "$id.jpg")
    val json = File(dir, "$id.json")
    val tmp = File(dir, "$id.jpg.tmp")
    val jsonTmp = File(dir, "$id.json.tmp")
    try {
        tmp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) throw IOException("JPEG encode failed") }
        ExifInterface(tmp).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, at.format(EXIF_TIME))
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, at.format(EXIF_OFFSET))
            saveAttributes()
        }
        jsonTmp.writeText(
            JSONObject()
                .put("id", id)
                .put("capturedAt", at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .put("source", "live")
                .put("width", bitmap.width)
                .put("height", bitmap.height)
                .toString(),
        )
        move(tmp, jpg)
        move(jsonTmp, json)
    } catch (e: IOException) {
        jpg.delete()
        json.delete()
        throw e
    } finally {
        tmp.delete()
        jsonTmp.delete()
    }
    return Screenshot(id, jpg, copyToGallery(context, jpg, at))
}

private fun move(from: File, to: File) {
    if (!from.renameTo(to)) throw IOException("rename failed: ${to.name}")
}

/** Pictures/<app name> via MediaStore, so the screenshot shows up in the gallery. False if that did not work. */
private fun copyToGallery(context: Context, jpg: File, at: ZonedDateTime): Boolean {
    // ponytail: Android 8–9 need WRITE_EXTERNAL_STORAGE plus a runtime prompt for this copy; there the screenshot
    // stays in the app (the snackbar says so). Add the permission flow if those devices matter.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Live_${at.format(FILE_TIME)}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${context.getString(Branding.appName)}")
        put(MediaStore.Images.Media.DATE_TAKEN, at.toInstant().toEpochMilli())
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    } catch (e: Exception) {
        null
    } ?: return false
    return try {
        val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream")
        out.use { stream -> jpg.inputStream().use { it.copyTo(stream) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (e: Exception) {
        Log.w(TAG, "gallery copy failed: ${e.javaClass.simpleName}")
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

private const val TAG = "LiveScreenshot"
private const val JPEG_QUALITY = 92
private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
private val EXIF_OFFSET = DateTimeFormatter.ofPattern("xxx")
private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
