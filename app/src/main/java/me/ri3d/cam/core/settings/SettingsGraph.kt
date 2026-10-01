package me.ri3d.cam.core.settings

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import me.ri3d.cam.core.navigation.Appearance
import me.ri3d.cam.core.navigation.Settings

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
