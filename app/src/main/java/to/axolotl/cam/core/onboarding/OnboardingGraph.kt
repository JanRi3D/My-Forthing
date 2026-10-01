package to.axolotl.cam.core.onboarding

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import to.axolotl.cam.core.navigation.CreateAccount
import to.axolotl.cam.core.navigation.Home
import to.axolotl.cam.core.navigation.OfflineProfile
import to.axolotl.cam.core.navigation.SignIn
import to.axolotl.cam.core.navigation.Welcome

fun NavGraphBuilder.onboardingGraph(navController: NavController) {
    composable<Welcome> {
        WelcomeScreen(
            onCreateAccount = { navController.navigate(CreateAccount) { launchSingleTop = true } },
            onSignIn = { navController.navigate(SignIn) { launchSingleTop = true } },
            onContinueOffline = { navController.navigate(OfflineProfile) { launchSingleTop = true } },
        )
    }
    composable<OfflineProfile> {
        OfflineProfileScreen(
            onBack = { navController.navigateUp() },
            onCreateAccount = { navController.navigate(CreateAccount) { launchSingleTop = true } },
            onCreated = {
                navController.navigate(Home) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}
