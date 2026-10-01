package to.axolotl.cam.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import to.axolotl.cam.core.model.AppPreferences
import to.axolotl.cam.core.model.AppTheme
import to.axolotl.cam.core.model.BackupMode
import to.axolotl.cam.core.model.ExportQuality

class SyncedPreferencesTest {
    private val deviceLocal = AppPreferences(
        platesLive = true,
        platesClips = true,
        liveUpscale = true,
        backupMode = BackupMode.ALL,
        backupOnMobileData = true,
        backupRequireInternetWifi = false,
        backupIncludePlateMetadata = true,
    )

    @Test
    fun `only theme and export quality leave the phone`() {
        val synced = deviceLocal.copy(theme = AppTheme.BLACK, exportQuality = ExportQuality.Q2160).toSynced()

        assertThat(synced).containsExactly("theme", "BLACK", "exportQuality", "Q2160")
    }

    @Test
    fun `applying remote values leaves device-local flags alone`() {
        val applied = deviceLocal.withSynced(mapOf("theme" to "BLACK", "exportQuality" to "Q1080", "backupMode" to "MANUAL"))

        assertThat(applied).isEqualTo(deviceLocal.copy(theme = AppTheme.BLACK, exportQuality = ExportQuality.Q1080))
    }

    @Test
    fun `unknown values from a newer app version keep the local value`() {
        val local = AppPreferences(theme = AppTheme.BLACK)

        assertThat(local.withSynced(mapOf("theme" to "NEON", "future" to "x"))).isEqualTo(local)
        assertThat(local.withSynced(emptyMap())).isEqualTo(local)
    }

    @Test
    fun `round trip`() {
        val prefs = AppPreferences(theme = AppTheme.BLACK, exportQuality = ExportQuality.Q1080)

        assertThat(AppPreferences().withSynced(prefs.toSynced())).isEqualTo(prefs)
    }
}
