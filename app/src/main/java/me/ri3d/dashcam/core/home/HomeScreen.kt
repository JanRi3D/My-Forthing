package me.ri3d.dashcam.core.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.FeatureFlags
import me.ri3d.dashcam.core.model.LocalProfile
import me.ri3d.dashcam.core.navigation.Live
import me.ri3d.dashcam.core.navigation.Plates
import me.ri3d.dashcam.core.navigation.Recordings
import me.ri3d.dashcam.core.navigation.Route
import me.ri3d.dashcam.core.navigation.SdCard
import me.ri3d.dashcam.core.navigation.Settings
import me.ri3d.dashcam.core.profile.LocalProfileDao
import java.io.File
import javax.inject.Inject

private class HomeTile(
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @DrawableRes val icon: Int,
    val route: Route,
    val enabled: Boolean,
)

private val tiles = listOf(
    HomeTile(R.string.home_tile_live, R.string.home_tile_live_text, R.drawable.ic_live, Live, FeatureFlags.live),
    HomeTile(R.string.home_tile_recordings, R.string.home_tile_recordings_text, R.drawable.ic_recordings, Recordings(), FeatureFlags.media),
    HomeTile(R.string.home_tile_sd_card, R.string.home_tile_sd_card_text, R.drawable.ic_sd_card, SdCard, FeatureFlags.dashcam),
    HomeTile(R.string.home_tile_settings, R.string.home_tile_settings_text, R.drawable.ic_settings, Settings, enabled = true),
)

@Composable
fun HomeScreen(
    dashcamCard: @Composable () -> Unit,
    onNavigate: (Route) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        SearchBar(profile, onSearch = { onNavigate(Plates()) }, onProfile = { onNavigate(Settings) })
        Spacer(Modifier.height(20.dp))
        dashcamCard()
        Spacer(Modifier.height(12.dp))
        // Large font sizes get one tile per row so titles such as "Einstellungen" are not broken mid-word.
        val perRow = if (LocalDensity.current.fontScale > 1.5f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(perRow).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tile -> Tile(tile, Modifier.weight(1f)) { onNavigate(tile.route) } }
                }
            }
        }
    }
}

/** Plate search (once feature/plates-ui exists, otherwise the app name) plus the profile button. */
@Composable
private fun SearchBar(profile: LocalProfile?, onSearch: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (FeatureFlags.plates) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClick = onSearch)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                Text(
                    stringResource(R.string.home_search_plates),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                stringResource(R.string.app_name),
                modifier = Modifier.weight(1f).padding(start = 20.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(onClick = onProfile, modifier = Modifier.padding(end = 4.dp)) {
            ProfileAvatar(profile, stringResource(R.string.home_profile))
        }
    }
}

@Composable
private fun ProfileAvatar(profile: LocalProfile?, description: String) {
    val filesDir = LocalContext.current.filesDir
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val avatarPath = profile?.avatarPath
        if (avatarPath != null) {
            AsyncImage(
                model = File(filesDir, avatarPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val name = profile?.displayName.orEmpty()
            Text(
                if (name.isEmpty()) "" else name.substring(0, name.offsetByCodePoints(0, 1)).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun Tile(tile: HomeTile, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = tile.enabled,
        modifier = modifier.fillMaxHeight().heightIn(min = 196.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp).alpha(if (tile.enabled) 1f else 0.5f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(tile.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(tile.title), style = MaterialTheme.typography.titleMedium.copy(hyphens = Hyphens.Auto))
                Text(
                    stringResource(if (tile.enabled) tile.subtitle else R.string.home_tile_unavailable),
                    style = MaterialTheme.typography.bodyMedium.copy(hyphens = Hyphens.Auto),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(profiles: LocalProfileDao) : ViewModel() {
    val profile: StateFlow<LocalProfile?> =
        profiles.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
