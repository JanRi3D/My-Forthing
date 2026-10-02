package me.ri3d.dashcam.plates.ui

import android.content.Context
import android.graphics.RectF
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.media.BackupState
import me.ri3d.dashcam.media.MediaCategory
import me.ri3d.dashcam.media.MediaItem
import me.ri3d.dashcam.media.MediaKind
import me.ri3d.dashcam.plates.PlateDetection
import me.ri3d.dashcam.plates.PlateText
import java.io.File

fun TestScope.preferencesIn(dir: File) =
    PreferencesRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "p.preferences_pb") })

/** A parsed reading as the recognizer reports it. */
fun detection(text: String, confidence: Float? = 0.9f, box: RectF = RectF(100f, 50f, 300f, 90f)) =
    PlateText.match(text)!!.let { PlateDetection(it.display, it.normalized, confidence, box, 0, it.format) }

/** A library row; with [downloadedAt] it gets a (dummy) phone copy under `filesDir/media/<id>/`. */
fun mediaItem(
    context: Context,
    id: String,
    kind: MediaKind = MediaKind.ORIGINAL_VIDEO,
    category: MediaCategory = MediaCategory.NORMAL,
    downloadedAt: Long? = null,
    recorderTime: String? = "2026-10-01 17:41:00",
    parentId: String? = null,
): MediaItem {
    val file = downloadedAt?.let { File(context.filesDir, "media/$id/clip.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(16)) } }
    return MediaItem(
        id = id, kind = kind, category = category, recorderType = category.recorderType, recorderPath = "/sim/$id.mp4",
        recorderThumbPath = null, originalFileName = "$id.mp4", recorderTime = recorderTime, recorderTimeEpochGuess = null,
        localUri = file?.toURI()?.toString(), localSizeBytes = file?.length(), localThumbPath = null, downloadedAt = downloadedAt,
        parentId = parentId, parentPositionMs = null, driveFileId = null, backupState = BackupState.NONE, backupError = null,
        driveMd5 = null, createdAt = 0,
    )
}
