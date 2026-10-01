package to.axolotl.cam.core.settings

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import to.axolotl.cam.core.navigation.Appearance
import to.axolotl.cam.core.navigation.Settings

/** Recorder settings rendered below the app section (feature/recorder-connection); renders its own section headers. */
typealias RecorderSettingsSection = @Composable () -> Unit

fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    recorderSettingsSection: RecorderSettingsSection? = null,
) {
    composable<Settings> {
        SettingsScreen(
            recorderSettingsSection = recorderSettingsSection,
            onBack = { navController.navigateUp() },
            onNavigate = { navController.navigate(it) { launchSingleTop = true } },
        )
    }
    composable<Appearance> {
        AppearanceScreen(onBack = { navController.navigateUp() })
    }
}
