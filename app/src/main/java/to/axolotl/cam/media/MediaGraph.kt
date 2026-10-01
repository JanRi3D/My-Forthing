package to.axolotl.cam.media

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import to.axolotl.cam.core.navigation.Clip
import to.axolotl.cam.core.navigation.Connection
import to.axolotl.cam.core.navigation.Recordings
import to.axolotl.cam.core.navigation.SdFiles
import to.axolotl.cam.core.navigation.Storage

/**
 * Recordings, SD files, clip player and storage. Phase 4 attaches through the slots: [selectionActions]
 * (drive-backup "Sichern"), [clipActions] (enhance-ui), [clipExtras] (plates-ui), [clipDeleteTargets] (drive-backup).
 */
fun NavGraphBuilder.mediaGraph(
    navController: NavController,
    selectionActions: SelectionActions = { _, _ -> },
    clipActions: ClipActions = { _, _ -> },
    clipExtras: ClipExtras = {},
    clipDeleteTargets: ClipDeleteTargets = { _, _ -> },
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
            clipDeleteTargets = clipDeleteTargets,
        )
    }
    composable<Storage> { StorageScreen(onBack = back) }
}
