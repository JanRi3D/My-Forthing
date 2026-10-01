package to.axolotl.cam.drive

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import to.axolotl.cam.R
import to.axolotl.cam.core.ui.UiState
import to.axolotl.cam.core.ui.UiText
import to.axolotl.cam.core.ui.asString

/**
 * Drive connection at a glance: account, state and quota. Used by the Google Drive screen and the Backup screen.
 */
@Composable
fun DriveStatusCard(
    state: DriveAuthState,
    quota: UiState<DriveQuota>?,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_cloud_upload), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.drive_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.summary(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state is DriveAuthState.NeedsReconnect) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (state) {
                DriveAuthState.NotConnected -> BodyText(stringResource(R.string.drive_not_connected_text))
                is DriveAuthState.NeedsReconnect -> BodyText(state.reason)
                is DriveAuthState.Connected -> Quota(quota)
            }
        }
    }
}

/** One line for lists: the account e-mail, or the state. */
@Composable
fun DriveAuthState.summary(): String = when (this) {
    DriveAuthState.NotConnected -> stringResource(R.string.drive_status_not_connected)
    is DriveAuthState.Connected -> accountEmail ?: stringResource(R.string.drive_status_connected)
    is DriveAuthState.NeedsReconnect -> stringResource(R.string.drive_status_reconnect)
}

@Composable
private fun Quota(quota: UiState<DriveQuota>?) {
    when (quota) {
        is UiState.Ready -> {
            val context = LocalContext.current
            val (limit, usage) = quota.data
            val used = Formatter.formatShortFileSize(context, usage)
            if (limit != null && limit > 0) {
                val description = stringResource(R.string.drive_quota_description)
                LinearProgressIndicator(
                    progress = { (usage.toFloat() / limit).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
                )
                BodyText(
                    stringResource(
                        R.string.drive_quota,
                        used,
                        Formatter.formatShortFileSize(context, limit),
                        Formatter.formatShortFileSize(context, quota.data.free ?: 0),
                    ),
                )
            } else {
                BodyText(stringResource(R.string.drive_quota_unlimited, used))
            }
        }
        UiState.Loading -> BodyText(stringResource(R.string.drive_quota_loading))
        is UiState.Error -> BodyText(quota.message.asString())
        UiState.Empty, null -> Unit
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** German message for a [DriveAuth] / [DriveApi] failure. */
fun Throwable.driveMessage(): UiText = when (this) {
    is DriveError.Authorization ->
        UiText.Res(if (configurationMissing) R.string.drive_error_configuration else R.string.drive_error_authorization, listOf(statusCode))
    is DriveError.ScopeNotGranted -> UiText.Res(R.string.drive_error_scope)
    is DriveError.Offline -> UiText.Res(R.string.drive_error_offline)
    is DriveError.InsufficientStorage -> UiText.Res(R.string.drive_error_storage_full)
    is DriveError.NeedsReconnect -> UiText.Res(R.string.drive_error_reconnect)
    is DriveError.NotConnected -> UiText.Res(R.string.drive_error_not_connected)
    is DriveError.Cancelled -> UiText.Res(R.string.drive_error_cancelled)
    is DriveError.Http ->
        if (code == 403 && reason == "accessNotConfigured") UiText.Res(R.string.drive_error_api_disabled) else UiText.Res(R.string.drive_error_http, listOf(code))
    else -> UiText.Res(R.string.drive_error_unknown)
}

@get:StringRes
val ReconnectReason.text: Int
    get() = when (this) {
        ReconnectReason.REVOKED -> R.string.drive_reconnect_revoked
        ReconnectReason.CONSENT_REQUIRED -> R.string.drive_reconnect_consent
    }
