package me.ri3d.dashcam.core.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/** The app-wide snackbar queue; any screen shows messages via `LocalSnackbarHostState.current.showSnackbar(…)`. */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbarHostState is provided by AxoNavHost")
}
