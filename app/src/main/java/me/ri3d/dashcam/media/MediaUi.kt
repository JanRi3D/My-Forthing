package me.ri3d.dashcam.media

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import me.ri3d.dashcam.R
import me.ri3d.dashcam.dashcam.errorMeaning
import me.ri3d.dashcam.dashcam.outcomeUnknown
import me.ri3d.dashcam.recorder.RecorderError
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Image over a tinted placeholder icon; the icon stays visible while loading and when loading fails. The caller gives
 * the slot a fixed size, so nothing moves when the image arrives; Coil decodes off the main thread and cancels the
 * request when the slot leaves the composition (scrolled away). A [RecorderThumb] becomes its cached request.
 */
@Composable
fun MediaThumb(model: Any?, @DrawableRes placeholder: Int, modifier: Modifier = Modifier, imageLoader: ImageLoader? = null) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(painterResource(placeholder), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val context = LocalContext.current
        val data = if (model is RecorderThumb) remember(model) { model.request(context) } else model
        if (data != null) {
            if (imageLoader != null) {
                AsyncImage(data, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(data, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@DrawableRes
fun placeholderFor(kind: MediaKind?): Int = if (kind == MediaKind.ORIGINAL_VIDEO || kind == MediaKind.UPSCALED_CLIP) R.drawable.ic_media_video else R.drawable.ic_media_photo

/** Small static label (not a button), e.g. "Vorfall" or "verbessert – rekonstruiert, kein Beweis". */
@Composable
fun MediaTag(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** Recorder screens without a session: hint plus the way to the connection screen. */
@Composable
fun NotConnectedCard(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashcam_not_connected_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.media_not_connected_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConnect) { Text(stringResource(R.string.dashcam_open_connection)) }
        }
    }
}

/** "17:41:08" from the raw recorder time; the raw text when it has another shape. */
fun recorderClock(raw: String?): String? = MediaRepository.parseRecorderTime(raw)?.let(CLOCK::format) ?: raw

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Day of a recorder time (read in the phone's zone) or of a phone timestamp; null when unknown. */
fun dayOf(item: MediaItem?, raw: String?): LocalDate? =
    MediaRepository.parseRecorderTime(raw)?.toLocalDate()
        ?: item?.let { java.time.Instant.ofEpochMilli(it.recorderTimeEpochGuess ?: it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() }

/** "Heute", "Gestern" or the localized date. */
fun dayLabel(context: Context, day: LocalDate?): String {
    if (day == null) return context.getString(R.string.media_day_unknown)
    val today = LocalDate.now()
    return when (day) {
        today -> context.getString(R.string.media_day_today)
        today.minusDays(1) -> context.getString(R.string.media_day_yesterday)
        else -> DateUtils.formatDateTime(
            context, day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR,
        )
    }
}

/** Kind and category in words ("Schleife", "Vorfall", "Foto (Typ 2)", "Screenshot", …). */
fun kindLabel(context: Context, item: MediaItem): String = when (item.kind) {
    MediaKind.SCREENSHOT -> context.getString(R.string.media_kind_screenshot)
    MediaKind.ENHANCED_FRAME -> context.getString(R.string.media_kind_enhanced)
    MediaKind.UPSCALED_CLIP -> context.getString(R.string.media_kind_upscaled)
    MediaKind.ORIGINAL_VIDEO, MediaKind.ORIGINAL_PHOTO -> when (item.category) {
        MediaCategory.NORMAL -> context.getString(R.string.media_category_normal)
        MediaCategory.EVENT -> context.getString(R.string.media_category_event)
        MediaCategory.USER -> context.getString(if (item.kind == MediaKind.ORIGINAL_PHOTO) R.string.media_category_user else R.string.media_category_user_video)
        MediaCategory.UNKNOWN -> context.getString(R.string.media_category_unknown, item.recorderType?.toString() ?: "–")
    }
}

/** 4101 failed: "Nicht gelöscht: Meaning (Code 310)", or "Ergebnis unbekannt …" when no answer came. */
fun deleteFailedText(context: Context, error: RecorderError): String {
    val text = context.getString(R.string.dashcam_error_with_code, context.getString(errorMeaning(error)), error.code)
    return context.getString(if (outcomeUnknown(error)) R.string.dashcam_status_unknown else R.string.media_delete_failed, text)
}

fun noticeText(context: Context, notice: MediaNotice): String = when (notice) {
    is MediaNotice.DeletedOnRecorder -> context.resources.getQuantityString(R.plurals.media_deleted_on_recorder, notice.count, notice.count)
    is MediaNotice.DeletedLocal -> context.resources.getQuantityString(R.plurals.media_deleted_local, notice.count, notice.count)
    is MediaNotice.RecorderFailed -> deleteFailedText(context, notice.error)
    MediaNotice.NothingToDownload -> context.getString(R.string.media_nothing_to_download)
}
