package to.axolotl.cam.core.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import to.axolotl.cam.R
import to.axolotl.cam.core.FeatureFlags

@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onSignIn: () -> Unit, onContinueOffline: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Scrolls for large font sizes; otherwise the hero is centred and the actions sit at the bottom.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            // The launcher foreground keeps the artwork in its middle 61 %, so it is drawn larger than the 124 dp artboard.
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.size(200.dp))
            Text(
                stringResource(R.string.app_name),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.welcome_subtitle),
                modifier = Modifier.widthIn(max = 280.dp).padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f).heightIn(min = 40.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val tall = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                if (FeatureFlags.accounts) {
                    Button(onClick = onCreateAccount, modifier = tall) { Text(stringResource(R.string.welcome_create_account)) }
                    FilledTonalButton(onClick = onSignIn, modifier = tall) { Text(stringResource(R.string.welcome_sign_in)) }
                    TextButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.welcome_continue_offline))
                    }
                } else {
                    // Without accounts the guest path is the only one, so it gets the primary button.
                    Button(onClick = onContinueOffline, modifier = tall) { Text(stringResource(R.string.welcome_continue_offline)) }
                }
            }
        }
    }
}
