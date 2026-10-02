package me.ri3d.dashcam.backup

import android.net.NetworkCapabilities
import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import me.ri3d.dashcam.core.model.AppPreferences
import me.ri3d.dashcam.core.model.BackupMode
import me.ri3d.dashcam.media.BackupState
import me.ri3d.dashcam.media.MediaCategory
import me.ri3d.dashcam.media.MediaItem
import me.ri3d.dashcam.media.MediaKind

@RunWith(RobolectricTestRunner::class) // NetworkRequest, WorkManager Constraints
class BackupRulesTest {
    private fun item(kind: MediaKind, category: MediaCategory, local: Boolean, parentId: String? = null) = MediaItem(
        id = "id", kind = kind, category = category, recorderType = null, recorderPath = null, recorderThumbPath = null,
        originalFileName = "f", recorderTime = null, recorderTimeEpochGuess = null, localUri = if (local) "file:/x" else null,
        localSizeBytes = null, localThumbPath = null, downloadedAt = null, parentId = parentId, parentPositionMs = null,
        driveFileId = null, backupState = BackupState.NONE, backupError = null, driveMd5 = null, createdAt = 0,
    )

    private fun parent(state: BackupState) = item(MediaKind.ORIGINAL_VIDEO, MediaCategory.EVENT, local = true).copy(id = "p", backupState = state)

    @Test
    fun `automatic rules matrix`() {
        val originals = listOf(MediaKind.ORIGINAL_VIDEO, MediaKind.ORIGINAL_PHOTO, MediaKind.SCREENSHOT)
        for (kind in originals) for (category in MediaCategory.entries) for (local in listOf(true, false)) {
            val it = item(kind, category, local)
            val why = "$kind $category local=$local"
            assertWithMessage(why).that(BackupRules.automatic(it, BackupMode.MANUAL, null)).isFalse()
            assertWithMessage(why).that(BackupRules.automatic(it, BackupMode.INCIDENTS, null)).isEqualTo(local && category == MediaCategory.EVENT)
            assertWithMessage(why).that(BackupRules.automatic(it, BackupMode.ALL, null)).isEqualTo(local)
        }
    }

    @Test
    fun `derived items follow their original`() {
        for (kind in listOf(MediaKind.ENHANCED_FRAME, MediaKind.UPSCALED_CLIP)) for (category in listOf(MediaCategory.EVENT, MediaCategory.NORMAL)) {
            val derived = item(kind, category, local = true, parentId = "p")
            for (state in BackupState.entries) {
                val expected = state == BackupState.DONE
                assertWithMessage("$kind $state").that(BackupRules.automatic(derived, BackupMode.ALL, parent(state))).isEqualTo(expected)
                assertThat(BackupRules.automatic(derived, BackupMode.INCIDENTS, parent(state))).isEqualTo(expected && category == MediaCategory.EVENT)
                assertThat(BackupRules.automatic(derived, BackupMode.MANUAL, parent(state))).isFalse()
            }
            assertThat(BackupRules.automatic(derived, BackupMode.ALL, null)).isFalse() // original deleted
            assertThat(BackupRules.automatic(derived.copy(localUri = null), BackupMode.ALL, parent(BackupState.DONE))).isFalse()
        }
    }

    @Test
    @Config(sdk = [33])
    fun `constraints follow the conditions`() {
        val wifi = BackupRules.constraints(AppPreferences(backupRequireInternetWifi = true, backupOnMobileData = true))
        val request = wifi.requiredNetworkRequest!!
        assertThat(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).isTrue()
        assertThat(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isTrue()
        assertThat(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).isTrue()
        assertThat(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).isFalse() // a VPN over Wi-Fi is fine

        val unmetered = BackupRules.constraints(AppPreferences(backupRequireInternetWifi = false, backupOnMobileData = false))
        assertThat(unmetered.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(unmetered.requiredNetworkRequest).isNull()

        val mobile = BackupRules.constraints(AppPreferences(backupRequireInternetWifi = false, backupOnMobileData = true))
        assertThat(mobile.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(mobile.requiredNetworkRequest).isNull()

        assertThat(BackupRules.constraints(AppPreferences())).isEqualTo(wifi) // defaults: Wi-Fi with internet only
    }

    @Test
    @Config(sdk = [27]) // WorkManager ignores network requests below API 28
    fun `below API 28 the Wi-Fi rule falls back to unmetered, never mobile data`() {
        val wifi = BackupRules.constraints(AppPreferences(backupRequireInternetWifi = true, backupOnMobileData = true))
        assertThat(wifi.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    }

    @Test
    @Config(sdk = [33])
    fun `the default network is checked against the conditions when a job starts`() {
        fun caps(vararg capabilities: Int, transport: Int) = ShadowNetworkCapabilities.newInstance().also { nc ->
            capabilities.forEach { shadowOf(nc).addCapability(it) }
            shadowOf(nc).addTransportType(transport)
        }
        val internet = intArrayOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = caps(*internet, NetworkCapabilities.NET_CAPABILITY_NOT_METERED, transport = NetworkCapabilities.TRANSPORT_WIFI)
        val cellular = caps(*internet, transport = NetworkCapabilities.TRANSPORT_CELLULAR)
        val recorderWifi = caps(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, transport = NetworkCapabilities.TRANSPORT_WIFI) // no internet

        val wifiOnly = AppPreferences(backupRequireInternetWifi = true)
        val unmeteredOnly = AppPreferences(backupRequireInternetWifi = false, backupOnMobileData = false)
        val mobileAllowed = AppPreferences(backupRequireInternetWifi = false, backupOnMobileData = true)
        assertThat(listOf(wifiOnly, unmeteredOnly, mobileAllowed).map { BackupRules.networkFits(wifi, it) }).containsExactly(true, true, true)
        assertThat(listOf(wifiOnly, unmeteredOnly, mobileAllowed).map { BackupRules.networkFits(cellular, it) }).containsExactly(false, false, true)
        assertThat(listOf(wifiOnly, unmeteredOnly, mobileAllowed).map { BackupRules.networkFits(recorderWifi, it) }).containsExactly(false, false, false)
        assertThat(BackupRules.networkFits(null, mobileAllowed)).isFalse()
    }
}
