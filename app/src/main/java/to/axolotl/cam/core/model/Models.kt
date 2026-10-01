package to.axolotl.cam.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single profile on this phone (guest, or the local cache of a signed-in account).
 * [avatarPath] is relative to `Context.filesDir`; [linkedUid] is the Firebase uid once an account is linked.
 */
@Entity(tableName = "local_profile")
data class LocalProfile(
    @PrimaryKey val id: String,
    val displayName: String,
    val avatarPath: String?,
    val createdAt: Long,
    val linkedUid: String?,
)

enum class AppTheme { MATERIAL_YOU, BLACK }

enum class ExportQuality { Q1080, Q1440, Q2160 }

enum class BackupMode { MANUAL, INCIDENTS, ALL }

/** App-only preferences (DataStore). Recorder settings are a different type and never written from here. */
data class AppPreferences(
    val theme: AppTheme = AppTheme.MATERIAL_YOU,
    val platesLive: Boolean = false,
    val platesClips: Boolean = false,
    val liveUpscale: Boolean = false,
    val exportQuality: ExportQuality = ExportQuality.Q1440,
    val backupMode: BackupMode = BackupMode.MANUAL,
    val backupOnMobileData: Boolean = false,
    val backupRequireInternetWifi: Boolean = true,
    val backupIncludePlateMetadata: Boolean = false,
)
