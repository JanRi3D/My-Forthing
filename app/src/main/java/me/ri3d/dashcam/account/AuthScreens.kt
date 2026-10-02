package me.ri3d.dashcam.account

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.LocalSnackbarHostState
import java.io.File

/** Anmelden: Google or e-mail/password; "Offline nutzen" while the phone has no profile yet (onboarding). */
@Composable
fun SignInScreen(
    onBack: () -> Unit,
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
    onContinueOffline: () -> Unit,
    onDone: (AuthDone) -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val activity = LocalActivity.current
    val enabled = viewModel.configured && !viewModel.busy
    val showOfflineOption by viewModel.canContinueOffline.collectAsStateWithLifecycle()
    AuthEffects(viewModel, onDone)
    AuthLayout(onBack = onBack) {
        if (!viewModel.configured) NotConfiguredNotice()
        AuthHeading(stringResource(R.string.sign_in_title), stringResource(R.string.sign_in_subtitle, stringResource(R.string.app_name)))
        GoogleButton(enabled) { activity?.let { viewModel.signInGoogle(it, needsTerms = false) } }
        OrDivider(stringResource(R.string.account_or))
        Column {
            EmailField(viewModel.email, viewModel::onEmail, viewModel.emailError, readOnly = viewModel.busy)
            PasswordField(
                viewModel.password,
                viewModel::onPassword,
                viewModel.passwordError(newPassword = false),
                newPassword = false,
                readOnly = viewModel.busy,
                onDone = viewModel::signIn,
            )
            TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.sign_in_forgot)) }
        }
        FormMessage(viewModel.message)
        PrimaryButton(stringResource(R.string.welcome_sign_in), viewModel.busy, viewModel.configured, viewModel::signIn)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FooterLink(stringResource(R.string.sign_in_new_here), stringResource(R.string.welcome_create_account), onCreateAccount)
            if (showOfflineOption) {
                TextButton(onClick = onContinueOffline) { Text(stringResource(R.string.sign_in_use_offline)) }
            }
        }
    }
}

/** Konto erstellen, step 1 of 2 (step 2 is the e-mail verification). */
@Composable
fun CreateAccountScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onDone: (AuthDone) -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val activity = LocalActivity.current
    AuthEffects(viewModel, onDone)
    AuthLayout(onBack = onBack, step = 1) {
        if (!viewModel.configured) NotConfiguredNotice()
        AuthHeading(stringResource(R.string.create_title), stringResource(R.string.create_subtitle))
        GoogleButton(viewModel.configured && !viewModel.busy) { activity?.let { viewModel.signInGoogle(it, needsTerms = true) } }
        OrDivider(stringResource(R.string.account_or))
        NewAccountForm(viewModel)
        PrimaryButton(stringResource(R.string.welcome_create_account), viewModel.busy, viewModel.configured, viewModel::create)
        FooterLink(stringResource(R.string.create_already), stringResource(R.string.welcome_sign_in), onSignIn)
    }
}

/** Online-Konto hinzufügen: the guest profile and app settings move into the new (or linked) account. */
@Composable
fun UpgradeScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onDone: (AuthDone) -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val activity = LocalActivity.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    AuthEffects(viewModel, onDone)
    AuthLayout(onBack = onBack, step = 1) {
        if (!viewModel.configured) NotConfiguredNotice()
        profile?.let { guest ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val filesDir = LocalContext.current.filesDir
                ProfileAvatar(guest.avatarPath?.let { File(filesDir, it) }, guest.displayName, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.upgrade_profile_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(guest.displayName, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(painterResource(R.drawable.ic_cloud_upload), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        AuthHeading(stringResource(R.string.upgrade_title), stringResource(R.string.upgrade_text))
        NewAccountForm(viewModel)
        PrimaryButton(stringResource(R.string.upgrade_create), viewModel.busy, viewModel.configured, viewModel::create)
        OrDivider(stringResource(R.string.upgrade_or))
        GoogleButton(viewModel.configured && !viewModel.busy) { activity?.let { viewModel.signInGoogle(it, needsTerms = true) } }
        FooterLink(stringResource(R.string.upgrade_existing), stringResource(R.string.upgrade_sign_in), onSignIn)
    }
}

@Composable
private fun NewAccountForm(viewModel: AuthViewModel) {
    EmailField(viewModel.email, viewModel::onEmail, viewModel.emailError, readOnly = viewModel.busy)
    PasswordField(
        viewModel.password,
        viewModel::onPassword,
        viewModel.passwordError(newPassword = true),
        newPassword = true,
        readOnly = viewModel.busy,
        onDone = viewModel::create,
    )
    TermsRow(viewModel.termsAccepted, viewModel::onTerms, viewModel.termsError, enabled = !viewModel.busy)
    FormMessage(viewModel.message)
}

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, onDone: (AuthDone) -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    AuthEffects(viewModel, onDone)
    AuthLayout(onBack = onBack) {
        if (!viewModel.configured) NotConfiguredNotice()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HeadingIcon(R.drawable.ic_password_reset)
            AuthHeading(stringResource(R.string.forgot_title), stringResource(R.string.forgot_text))
        }
        EmailField(
            viewModel.email,
            viewModel::onEmail,
            viewModel.emailError,
            readOnly = viewModel.busy,
            imeAction = ImeAction.Done,
            onDone = viewModel::sendReset,
        )
        FormMessage(viewModel.message)
        PrimaryButton(stringResource(R.string.forgot_send), viewModel.busy, viewModel.configured, viewModel::sendReset)
        FooterLink(stringResource(R.string.forgot_remembered), stringResource(R.string.welcome_sign_in), onBack)
    }
}

/** Shares the [AuthViewModel] of Passwort vergessen (address and resend cooldown). */
@Composable
fun ResetSentScreen(onBack: () -> Unit, onBackToSignIn: () -> Unit, viewModel: AuthViewModel) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val noMailApp = stringResource(R.string.account_error_no_mail_app)
    AuthLayout(onBack = onBack) {
        Column(
            Modifier.fillMaxWidth().padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_mail_sent),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                stringResource(R.string.reset_sent_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.reset_sent_text, viewModel.email.trim()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        FormMessage(viewModel.message, color = MaterialTheme.colorScheme.onSurface)
        PrimaryButton(stringResource(R.string.account_open_mail), busy = false, enabled = true) {
            if (!context.openMailApp()) scope.launch { snackbar.showSnackbar(noMailApp) }
        }
        FilledTonalButton(onClick = onBackToSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.reset_sent_back))
        }
        ResendRow(stringResource(R.string.reset_sent_missing), viewModel.resendCooldown.secondsLeft, viewModel.busy, viewModel::resendReset)
    }
}

/** "Erneut senden" with the remaining cooldown in its label. */
@Composable
internal fun ResendRow(text: String?, secondsLeft: Int, busy: Boolean, onResend: () -> Unit) {
    val label = if (secondsLeft > 0) {
        stringResource(R.string.account_resend_in, secondsLeft / 60, secondsLeft % 60)
    } else {
        stringResource(R.string.account_resend)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onResend, enabled = secondsLeft == 0 && !busy) { Text(label) }
    }
}
