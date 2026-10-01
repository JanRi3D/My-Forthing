package to.axolotl.cam.core.home

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import to.axolotl.cam.core.navigation.Home

/** [dashcamCard] is replaced by feature/recorder-connection with the live connection card. */
fun NavGraphBuilder.homeGraph(
    navController: NavController,
    dashcamCard: @Composable () -> Unit = { HomeDashcamCard() },
) {
    composable<Home> {
        HomeScreen(dashcamCard = dashcamCard, onNavigate = { navController.navigate(it) { launchSingleTop = true } })
    }
}
