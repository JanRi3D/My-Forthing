package to.axolotl.cam.dashcam

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Phone side of the recorder Wi-Fi. A seam so the connection manager can be tested without a radio. */
interface RecorderWifi {
    /**
     * Requests a Wi-Fi network (`ConnectivityManager.requestNetwork`) while collected: emits it when available and
     * null when lost. Cancelling the collection releases the request.
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
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network)
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
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
        /** Location for the SSID (fine + coarse together, as Android 12+ requires); Android 13+ also nearby Wi-Fi. */
        val PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
}

/** The original app accepts any SSID whose uppercase form starts with FORTHING; here it is only a hint. */
fun looksLikeRecorderSsid(ssid: String): Boolean = ssid.uppercase().startsWith("FORTHING")
