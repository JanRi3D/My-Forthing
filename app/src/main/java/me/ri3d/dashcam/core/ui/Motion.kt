package me.ri3d.dashcam.core.ui

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** True when the user switched animations off (developer options / accessibility "remove animations"). */
val LocalReduceMotion = staticCompositionLocalOf { false }

// ponytail: read once per activity; a change while the app is open applies on the next start.
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Material shared-axis X screen transitions; no motion at all when [reduce] is set. */
object AxoMotion {
    private const val DURATION_MS = 300
    private const val SHIFT_DIVISOR = 10

    fun enter(reduce: Boolean, forward: Boolean): EnterTransition =
        if (reduce) EnterTransition.None
        else slideInHorizontally(tween(DURATION_MS)) { width -> (if (forward) width else -width) / SHIFT_DIVISOR } +
            fadeIn(tween(DURATION_MS))

    fun exit(reduce: Boolean, forward: Boolean): ExitTransition =
        if (reduce) ExitTransition.None
        else slideOutHorizontally(tween(DURATION_MS)) { width -> (if (forward) -width else width) / SHIFT_DIVISOR } +
            fadeOut(tween(DURATION_MS))
}
