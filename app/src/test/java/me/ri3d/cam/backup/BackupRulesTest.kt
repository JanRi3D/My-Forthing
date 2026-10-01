package me.ri3d.cam.backup

import android.net.NetworkCapabilities
import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import me.ri3d.cam.core.model.AppPreferences
import me.ri3d.cam.core.model.BackupMode
import me.ri3d.cam.media.BackupState
import me.ri3d.cam.media.MediaCategory
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaKind

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

        val unmetered = BackupRules.constraints(AppPreferences(backupRequireInternetWifi = false, backupOnMobileData = false))
        assertThat(unmetered.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(unmetered.requiredNetworkRequest).isNull()

        val mobile = BackupRules.constraints(AppPreferences(backupRequireInternetWifi = false, backupOnMobileData = true))
        assertThat(mobile.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(mobile.requiredNetworkRequest).isNull()

        assertThat(BackupRules.constraints(AppPreferences())).isEqualTo(wifi) // defaults: Wi-Fi with internet only
    }

    @Test
    @Config(sdk = [23]) // WorkManager ignores network requests below API 28
    fun `below API 28 the Wi-Fi rule falls back to unmetered, never mobile data`() {
        val wifi = BackupRules.constraints(AppPreferences(backupRequireInternetWifi = true, backupOnMobileData = true))
        assertThat(wifi.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    }
}
