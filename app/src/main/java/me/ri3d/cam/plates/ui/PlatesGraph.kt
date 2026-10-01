package me.ri3d.cam.plates.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import me.ri3d.cam.core.navigation.PlateDetail
import me.ri3d.cam.core.navigation.Plates
import me.ri3d.cam.core.navigation.PlatesSettings

/** Routes `Plates(query)`, `PlateDetail(plateId)` and `PlatesSettings`. Live and clip slots: see AxoNavHost. */
fun NavGraphBuilder.platesGraph(navController: NavController) {
    val back: () -> Unit = { navController.navigateUp() }
    composable<Plates> { PlatesScreen(onBack = back, onNavigate = { navController.navigate(it) }) }
    composable<PlateDetail> { PlateDetailScreen(onBack = back, onNavigate = { navController.navigate(it) }) }
    composable<PlatesSettings> { PlatesSettingsScreen(onBack = back) }
}
