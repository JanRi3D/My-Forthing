package me.ri3d.dashcam.core.navigation

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
import me.ri3d.dashcam.account.accountGraph
import me.ri3d.dashcam.backup.BackupSelectionAction
import me.ri3d.dashcam.backup.backupGraph
import me.ri3d.dashcam.backup.driveDeleteTargets
import me.ri3d.dashcam.drive.driveGraph
import me.ri3d.dashcam.core.home.homeGraph
import me.ri3d.dashcam.core.onboarding.onboardingGraph
import me.ri3d.dashcam.core.settings.settingsGraph
import me.ri3d.dashcam.core.ui.AxoMotion
import me.ri3d.dashcam.core.ui.LocalReduceMotion
import me.ri3d.dashcam.core.ui.LocalSnackbarHostState
import me.ri3d.dashcam.core.ui.rememberReduceMotion
import me.ri3d.dashcam.dashcam.DashcamHomeCard
import me.ri3d.dashcam.dashcam.DashcamSettingsSection
import me.ri3d.dashcam.dashcam.dashcamGraph
import me.ri3d.dashcam.enhance.ui.EnhanceClipActions
import me.ri3d.dashcam.enhance.ui.LiveSharpenControl
import me.ri3d.dashcam.enhance.ui.enhanceGraph
import me.ri3d.dashcam.enhance.ui.liveSharpenEffect
import me.ri3d.dashcam.live.liveGraph
import me.ri3d.dashcam.media.mediaGraph
import me.ri3d.dashcam.plates.ui.ClipPlates
import me.ri3d.dashcam.plates.ui.ClipPlatesOverlay
import me.ri3d.dashcam.plates.ui.LivePlatesList
import me.ri3d.dashcam.plates.ui.LivePlatesOverlay
import me.ri3d.dashcam.plates.ui.LivePlatesToggle
import me.ri3d.dashcam.plates.ui.PlatesAutoScan
import me.ri3d.dashcam.plates.ui.platesGraph

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
                    liveGraph(
                        navController,
                        leadingControls = { LivePlatesToggle() },
                        trailingControls = { LiveSharpenControl() },
                        belowControls = { LivePlatesList(onNavigate = { navController.navigate(it) }) },
                        overlay = { LivePlatesOverlay(it) },
                        renderEffect = { liveSharpenEffect(it) },
                    )
                    mediaGraph(
                        navController,
                        selectionActions = { items, clear -> BackupSelectionAction(items, clear, onConnectDrive = { navController.navigate(DriveAccount) }) },
                        clipActions = { item, position -> EnhanceClipActions(item, position) { navController.navigate(it) } },
                        clipExtras = { item, _, seekTo -> ClipPlates(item, seekTo, onNavigate = { navController.navigate(it) }) },
                        clipOverlay = { item, position -> ClipPlatesOverlay(item, position) },
                        clipDeleteTargets = { item -> driveDeleteTargets(item) },
                    )
                    enhanceGraph(navController)
                    platesGraph(navController)
                    backupGraph(navController)
                }
                PlatesAutoScan()
                SnackbarHost(
                    snackbarHostState,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
                )
            }
        }
    }
}
