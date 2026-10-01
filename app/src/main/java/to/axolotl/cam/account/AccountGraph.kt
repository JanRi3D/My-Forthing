package to.axolotl.cam.account

import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.EntryPointAccessors
import to.axolotl.cam.core.navigation.Account
import to.axolotl.cam.core.navigation.CreateAccount
import to.axolotl.cam.core.navigation.ForgotPassword
import to.axolotl.cam.core.navigation.Home
import to.axolotl.cam.core.navigation.OfflineProfile
import to.axolotl.cam.core.navigation.ResetSent
import to.axolotl.cam.core.navigation.SignIn
import to.axolotl.cam.core.navigation.Upgrade
import to.axolotl.cam.core.navigation.VerifyEmail

fun NavGraphBuilder.accountGraph(navController: NavController) {
    // The repository is a singleton: creating it with the graph (app start) restores a linked account's session and
    // starts its profile sync. Without Firebase configuration, or for a guest, it does nothing.
    EntryPointAccessors.fromApplication(navController.context, AccountEntryPoint::class.java).accounts()

    val onDone: (AuthDone) -> Unit = { navController.afterAuth(it) }
    composable<SignIn> {
        SignInScreen(
            onBack = { navController.navigateUp() },
            onForgotPassword = { navController.navigate(ForgotPassword) { launchSingleTop = true } },
            onCreateAccount = { navController.navigate(CreateAccount) { popUpTo<CreateAccount> { inclusive = true } } },
            onContinueOffline = { navController.navigate(OfflineProfile) { launchSingleTop = true } },
            onDone = onDone,
        )
    }
    composable<CreateAccount> {
        CreateAccountScreen(
            onBack = { navController.navigateUp() },
            onSignIn = { navController.navigate(SignIn) { popUpTo<SignIn> { inclusive = true } } },
            onDone = onDone,
        )
    }
    composable<Upgrade> {
        UpgradeScreen(
            onBack = { navController.navigateUp() },
            onSignIn = { navController.navigate(SignIn) { launchSingleTop = true } },
            onDone = onDone,
        )
    }
    composable<ForgotPassword> {
        ForgotPasswordScreen(onBack = { navController.navigateUp() }, onDone = onDone)
    }
    composable<ResetSent> { entry ->
        val parent = remember(entry) { navController.getBackStackEntry<ForgotPassword>() }
        ResetSentScreen(
            onBack = { navController.navigateUp() },
            onBackToSignIn = { navController.popBackStack<SignIn>(inclusive = false) },
            viewModel = hiltViewModel(parent),
        )
    }
    composable<VerifyEmail> {
        VerifyEmailScreen(
            onLeave = {
                // Opened right after creating an account the back stack is empty: continue to Home.
                if (navController.previousBackStackEntry == null) navController.afterAuth(AuthDone.HOME) else navController.navigateUp()
            },
        )
    }
    composable<Account> {
        AccountScreen(
            onBack = { navController.navigateUp() },
            onVerify = { navController.navigate(VerifyEmail) { launchSingleTop = true } },
            onSignedOut = { navController.navigateUp() },
        )
    }
}

private fun NavController.afterAuth(done: AuthDone) {
    when (done) {
        AuthDone.HOME -> navigate(Home) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
        AuthDone.VERIFY_EMAIL -> navigate(VerifyEmail) { popUpTo(graph.id) { inclusive = true } }
        AuthDone.RESET_SENT -> navigate(ResetSent) { launchSingleTop = true }
    }
}
