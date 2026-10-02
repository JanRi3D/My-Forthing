package me.ri3d.dashcam.media

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import me.ri3d.dashcam.core.navigation.Clip
import me.ri3d.dashcam.core.navigation.Connection
import me.ri3d.dashcam.core.navigation.DriveAccount
import me.ri3d.dashcam.core.navigation.Recordings
import me.ri3d.dashcam.core.navigation.SdFiles
import me.ri3d.dashcam.core.navigation.Storage

/**
 * Recordings, SD files, clip player and storage. Phase 4 attaches through the slots: [selectionActions]
 * (drive-backup "Sichern"), [clipActions] (enhance-ui), [clipExtras] and [clipOverlay] (plates-ui), [clipDeleteTargets]
 * (drive-backup).
 */
fun NavGraphBuilder.mediaGraph(
    navController: NavController,
    selectionActions: SelectionActions = { _, _ -> },
    clipActions: ClipActions = { _, _ -> },
    clipExtras: ClipExtras = { _, _, _ -> },
    clipOverlay: ClipOverlay = { _, _ -> },
    clipDeleteTargets: ClipDeleteTargets = { emptyList() },
) {
    val back: () -> Unit = { navController.navigateUp() }
    val connect: () -> Unit = { navController.navigate(Connection) { launchSingleTop = true } }
    composable<Recordings> { entry ->
        RecordingsScreen(
            initialTab = RecordingsTab.of(entry.toRoute<Recordings>().tab),
            onBack = back,
            onConnect = connect,
            onOpen = { navController.navigate(Clip(it)) },
            onRawList = { navController.navigate(SdFiles(it.name)) },
            selectionActions = selectionActions,
            onConnectDrive = { navController.navigate(DriveAccount) { launchSingleTop = true } },
        )
    }
    composable<SdFiles> { entry ->
        SdFilesScreen(entry.toRoute<SdFiles>().category, onBack = back, onConnect = connect, onOpen = { navController.navigate(Clip(it)) })
    }
    composable<Clip> { entry ->
        ClipScreen(
            positionMs = entry.toRoute<Clip>().positionMs,
            onBack = back,
            onOpen = { id, position -> navController.navigate(Clip(id, position)) },
            clipActions = clipActions,
            clipExtras = clipExtras,
            clipOverlay = clipOverlay,
            clipDeleteTargets = clipDeleteTargets,
        )
    }
    composable<Storage> { StorageScreen(onBack = back) }
}
