package me.ri3d.cam.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.ri3d.cam.R
import me.ri3d.cam.core.branding.Branding
import me.ri3d.cam.core.ui.AxoTopBar
import me.ri3d.cam.core.ui.ConfirmDialog
import me.ri3d.cam.core.ui.UiText
import me.ri3d.cam.core.ui.asString
import java.io.File

// ponytail: placeholder pages under the assumed support domain; replace with the real legal pages before release.
private const val TERMS_URL = "${Branding.supportUrl}/nutzungsbedingungen"
private const val PRIVACY_URL = "${Branding.supportUrl}/datenschutz"

/** Scrolling single-column layout of the sign-in artboards; [step] shows "Schritt n von 2" with a progress bar. */
@Composable
internal fun AuthLayout(onBack: (() -> Unit)?, step: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { AxoTopBar(title = "", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (step != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.account_step, step, 2),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { step / 2f },
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}, // the label above says it
                    )
                }
            }
            content()
        }
    }
}

/** Shown on every sign-in screen of a build without Firebase configuration; the actions below are disabled. */
@Composable
internal fun NotConfiguredNotice() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics(mergeDescendants = true) {}
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.account_not_configured),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.account_not_configured_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
internal fun AuthHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 56 dp tonal icon tile above a heading (Passwort vergessen). */
@Composable
internal fun HeadingIcon(@DrawableRes icon: Int) {
    Box(
        Modifier.size(56.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun GoogleButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.account_continue_google))
    }
}

@Composable
internal fun OrDivider(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun EmailField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes error: Int?,
    readOnly: Boolean,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
        readOnly = readOnly,
        label = { Text(stringResource(R.string.account_email)) },
        placeholder = { Text(stringResource(R.string.account_email_placeholder)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

/** [newPassword] switches autofill to "new password" and shows the length hint. */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes error: Int?,
    newPassword: Boolean,
    readOnly: Boolean,
    onDone: () -> Unit,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    val supporting = error ?: R.string.account_password_hint.takeIf { newPassword }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        modifier = Modifier.fillMaxWidth().semantics {
            contentType = if (newPassword) ContentType.NewPassword else ContentType.Password
        },
        label = { Text(stringResource(R.string.account_password)) },
        placeholder = {
            Text(stringResource(if (newPassword) R.string.account_password_new_placeholder else R.string.account_password_placeholder))
        },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    painterResource(if (shown) R.drawable.ic_password_hide else R.drawable.ic_password_show),
                    contentDescription = stringResource(if (shown) R.string.account_password_hide else R.string.account_password_show),
                )
            }
        },
        isError = error != null,
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

/** Consent checkbox plus links to the legal pages (links are separate buttons so TalkBack reaches them). */
@Composable
internal fun TermsRow(accepted: Boolean, onChange: (Boolean) -> Unit, @StringRes error: Int?, enabled: Boolean) {
    val uriHandler = LocalUriHandler.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(accepted, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = accepted, onCheckedChange = null, enabled = enabled)
            Text(stringResource(R.string.create_terms), style = MaterialTheme.typography.bodyMedium)
        }
        if (error != null) {
            Text(
                stringResource(error),
                modifier = Modifier.padding(start = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Aligns the link labels with the checkbox (TextButton has 12 dp inner padding).
        Row(Modifier.offset(x = (-12).dp)) {
            TextButton(onClick = { runCatching { uriHandler.openUri(TERMS_URL) } }) { Text(stringResource(R.string.create_terms_link)) }
            TextButton(onClick = { runCatching { uriHandler.openUri(PRIVACY_URL) } }) { Text(stringResource(R.string.create_privacy_link)) }
        }
    }
}

/** Result of the last action, announced by TalkBack. */
@Composable
internal fun FormMessage(message: UiText?, color: Color = MaterialTheme.colorScheme.error) {
    if (message != null) {
        Text(
            message.asString(),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
internal fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        if (busy) {
            val loading = stringResource(R.string.state_view_loading)
            CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = loading }, strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}

/** "Neu hier? Konto erstellen" style row at the bottom of the auth screens. */
@Composable
internal fun FooterLink(text: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
internal fun ProfileAvatar(file: File?, name: String, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val trimmed = name.trim()
            Text(
                if (trimmed.isEmpty()) "" else trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Navigation for [AuthViewModel.done] plus the dialogs for [AuthViewModel.prompt]. */
@Composable
internal fun AuthEffects(viewModel: AuthViewModel, onDone: (AuthDone) -> Unit) {
    LaunchedEffect(viewModel.done) {
        viewModel.done?.let {
            viewModel.navigated()
            onDone(it)
        }
    }
    when (val prompt = viewModel.prompt) {
        null -> Unit
        is LinkPrompt.Choose -> MergeChooser(prompt, onChoose = viewModel::choose, onDismiss = viewModel::dismissPrompt)
        is LinkPrompt.OtherAccount -> ConfirmDialog(
            title = stringResource(R.string.other_account_title),
            text = stringResource(R.string.other_account_text, prompt.accountEmail ?: stringResource(R.string.other_account_fallback)),
            confirmLabel = stringResource(R.string.other_account_switch),
            onConfirm = { viewModel.choose(MergeStrategy.KEEP_REMOTE) },
            onDismiss = viewModel::dismissPrompt,
        )
    }
}

/** Local vs account profile; nothing is replaced until the user picks one and confirms. */
@Composable
private fun MergeChooser(prompt: LinkPrompt.Choose, onChoose: (MergeStrategy) -> Unit, onDismiss: () -> Unit) {
    var choice by rememberSaveable { mutableStateOf<MergeStrategy?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.merge_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.merge_text))
                ProfileChoice(
                    label = stringResource(R.string.merge_local),
                    summary = prompt.local,
                    dateText = R.string.merge_created,
                    selected = choice == MergeStrategy.KEEP_LOCAL,
                    onSelect = { choice = MergeStrategy.KEEP_LOCAL },
                )
                ProfileChoice(
                    label = stringResource(R.string.merge_remote),
                    summary = prompt.remote,
                    dateText = R.string.merge_updated,
                    selected = choice == MergeStrategy.KEEP_REMOTE,
                    onSelect = { choice = MergeStrategy.KEEP_REMOTE },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { choice?.let(onChoose) }, enabled = choice != null) { Text(stringResource(R.string.merge_keep)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ProfileChoice(label: String, summary: ProfileSummary, @StringRes dateText: Int, selected: Boolean, onSelect: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large,
            )
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        ProfileAvatar(summary.avatar, summary.displayName, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(summary.displayName, style = MaterialTheme.typography.bodyLarge)
            summary.updatedAt?.let {
                Text(
                    stringResource(dateText, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Opens the user's mail app (inbox), false if there is none. */
internal fun Context.openMailApp(): Boolean = try {
    startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
}
