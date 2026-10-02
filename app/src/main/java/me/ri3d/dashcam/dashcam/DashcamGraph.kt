package me.ri3d.dashcam.dashcam

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import me.ri3d.dashcam.core.navigation.Connection
import me.ri3d.dashcam.core.navigation.Diagnostics
import me.ri3d.dashcam.core.navigation.SdCard

/** Connection, SD card and diagnostics. The Home card and the recorder settings section use the core slots. */
fun NavGraphBuilder.dashcamGraph(navController: NavController) {
    val back: () -> Unit = { navController.navigateUp() }
    composable<Connection> {
        ConnectionScreen(onBack = back, onOpenDiagnostics = { navController.navigate(Diagnostics) { launchSingleTop = true } })
    }
    composable<SdCard> {
        SdCardScreen(onBack = back, onConnect = { navController.navigate(Connection) { launchSingleTop = true } })
    }
    composable<Diagnostics> {
        DiagnosticsScreen(onBack = back)
    }
}
