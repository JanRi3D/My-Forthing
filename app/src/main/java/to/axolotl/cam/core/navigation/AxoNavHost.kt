package to.axolotl.cam.core.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import to.axolotl.cam.account.accountGraph
import to.axolotl.cam.drive.driveGraph
import to.axolotl.cam.core.home.homeGraph
import to.axolotl.cam.core.onboarding.onboardingGraph
import to.axolotl.cam.core.settings.settingsGraph
import to.axolotl.cam.core.ui.AxoMotion
import to.axolotl.cam.core.ui.LocalReduceMotion
import to.axolotl.cam.core.ui.LocalSnackbarHostState
import to.axolotl.cam.core.ui.rememberReduceMotion
import to.axolotl.cam.dashcam.DashcamHomeCard
import to.axolotl.cam.dashcam.DashcamSettingsSection
import to.axolotl.cam.dashcam.dashcamGraph
import to.axolotl.cam.live.liveGraph

/** The single NavHost. Features register their graph here with one line each. */
@Composable
fun AxoNavHost(startDestination: Route) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val reduceMotion = rememberReduceMotion()
    CompositionLocalProvider(
        LocalSnackbarHostState provides snackbarHostState,
        LocalReduceMotion provides reduceMotion,
    ) {
        // The Surface gives every screen the surface colour and onSurface as content colour.
        Surface(Modifier.fillMaxSize()) {
            Box {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    enterTransition = { AxoMotion.enter(reduceMotion, forward = true) },
                    exitTransition = { AxoMotion.exit(reduceMotion, forward = true) },
                    popEnterTransition = { AxoMotion.enter(reduceMotion, forward = false) },
                    popExitTransition = { AxoMotion.exit(reduceMotion, forward = false) },
                ) {
                    onboardingGraph(navController)
                    homeGraph(navController, dashcamCard = { DashcamHomeCard(onClick = { navController.navigate(Connection) }) })
                    settingsGraph(navController, recorderSettingsSection = { DashcamSettingsSection(onNavigate = { navController.navigate(it) }) })
                    accountGraph(navController)
                    driveGraph(navController)
                    dashcamGraph(navController)
                    liveGraph(navController)
                }
                SnackbarHost(
                    snackbarHostState,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
                )
            }
        }
    }
}
