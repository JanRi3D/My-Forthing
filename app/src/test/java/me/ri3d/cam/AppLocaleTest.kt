package me.ri3d.cam

import android.app.LocaleManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AppLocaleTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreDefaultLocale() = Locale.setDefault(defaultLocale)

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS")
    fun `Android 13+ sets the per-app language and the activity context to German`() {
        pinAppLocale(app)
        assertThat(app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()).isEqualTo("de")
        assertThat(app.withAppLocale().resources.configuration.locales.toLanguageTags()).isEqualTo("de")
    }

    @Test
    @Config(sdk = [30], qualifiers = "en-rUS")
    fun `below Android 13 the activity context and the default locale are German`() {
        assertThat(app.withAppLocale().resources.configuration.locales.toLanguageTags()).isEqualTo("de")
        assertThat(Locale.getDefault().language).isEqualTo("de")
    }
}
