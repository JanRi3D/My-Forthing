package me.ri3d.dashcam.core.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.FeatureFlags
import me.ri3d.dashcam.core.log.Log
import me.ri3d.dashcam.core.profile.ProfileRepository
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.core.ui.ListGroup
import me.ri3d.dashcam.core.ui.ListRow
import me.ri3d.dashcam.core.ui.LocalSnackbarHostState
import javax.inject.Inject

private const val MAX_NAME_LENGTH = 40

@Composable
fun OfflineProfileScreen(
    onBack: () -> Unit,
    onCreateAccount: () -> Unit,
    onCreated: () -> Unit,
    viewModel: OfflineProfileViewModel = hiltViewModel(),
) {
    var name by rememberSaveable { mutableStateOf("") }
    var avatar by rememberSaveable { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) avatar = uri }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val saveError = stringResource(R.string.offline_error_save)

    LaunchedEffect(viewModel.state) {
        when (viewModel.state) {
            SaveState.SAVED -> onCreated()
            SaveState.FAILED -> {
                viewModel.errorShown()
                scope.launch { snackbar.showSnackbar(saveError) }
            }
            else -> Unit
        }
    }

    Scaffold(topBar = { AxoTopBar(title = "", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .padding(bottom = 8.dp)
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_phone),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    stringResource(R.string.offline_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.offline_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.offline_name_label)) },
                placeholder = { Text(stringResource(R.string.offline_name_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )

            AvatarPicker(
                avatar = avatar,
                onPick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                onRemove = { avatar = null },
            )

            ListGroup(
                buildList {
                    add { shape ->
                        ListRow(
                            stringResource(R.string.offline_local_title),
                            supporting = stringResource(R.string.offline_local_text),
                            icon = R.drawable.ic_phone,
                            shape = shape,
                            verticalAlignment = Alignment.Top,
                        )
                    }
                    add { shape ->
                        ListRow(
                            stringResource(R.string.offline_no_internet_title),
                            supporting = stringResource(R.string.offline_no_internet_text),
                            icon = R.drawable.ic_wifi_off,
                            shape = shape,
                            verticalAlignment = Alignment.Top,
                        )
                    }
                    // Only promised once accounts exist.
                    if (FeatureFlags.accounts) {
                        add { shape ->
                            ListRow(
                                stringResource(R.string.offline_go_online_title),
                                supporting = stringResource(R.string.offline_go_online_text),
                                icon = R.drawable.ic_cloud_upload,
                                shape = shape,
                                verticalAlignment = Alignment.Top,
                            )
                        }
                    }
                },
            )

            Button(
                onClick = { viewModel.create(name, avatar) },
                enabled = name.isNotBlank() && viewModel.state == SaveState.IDLE,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.offline_create)) }

            if (FeatureFlags.accounts) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.offline_account_instead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCreateAccount) { Text(stringResource(R.string.welcome_create_account)) }
                }
            }
        }
    }
}

@Composable
private fun AvatarPicker(avatar: Uri?, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null, // "Profilbild" is the label right next to it
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painterResource(R.drawable.ic_account),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.offline_picture_label), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.offline_picture_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Aligns the button labels with the text above (TextButton has 12 dp inner padding).
            Row(Modifier.offset(x = (-12).dp)) {
                TextButton(onClick = onPick) {
                    Text(stringResource(if (avatar == null) R.string.offline_picture_pick else R.string.offline_picture_change))
                }
                if (avatar != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.offline_picture_remove)) }
                }
            }
        }
    }
}

enum class SaveState { IDLE, SAVING, SAVED, FAILED }

@HiltViewModel
class OfflineProfileViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    var state by mutableStateOf(SaveState.IDLE)
        private set

    fun create(name: String, avatar: Uri?) {
        if (state != SaveState.IDLE || name.isBlank()) return
        state = SaveState.SAVING
        viewModelScope.launch {
            state = try {
                profiles.createLocal(name, avatar)
                SaveState.SAVED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Creating the offline profile failed", e)
                SaveState.FAILED
            }
        }
    }

    fun errorShown() {
        if (state == SaveState.FAILED) state = SaveState.IDLE
    }

    private companion object {
        const val TAG = "OfflineProfile"
    }
}
