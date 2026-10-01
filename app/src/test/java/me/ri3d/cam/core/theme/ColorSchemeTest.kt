package me.ri3d.cam.core.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import me.ri3d.cam.core.model.AppTheme

class ColorSchemeTest {
    private val wallpaper = darkColorScheme(primary = Color.Magenta)

    @Test
    fun `black ignores system mode and wallpaper colours`() {
        assertThat(colorSchemeFor(AppTheme.BLACK, dark = false, dynamic = { wallpaper })).isSameInstanceAs(BlackColors)
        assertThat(colorSchemeFor(AppTheme.BLACK, dark = true, dynamic = null)).isSameInstanceAs(BlackColors)
        assertThat(BlackColors.surface).isEqualTo(Color(0xFF050506))
    }

    @Test
    fun `material you uses wallpaper colours when available`() {
        var requestedDark: Boolean? = null
        val scheme = colorSchemeFor(AppTheme.MATERIAL_YOU, dark = true) { dark -> requestedDark = dark; wallpaper }

        assertThat(scheme).isSameInstanceAs(wallpaper)
        assertThat(requestedDark).isTrue()
    }

    @Test
    fun `material you falls back to static blue below Android 12`() {
        assertThat(colorSchemeFor(AppTheme.MATERIAL_YOU, dark = true, dynamic = null)).isSameInstanceAs(BlueDarkColors)
        assertThat(colorSchemeFor(AppTheme.MATERIAL_YOU, dark = false, dynamic = null)).isSameInstanceAs(BlueLightColors)
        assertThat(BlueDarkColors.primary).isEqualTo(Color(0xFFA9C7FF))
    }
}
