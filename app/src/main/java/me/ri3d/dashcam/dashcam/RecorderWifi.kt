package me.ri3d.dashcam.dashcam

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Phone side of the recorder Wi-Fi. A seam so the connection manager can be tested without a radio. */
interface RecorderWifi {
    /**
     * Requests a Wi-Fi network (`ConnectivityManager.requestNetwork`) while collected: emits the newest one when available
     * and null only when that one is lost (a switch emits the new network). Cancelling releases the request.
     */
    fun network(): Flow<Network?>

    /** SSID without quotes, or null when unknown (no Wi-Fi, permission missing, location off). */
    fun currentSsid(): String?

    /** Phone mobile data switch, null when Android does not tell. */
    fun mobileDataEnabled(): Boolean?
}

class AndroidRecorderWifi(context: Context) : RecorderWifi {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    override fun network(): Flow<Network?> = callbackFlow {
        val callback = LatestNetworkCallback { trySend(it) }
        // The recorder hotspot has no internet: do not require NET_CAPABILITY_INTERNET (a default of the builder).
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.requestNetwork(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }

    /**
     * Like the original app's helper: a quoted SSID with the quotes removed; "<unknown ssid>" and unquoted (hex)
     * names count as unknown. getConnectionInfo is deprecated since API 31 but still reports the SSID to apps
     * holding the location permission, which the network callback's transportInfo would not without
     * FLAG_INCLUDE_LOCATION_INFO.
     */
    @Suppress("DEPRECATION")
    override fun currentSsid(): String? = runCatching { wifi?.connectionInfo?.ssid }.getOrNull()
        ?.takeIf { it.length > 2 && it.startsWith('"') && it.endsWith('"') }
        ?.let { it.substring(1, it.length - 1) }

    override fun mobileDataEnabled(): Boolean? = try {
        telephony?.isDataEnabled
    } catch (e: SecurityException) {
        null
    }

    companion object {
        /** Location reads the SSID (fine + coarse together, as Android 12+ requires). */
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

/**
 * Emits the newest available network and null only when that network is lost. On a make-before-break switch
 * (Android 12+) `onAvailable(new)` arrives before `onLost(old)`; the old loss must not read as "no Wi-Fi".
 */
class LatestNetworkCallback(private val emit: (Network?) -> Unit) : ConnectivityManager.NetworkCallback() {
    private var current: Network? = null // guarded by this

    override fun onAvailable(network: Network) {
        synchronized(this) { current = network }
        emit(network)
    }

    override fun onLost(network: Network) {
        val wasCurrent = synchronized(this) { (network == current).also { if (it) current = null } }
        if (wasCurrent) emit(null)
    }
}

/** The original app accepts any SSID whose uppercase form starts with FORTHING; here it is only a hint. */
fun looksLikeRecorderSsid(ssid: String): Boolean = ssid.uppercase().startsWith("FORTHING")
