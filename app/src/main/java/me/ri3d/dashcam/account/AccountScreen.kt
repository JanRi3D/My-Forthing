package me.ri3d.dashcam.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.log.Log
import me.ri3d.dashcam.core.model.LocalProfile
import me.ri3d.dashcam.core.navigation.Account
import me.ri3d.dashcam.core.navigation.Route
import me.ri3d.dashcam.core.navigation.SignIn
import me.ri3d.dashcam.core.navigation.Upgrade
import me.ri3d.dashcam.core.profile.LocalProfileDao
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.core.ui.ConfirmDialog
import me.ri3d.dashcam.core.ui.ListGroup
import me.ri3d.dashcam.core.ui.ListRow
import me.ri3d.dashcam.core.ui.UiText
import java.io.File
import javax.inject.Inject

/** Konto: works offline from the local profile; edits sync when online. */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel.event) {
        when (viewModel.event) {
            AccountEvent.VERIFICATION_SENT -> onVerify()
            AccountEvent.SIGNED_OUT -> onSignedOut()
            null -> return@LaunchedEffect
        }
        viewModel.eventHandled()
    }

    Scaffold(topBar = { AxoTopBar(stringResource(R.string.settings_account), onBack = onBack) }) { padding ->
        val signedIn = state as? AccountState.SignedIn
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val current = profile ?: return@Column
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileAvatar(current.avatarPath?.let { File(LocalContext.current.filesDir, it) }, current.displayName, 96.dp)
                Text(
                    current.displayName,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                signedIn?.email?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            NameEditor(current, viewModel::rename)
            FormMessage(viewModel.message)
            if (signedIn != null) {
                ListGroup(
                    listOf(
                        { shape ->
                            VerificationRow(signedIn, shape, viewModel.sending, viewModel.verifyCooldown.secondsLeft, viewModel::sendVerification)
                        },
                        { shape ->
                            ListRow(
                                stringResource(if (signedIn.online) R.string.account_online else R.string.account_offline),
                                supporting = stringResource(if (signedIn.online) R.string.account_online_text else R.string.account_offline_text),
                                icon = if (signedIn.online) R.drawable.ic_cloud_upload else R.drawable.ic_wifi_off,
                                shape = shape,
                                verticalAlignment = Alignment.Top,
                            )
                        },
                        { shape ->
                            ListRow(
                                stringResource(R.string.account_synced_title),
                                supporting = stringResource(R.string.account_synced_text),
                                icon = R.drawable.ic_info,
                                shape = shape,
                                verticalAlignment = Alignment.Top,
                            )
                        },
                        { shape ->
                            ListRow(
                                stringResource(R.string.account_drive_title),
                                supporting = stringResource(R.string.account_drive_text),
                                icon = R.drawable.ic_cloud_upload,
                                shape = shape,
                                verticalAlignment = Alignment.Top,
                            )
                        },
                    ),
                )
                OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.account_sign_out))
                }
            }
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.account_sign_out_title),
            text = stringResource(R.string.account_sign_out_text),
            confirmLabel = stringResource(R.string.account_sign_out),
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun NameEditor(profile: LocalProfile, onSave: (String) -> Unit) {
    var draft by rememberSaveable(profile.displayName) { mutableStateOf(profile.displayName) }
    val changed = draft.trim().isNotEmpty() && draft.trim() != profile.displayName
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it.take(PROFILE_NAME_MAX) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.offline_name_label)) },
        singleLine = true,
        trailingIcon = {
            if (changed) TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.account_name_save)) }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (changed) onSave(draft) }),
    )
}

@Composable
private fun VerificationRow(state: AccountState.SignedIn, shape: Shape, sending: Boolean, secondsLeft: Int, onSend: () -> Unit) {
    if (state.emailVerified) {
        ListRow(stringResource(R.string.account_email_verified), icon = R.drawable.ic_mail_sent, shape = shape)
    } else {
        ListRow(
            stringResource(R.string.account_email_unverified),
            supporting = if (secondsLeft > 0) {
                stringResource(R.string.account_resend_in, secondsLeft / 60, secondsLeft % 60)
            } else {
                stringResource(R.string.account_email_verify_action)
            },
            icon = R.drawable.ic_mail_sent,
            shape = shape,
            onClick = onSend.takeIf { !sending && secondsLeft == 0 },
        )
    }
}

/** Settings → App → Konto; the target depends on the account state. */
@Composable
fun AccountSettingsRow(shape: Shape, onNavigate: (Route) -> Unit, viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val signedIn = state as? AccountState.SignedIn
    val (supporting, target) = when {
        signedIn != null -> (signedIn.email ?: signedIn.displayName.orEmpty()) to Account
        profile?.linkedUid != null -> stringResource(R.string.settings_account_signed_out) to SignIn
        else -> stringResource(R.string.settings_account_offline) to Upgrade
    }
    ListRow(
        stringResource(R.string.settings_account),
        supporting = supporting,
        icon = R.drawable.ic_account,
        shape = shape,
        onClick = { onNavigate(target) },
        trailing = {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

enum class AccountEvent { VERIFICATION_SENT, SIGNED_OUT }

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val profiles: LocalProfileDao,
) : ViewModel() {
    val state: StateFlow<AccountState> = accounts.state
    val profile: StateFlow<LocalProfile?> = profiles.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var message by mutableStateOf<UiText?>(null)
        private set
    var event by mutableStateOf<AccountEvent?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    val verifyCooldown = Cooldown(viewModelScope)

    /** Local write only; the running profile sync pushes it to the account (queued while offline). */
    fun rename(name: String) {
        val current = profile.value ?: return
        val trimmed = name.trim().take(PROFILE_NAME_MAX)
        if (trimmed.isEmpty() || trimmed == current.displayName) return
        message = null
        viewModelScope.launch {
            try {
                profiles.upsert(current.copy(displayName = trimmed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Renaming the profile failed: ${e.logLabel()}")
                message = UiText.Res(R.string.account_error_generic)
            }
        }
    }

    fun sendVerification() {
        if (sending || verifyCooldown.secondsLeft > 0) return
        sending = true
        message = null
        viewModelScope.launch {
            accounts.sendVerification()
                .onSuccess {
                    verifyCooldown.start(RESEND_COOLDOWN_S)
                    event = AccountEvent.VERIFICATION_SENT
                }
                .onFailure { message = it.toAccountMessage() }
            sending = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            accounts.signOut()
            event = AccountEvent.SIGNED_OUT
        }
    }

    fun eventHandled() {
        event = null
    }

    private companion object {
        const val TAG = "Account"
    }
}
