package me.ri3d.cam.enhance.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import me.ri3d.cam.R
import me.ri3d.cam.core.navigation.Clip
import me.ri3d.cam.core.navigation.Enhance
import me.ri3d.cam.core.navigation.EnhanceSettings
import me.ri3d.cam.core.navigation.Route
import me.ri3d.cam.core.navigation.Upscale
import me.ri3d.cam.core.ui.ListRow
import me.ri3d.cam.core.ui.UiText
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaKind

/** Routes `Enhance`, `Upscale`, `EnhanceSettings`. Entry points: [EnhanceClipActions], [EnhanceSettingsRow], live slots. */
fun NavGraphBuilder.enhanceGraph(navController: NavController) {
    val back: () -> Unit = { navController.navigateUp() }
    composable<Enhance> {
        EnhanceScreen(
            onBack = back,
            onSaved = { id -> navController.navigate(Clip(id)) { popUpTo<Enhance> { inclusive = true } } },
        )
    }
    composable<Upscale> {
        UpscaleScreen(onBack = back, onOpen = { navController.navigate(Clip(it)) }, onOpenJob = { navController.navigate(Upscale(it)) })
    }
    composable<EnhanceSettings> { EnhanceSettingsScreen(onBack = back) }
}

/**
 * Clip screen slot: "Bild verbessern" (videos at [positionMs], photos, screenshots) and "Clip hochskalieren" (original
 * videos). Derived items are not enhanced again; they link to their original instead.
 */
@Composable
fun EnhanceClipActions(item: MediaItem, positionMs: Long, onNavigate: (Route) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (item.isDerived) {
            Text(
                stringResource(if (item.kind == MediaKind.UPSCALED_CLIP) R.string.enhance_already_upscaled else R.string.enhance_already_enhanced),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.parentId?.let { parent ->
                TextButton(onClick = { onNavigate(Clip(parent, item.parentPositionMs ?: 0)) }) { Text(stringResource(R.string.enhance_open_original)) }
            }
            return
        }
        val onPhone = item.localFile?.isFile == true
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onNavigate(Enhance(item.id, if (item.isVideo) positionMs else 0)) }, enabled = onPhone) {
                Icon(painterResource(R.drawable.ic_enhance), contentDescription = null)
                Text(stringResource(R.string.enhance_action_frame), Modifier.padding(start = 8.dp))
            }
            if (item.kind == MediaKind.ORIGINAL_VIDEO) {
                FilledTonalButton(onClick = { onNavigate(Upscale(item.id)) }, enabled = onPhone) {
                    Icon(painterResource(R.drawable.ic_upscale), contentDescription = null)
                    Text(stringResource(R.string.enhance_action_clip), Modifier.padding(start = 8.dp))
                }
            }
        }
        if (!onPhone) {
            Text(stringResource(R.string.enhance_actions_need_copy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Settings → App: "Verbesserung & Hochskalierung". */
@Composable
fun EnhanceSettingsRow(shape: Shape, onClick: () -> Unit) {
    ListRow(
        stringResource(R.string.enhance_settings_title),
        supporting = stringResource(R.string.enhance_settings_row_text),
        icon = R.drawable.ic_enhance,
        shape = shape,
        onClick = onClick,
        trailing = {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

/** Radio row inside a `ListGroup(…, Modifier.selectableGroup())`: the whole row selects, read as a radio button. */
@Composable
internal fun ChoiceRow(
    headline: String,
    supporting: String?,
    selected: Boolean,
    enabled: Boolean,
    shape: Shape,
    supportingColor: Color = Color.Unspecified,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (supporting == null) 56.dp else 72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            Text(headline, style = MaterialTheme.typography.bodyLarge, color = if (enabled) onSurface else onSurface.copy(alpha = 0.38f))
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
                )
            }
        }
    }
}

/** "45 s", "12 min", "3 h 20 min" (rounded up, an estimate). */
internal fun durationText(context: Context, ms: Long): String = duration(ms).let { context.getString(it.id, *it.args.toTypedArray()) }

internal fun duration(ms: Long): UiText.Res {
    val s = ((ms + 999) / 1000).coerceAtLeast(1)
    val m = (s + 59) / 60
    return when {
        s < 60 -> UiText.Res(R.string.enhance_duration_seconds, listOf(s.toInt()))
        m < 60 -> UiText.Res(R.string.enhance_duration_minutes, listOf(m.toInt()))
        else -> UiText.Res(R.string.enhance_duration_hours, listOf((m / 60).toInt(), (m % 60).toInt()))
    }
}
