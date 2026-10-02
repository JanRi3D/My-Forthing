package me.ri3d.dashcam.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import me.ri3d.dashcam.core.model.AppPreferences
import me.ri3d.dashcam.core.model.AppTheme
import me.ri3d.dashcam.core.model.BackupMode
import me.ri3d.dashcam.core.model.ExportQuality

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

    @Test
    fun `the Drive account follows the account once this phone knew one, empty after a disconnect`() {
        assertThat(AppPreferences().toSynced()).doesNotContainKey("driveAccount")
        val connected = AppPreferences(driveAccount = "jane@gmail.com")
        val disconnected = AppPreferences(driveAccount = "")

        assertThat(connected.toSynced()).containsEntry("driveAccount", "jane@gmail.com")
        assertThat(AppPreferences().withSynced(connected.toSynced())).isEqualTo(connected)
        assertThat(disconnected.toSynced()).containsEntry("driveAccount", "")
        assertThat(connected.withSynced(disconnected.toSynced())).isEqualTo(disconnected)
        assertThat(connected.withSynced(emptyMap())).isEqualTo(connected) // an account from before keeps the phone's
    }

    @Test
    fun `a Drive account value that is no e-mail address is ignored`() {
        val connected = AppPreferences(driveAccount = "jane@gmail.com")

        listOf("not an address", "jane @gmail.com", "a\nb@c", "x".repeat(250) + "@gmail.com").forEach { value ->
            assertThat(connected.withSynced(mapOf("driveAccount" to value))).isEqualTo(connected)
        }
    }
}
