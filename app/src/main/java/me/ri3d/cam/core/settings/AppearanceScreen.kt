package me.ri3d.cam.core.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ri3d.cam.R
import me.ri3d.cam.core.model.AppTheme
import me.ri3d.cam.core.theme.AxoTheme
import me.ri3d.cam.core.theme.LocalAxoColors
import me.ri3d.cam.core.ui.AxoTopBar
import me.ri3d.cam.core.ui.SectionHeader

@Composable
fun AppearanceScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val selected = preferences.theme
    Scaffold(topBar = { AxoTopBar(stringResource(R.string.appearance_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(stringResource(R.string.appearance_section_theme))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTheme.entries.forEach { theme ->
                    ThemeOption(theme, selected = theme == selected, onSelect = { viewModel.setTheme(theme) })
                }
            }
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (selected) {
                        AppTheme.MATERIAL_YOU -> stringResource(R.string.appearance_info_material_you, stringResource(R.string.app_name))
                        AppTheme.BLACK -> stringResource(R.string.appearance_info_black)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThemeOption(theme: AppTheme, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        selected = selected,
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Live preview: each option renders with its own theme, so it shows the real wallpaper colours.
            AxoTheme(theme) { MiniPreview() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(theme.label), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        when (theme) {
                            AppTheme.MATERIAL_YOU -> R.string.appearance_material_you_text
                            AppTheme.BLACK -> R.string.appearance_black_text
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AxoTheme(theme) { Swatches(theme) }
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun MiniPreview() {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .size(width = 76.dp, height = 132.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, shape)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Block(colors.surfaceContainerHigh, 10, 5)
        Block(colors.primaryContainer, 34, 8)
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Block(colors.surfaceContainerHigh, 30, 8, Modifier.weight(1f))
                Block(colors.surfaceContainerHigh, 30, 8, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Block(color: Color, height: Int, radius: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(radius.dp)).background(color))
}

@Composable
private fun Swatches(theme: AppTheme) {
    val colors = MaterialTheme.colorScheme
    val swatches = when (theme) {
        AppTheme.MATERIAL_YOU -> listOf(colors.primary, colors.secondary, colors.tertiary)
        AppTheme.BLACK -> listOf(colors.surface, colors.primary, LocalAxoColors.current.recording)
    }
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatches.forEach { color ->
            Box(Modifier.size(18.dp).clip(CircleShape).background(color).border(1.dp, colors.outline, CircleShape))
        }
    }
}
