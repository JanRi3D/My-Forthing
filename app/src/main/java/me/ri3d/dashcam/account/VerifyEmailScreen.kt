package me.ri3d.dashcam.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.LocalSnackbarHostState
import me.ri3d.dashcam.core.ui.UiText
import javax.inject.Inject

/** Polling interval while the screen is visible; returning from the mail app checks immediately. */
internal const val VERIFY_POLL_MS = 5_000L

/** E-Mail bestätigen (step 2 of 2). Firebase verifies by link, so there is no code to enter. */
@Composable
fun VerifyEmailScreen(onLeave: () -> Unit, viewModel: VerifyEmailViewModel = hiltViewModel()) {
    val email by viewModel.email.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val noMailApp = stringResource(R.string.account_error_no_mail_app)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.pollVerification() }
    }
    LaunchedEffect(viewModel.verified) {
        if (viewModel.verified) onLeave()
    }
    BackHandler(onBack = onLeave)

    AuthLayout(onBack = onLeave, step = 2) {
        AuthHeading(stringResource(R.string.verify_title), stringResource(R.string.verify_text, email.orEmpty()))
        FormMessage(viewModel.message, color = MaterialTheme.colorScheme.onSurface)
        PrimaryButton(stringResource(R.string.account_open_mail), busy = false, enabled = true) {
            if (!context.openMailApp()) scope.launch { snackbar.showSnackbar(noMailApp) }
        }
        FilledTonalButton(
            onClick = viewModel::confirm,
            enabled = !viewModel.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.verify_confirmed)) }
        ResendRow(text = null, viewModel.resendCooldown.secondsLeft, busy = false, onResend = viewModel::resend)
        TextButton(onClick = onLeave, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.verify_later))
        }
    }
}

@HiltViewModel
class VerifyEmailViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {
    val email: StateFlow<String?> = accounts.state
        .map { (it as? AccountState.SignedIn)?.email }
        .stateIn(viewModelScope, SharingStarted.Eagerly, (accounts.state.value as? AccountState.SignedIn)?.email)

    /** This screen only opens right after a link was sent, so resending waits first. */
    val resendCooldown = Cooldown(viewModelScope).apply { start(RESEND_COOLDOWN_S) }

    var verified by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<UiText?>(null)
        private set

    /** "Ich habe bestätigt". */
    fun confirm() {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            accounts.reloadVerification()
                .onSuccess { if (it) verified = true else message = UiText.Res(R.string.verify_not_yet) }
                .onFailure { message = it.toAccountMessage() }
            busy = false
        }
    }

    fun resend() {
        if (resendCooldown.secondsLeft > 0) return
        resendCooldown.start(RESEND_COOLDOWN_S)
        message = null
        viewModelScope.launch {
            accounts.sendVerification()
                .onSuccess { message = UiText.Res(R.string.account_link_sent) }
                .onFailure { message = it.toAccountMessage() }
        }
    }

    /** Checks now and then every [VERIFY_POLL_MS] until verified; failures (offline) just wait for the next round. */
    suspend fun pollVerification() {
        while (!verified) {
            if (accounts.reloadVerification().getOrNull() == true) verified = true else delay(VERIFY_POLL_MS)
        }
    }
}
