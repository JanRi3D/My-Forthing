package to.axolotl.cam.media

import android.content.Context
import coil3.ImageLoader
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import okhttp3.OkHttpClient
import to.axolotl.cam.dashcam.RecorderConnectionManager
import to.axolotl.cam.dashcam.RecorderConnectionState
import java.io.IOException
import javax.inject.Singleton

/** No Ready session on the bound recorder Wi-Fi: nothing is requested; downloads wait without using up attempts. */
class RecorderNotReadyException : IOException("no ready recorder session")

/**
 * Recorder media over HTTP: the client bound to the recorder Wi-Fi and the file URL. In the debug simulator mode
 * the files come from the desktop simulator's HTTP server ([simulatorBaseUrl], `:recorder:runSimulator`).
 */
class RecorderHttp(
    private val manager: RecorderConnectionManager,
    private val simulatorBaseUrl: String,
    context: Context,
) {
    /**
     * The bound client, only while a session is Ready on the network that client is bound to (or in simulator mode).
     * Any other device answering at 192.168.42.1 on some Wi-Fi is never asked. Throws [RecorderNotReadyException]
     * (or `RecorderNotBoundException`); never falls back to mobile data.
     */
    fun client(): OkHttpClient {
        val state = manager.state.value
        if (state !is RecorderConnectionState.Ready) throw RecorderNotReadyException()
        if (!manager.simulator.value && state.network != manager.recorderNetwork.value) throw RecorderNotReadyException()
        return manager.httpClient()
    }

    fun url(recorderPath: String): String =
        if (manager.simulator.value) simulatorBaseUrl.trimEnd('/') + "/" + recorderPath.trimStart('/') else manager.mediaUrl(recorderPath)

    /**
     * Thumbnails from the recorder. Every request asks [client] at call time, so a new or lost session applies at
     * once and without a Ready session the placeholder stays. Coil's own connectivity check is off: the recorder
     * Wi-Fi has no internet. No service-loaded fetchers: nothing may load recorder URLs unbound.
     */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .serviceLoaderEnabled(false)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { Call.Factory { request -> client().newCall(request) } },
                        connectivityChecker = { ConnectivityChecker.ONLINE },
                    ),
                )
            }
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    /** `:recorder:runSimulator` serves the simulated files here (emulator alias of the development machine). */
    const val SIMULATOR_BASE_URL = "http://10.0.2.2:8080"

    @Provides
    @Singleton
    fun recorderHttp(manager: RecorderConnectionManager, @ApplicationContext context: Context) =
        RecorderHttp(manager, SIMULATOR_BASE_URL, context)
}
