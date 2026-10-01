package me.ri3d.cam.core.navigation

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
import me.ri3d.cam.account.accountGraph
import me.ri3d.cam.drive.driveGraph
import me.ri3d.cam.core.home.homeGraph
import me.ri3d.cam.core.onboarding.onboardingGraph
import me.ri3d.cam.core.settings.settingsGraph
import me.ri3d.cam.core.ui.AxoMotion
import me.ri3d.cam.core.ui.LocalReduceMotion
import me.ri3d.cam.core.ui.LocalSnackbarHostState
import me.ri3d.cam.core.ui.rememberReduceMotion
import me.ri3d.cam.dashcam.DashcamHomeCard
import me.ri3d.cam.dashcam.DashcamSettingsSection
import me.ri3d.cam.dashcam.dashcamGraph
import me.ri3d.cam.live.liveGraph
import me.ri3d.cam.media.mediaGraph

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
                    mediaGraph(navController)
                }
                SnackbarHost(
                    snackbarHostState,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
                )
            }
        }
    }
}
