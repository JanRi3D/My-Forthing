package to.axolotl.cam.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.axolotl.cam.core.model.AppPreferences
import to.axolotl.cam.core.model.AppTheme
import to.axolotl.cam.core.model.BackupMode
import to.axolotl.cam.core.model.ExportQuality
import java.io.File

class PreferencesRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.dataStore() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "test.preferences_pb") }

    @Test
    fun `empty store yields defaults`() = runTest {
        assertThat(PreferencesRepository(dataStore()).preferences.first()).isEqualTo(AppPreferences())
    }

    @Test
    fun `every field round-trips`() = runTest {
        val repository = PreferencesRepository(dataStore())
        val changed = AppPreferences(
            theme = AppTheme.BLACK,
            platesLive = true,
            platesClips = true,
            liveUpscale = true,
            exportQuality = ExportQuality.Q2160,
            backupMode = BackupMode.INCIDENTS,
            backupOnMobileData = true,
            backupRequireInternetWifi = false,
            backupIncludePlateMetadata = true,
        )

        repository.update { changed }

        assertThat(repository.preferences.first()).isEqualTo(changed)
        repository.update { it.copy(theme = AppTheme.MATERIAL_YOU) }
        assertThat(repository.preferences.first()).isEqualTo(changed.copy(theme = AppTheme.MATERIAL_YOU))
    }

    @Test
    fun `unknown enum names fall back to defaults`() = runTest {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("theme")] = "NEON" }

        assertThat(PreferencesRepository(store).preferences.first().theme).isEqualTo(AppTheme.MATERIAL_YOU)
    }
}
