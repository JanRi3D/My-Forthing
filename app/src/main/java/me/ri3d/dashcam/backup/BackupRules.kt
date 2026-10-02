package me.ri3d.dashcam.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType
import me.ri3d.dashcam.core.model.AppPreferences
import me.ri3d.dashcam.core.model.BackupMode
import me.ri3d.dashcam.media.BackupState
import me.ri3d.dashcam.media.MediaCategory
import me.ri3d.dashcam.media.MediaItem

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
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // the builder's default would rule out any VPN
                .build()
            builder.setRequiredNetworkRequest(wifi, type)
        } else {
            builder.setRequiredNetworkType(type)
        }
        return builder.build()
    }

    /**
     * The default network right now (the one the Drive client uses) fits the conditions: validated internet, Wi-Fi
     * when required, unmetered unless mobile data is allowed. Checked when a job starts, since the network may have
     * changed after JobScheduler decided. A VPN reports its underlying network's transports and metering.
     */
    fun networkFits(caps: NetworkCapabilities?, prefs: AppPreferences): Boolean = when {
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> false
        prefs.backupRequireInternetWifi -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        else -> prefs.backupOnMobileData || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** [networkFits] for the phone's default network right now (uploads and "Vom Drive laden"). */
    fun defaultNetworkFits(context: Context, prefs: AppPreferences): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return networkFits(connectivity.getNetworkCapabilities(connectivity.activeNetwork), prefs)
    }
}
