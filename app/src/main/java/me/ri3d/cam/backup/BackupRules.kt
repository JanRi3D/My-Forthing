package me.ri3d.cam.backup

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType
import me.ri3d.cam.core.model.AppPreferences
import me.ri3d.cam.core.model.BackupMode
import me.ri3d.cam.media.BackupState
import me.ri3d.cam.media.MediaCategory
import me.ri3d.cam.media.MediaItem

/** What gets backed up automatically, and on which network (CONTRACTS §10, docs/features/backup.md). */
object BackupRules {
    /** States that wait for or run an upload. */
    val PENDING = setOf(BackupState.QUEUED, BackupState.UPLOADING)

    /** Uploads come from phone copies only: nothing is streamed from the recorder. */
    fun hasPhoneCopy(item: MediaItem): Boolean = item.localUri != null

    /**
     * Whether [mode] backs [item] up without being asked. INCIDENTS: category EVENT. ALL: every original (video,
     * photo, screenshot). Derived items (enhanced, upscaled) follow their original: only once [parent] is backed up.
     */
    fun automatic(item: MediaItem, mode: BackupMode, parent: MediaItem?): Boolean {
        if (!hasPhoneCopy(item)) return false
        val matches = when (mode) {
            BackupMode.MANUAL -> false
            BackupMode.INCIDENTS -> item.category == MediaCategory.EVENT
            BackupMode.ALL -> true
        }
        return matches && (!item.isDerived || parent?.backupState == BackupState.DONE)
    }

    /**
     * WorkManager network constraints. The job runs on the phone's default network with validated internet (the
     * process never binds to the recorder Wi-Fi, which has none); unmetered unless mobile data is allowed; with
     * [AppPreferences.backupRequireInternetWifi] only on Wi-Fi with validated internet (API 28+, below: unmetered).
     */
    fun constraints(prefs: AppPreferences): Constraints {
        val type = if (prefs.backupOnMobileData && !prefs.backupRequireInternetWifi) NetworkType.CONNECTED else NetworkType.UNMETERED
        val builder = Constraints.Builder()
        if (prefs.backupRequireInternetWifi) {
            val wifi = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            builder.setRequiredNetworkRequest(wifi, type)
        } else {
            builder.setRequiredNetworkType(type)
        }
        return builder.build()
    }
}
