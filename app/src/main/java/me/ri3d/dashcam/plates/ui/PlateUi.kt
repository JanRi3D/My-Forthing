package me.ri3d.dashcam.plates.ui

import android.content.Context
import android.graphics.RectF
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import me.ri3d.dashcam.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.min
import kotlin.math.roundToInt

/** Where content of [content] size appears when fitted (letterboxed, centred) into [container]. */
fun fitRect(container: Size, content: IntSize): Rect {
    if (content.width <= 0 || content.height <= 0) return Rect.Zero
    val scale = min(container.width / content.width, container.height / content.height)
    val w = content.width * scale
    val h = content.height * scale
    val left = (container.width - w) / 2
    val top = (container.height - h) / 2
    return Rect(left, top, left + w, top + h)
}

/** A box in pixels of a frame of [frame] size, mapped into [videoRect] (where that frame is shown, px). */
fun mapBox(box: Rect, frame: IntSize, videoRect: Rect): Rect {
    val sx = videoRect.width / frame.width
    val sy = videoRect.height / frame.height
    return Rect(videoRect.left + box.left * sx, videoRect.top + box.top * sy, videoRect.left + box.right * sx, videoRect.top + box.bottom * sy)
}

fun RectF.toRect() = Rect(left, top, right, bottom)

/** One plate to draw over a video. [unsure]: no confidence (any `?`, a left-out glyph): dashed amber "unsicher" style. */
class OverlayPlate(val text: String, val unsure: Boolean, val box: Rect)

private val UnsureColor = Color(0xFFFFD54F)

/**
 * Boxes plus text chips over a video showing frames of [frame] size in [videoRect]. Text is shown as read (`?`
 * as-is). TalkBack reads one summary ("Erkannte Kennzeichen: …") on the first chip instead of the single chips.
 */
@Composable
fun PlateOverlay(plates: List<OverlayPlate>, frame: IntSize, videoRect: Rect, modifier: Modifier = Modifier) {
    if (plates.isEmpty() || frame.width <= 0 || frame.height <= 0 || videoRect.isEmpty) return
    val sure = MaterialTheme.colorScheme.primary
    val onSure = MaterialTheme.colorScheme.onPrimary
    val unsureLabel = stringResource(R.string.plates_unsure)
    val summary = stringResource(
        R.string.plates_overlay_description,
        plates.joinToString("; ") { if (it.unsure) "${it.text}, $unsureLabel" else it.text },
    )
    val mapped = plates.map { it to mapBox(it.box, frame, videoRect) }
    // TalkBack: one summary on the first chip; the full-size box stays free so the video below remains explorable.
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 2.dp.toPx()
            val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            mapped.forEach { (plate, r) ->
                drawRect(
                    color = if (plate.unsure) UnsureColor else sure,
                    topLeft = Offset(r.left, r.top),
                    size = r.size,
                    style = Stroke(width, pathEffect = if (plate.unsure) dashes else null),
                )
            }
        }
        mapped.forEachIndexed { index, (plate, r) ->
            Row(
                Modifier
                    .layout { measurable, constraints ->
                        // Above the box, or below it when the box touches the top edge.
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val gap = 4.dp.roundToPx()
                        val above = r.top.roundToInt() - gap - p.height
                        layout(p.width, p.height) {
                            p.place(r.left.roundToInt(), if (above >= 0) above else r.bottom.roundToInt() + gap)
                        }
                    }
                    .clearAndSetSemantics { if (index == 0) contentDescription = summary } // after layout: bounds = the chip
                    .background(if (plate.unsure) UnsureColor else sure, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val color = if (plate.unsure) Color.Black else onSure
                Text(plate.text, style = PlateTextStyle.copy(fontSize = 11.sp), color = color)
                if (plate.unsure) Text(unsureLabel, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

/** Monospace plate reading, as in the artboards. */
val PlateTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em)

/** A plate reading on an inverse-surface chip; `?` characters are shown as read. */
@Composable
fun PlateChip(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Text(
        text,
        modifier
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(if (large) 10.dp else 6.dp))
            .padding(horizontal = if (large) 16.dp else 8.dp, vertical = if (large) 8.dp else 4.dp),
        style = PlateTextStyle.copy(fontSize = if (large) 28.sp else 14.sp),
        color = MaterialTheme.colorScheme.inverseOnSurface,
        maxLines = 1,
    )
}

/** "heute 17:42", "gestern 08:12" or "12. Sept., 09:05" (phone time); with [seconds] "heute 17:42:05". */
fun seenText(context: Context, epochMs: Long, seconds: Boolean = false): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val time = if (seconds) timeOfDay(epochMs) else DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_TIME)
    val today = LocalDate.now(zone)
    return when (at.toLocalDate()) {
        today -> context.getString(R.string.plates_today, time)
        today.minusDays(1) -> context.getString(R.string.plates_yesterday, time)
        else -> context.getString(
            R.string.plates_date_time,
            DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
            time,
        )
    }
}

/** Phone time of day with seconds, "17:42:05". */
fun timeOfDay(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
