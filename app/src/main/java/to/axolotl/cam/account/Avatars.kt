package to.axolotl.cam.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Longest side of a profile picture, locally and in Firebase Storage. */
internal const val AVATAR_MAX_PX = 512
private const val AVATAR_JPEG_QUALITY = 85

/** Writes [src] to [dst] as an upright JPEG whose longest side is at most [AVATAR_MAX_PX]. */
internal fun writeSmallAvatar(src: File, dst: File) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src.path, bounds)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, AVATAR_MAX_PX) }
    val decoded = BitmapFactory.decodeFile(src.path, options) ?: throw IOException("Not a decodable image")
    val scale = min(1f, AVATAR_MAX_PX.toFloat() / max(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(ExifInterface(src).rotationDegrees.toFloat())
    }
    val small = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    dst.parentFile?.mkdirs()
    dst.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, it) }
}

/** Largest power-of-two subsampling that keeps the longest side at or above [maxPx] (exact scaling follows). */
internal fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
    var size = 1
    while (max(width, height) / (size * 2) >= maxPx) size *= 2
    return size
}
