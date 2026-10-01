package me.ri3d.cam.core.onboarding

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import me.ri3d.cam.core.navigation.CreateAccount
import me.ri3d.cam.core.navigation.Home
import me.ri3d.cam.core.navigation.OfflineProfile
import me.ri3d.cam.core.navigation.SignIn
import me.ri3d.cam.core.navigation.Welcome

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
