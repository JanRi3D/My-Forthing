package me.ri3d.cam

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.model.AppTheme
import me.ri3d.cam.core.navigation.AxoNavHost
import me.ri3d.cam.core.navigation.Route
import me.ri3d.cam.core.navigation.startDestination
import me.ri3d.cam.core.profile.LocalProfileDao
import me.ri3d.cam.core.theme.AxoTheme
import me.ri3d.cam.core.theme.isDark
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash stays until theme and start destination are known (a few ms), so neither flashes.
        // The condition runs on pre-draw, i.e. after super.onCreate has injected the ViewModel factory.
        installSplashScreen().setKeepOnScreenCondition { viewModel.state.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value ?: return@setContent
            val dark = state.theme.isDark()
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AxoTheme(state.theme) { AxoNavHost(state.startDestination) }
        }
    }
}

data class MainState(val theme: AppTheme, val startDestination: Route)

@HiltViewModel
class MainViewModel @Inject constructor(
    preferences: PreferencesRepository,
    profiles: LocalProfileDao,
) : ViewModel() {
    /** The start destination is decided once; later profile changes navigate explicitly instead of rebuilding the graph. */
    val state: StateFlow<MainState?> = flow {
        val start = startDestination(hasProfile = profiles.observe().first() != null)
        emitAll(preferences.preferences.map { MainState(it.theme, start) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
