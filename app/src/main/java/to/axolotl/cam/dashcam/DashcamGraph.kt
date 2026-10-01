package to.axolotl.cam.dashcam

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import to.axolotl.cam.core.navigation.Connection
import to.axolotl.cam.core.navigation.Diagnostics
import to.axolotl.cam.core.navigation.SdCard

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
