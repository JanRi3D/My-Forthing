package me.ri3d.cam.core.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.ri3d.cam.core.model.AppTheme

/** Semantic colours Material 3 has no slot for. */
@Immutable
data class AxoColors(val recording: Color, val ok: Color)

private val BlackAxoColors = AxoColors(recording = Color(0xFFFF453A), ok = Color(0xFF30D158))

val LocalAxoColors = staticCompositionLocalOf { BlackAxoColors }

/** Large rounded shapes of the artboards: 16 dp icon tiles, 20 dp list groups, 28 dp cards. Buttons are pills by default. */
private val AxoShapes = Shapes(
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Effective dark mode: "Schwarz" is always dark, Material You follows the system. */
@Composable
fun AppTheme.isDark(): Boolean = this == AppTheme.BLACK || isSystemInDarkTheme()

// Typography: Material 3 defaults (system sans) for both themes; Geist is not bundled.
@Composable
fun AxoTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val dark = theme.isDark()
    val context = LocalContext.current
    val dynamic: ((Boolean) -> ColorScheme)? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            { isDark -> if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        } else {
            null
        }
    val colors = colorSchemeFor(theme, dark, dynamic)
    val axoColors = when (theme) {
        AppTheme.BLACK -> BlackAxoColors
        AppTheme.MATERIAL_YOU -> AxoColors(
            recording = colors.error,
            ok = if (dark) Color(0xFF8FD99A) else Color(0xFF2E6C3D),
        )
    }
    CompositionLocalProvider(LocalAxoColors provides axoColors) {
        MaterialTheme(colorScheme = colors, shapes = AxoShapes, content = content)
    }
}

/** [dynamic] is null when the device has no wallpaper colours (below Android 12). */
internal fun colorSchemeFor(
    theme: AppTheme,
    dark: Boolean,
    dynamic: ((dark: Boolean) -> ColorScheme)?,
): ColorScheme = when (theme) {
    AppTheme.BLACK -> BlackColors
    AppTheme.MATERIAL_YOU -> dynamic?.invoke(dark) ?: if (dark) BlueDarkColors else BlueLightColors
}
