package me.ri3d.dashcam.drive

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The internet client for googleapis.com (not the recorder-bound one). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DriveHttp

@Module
@InstallIn(SingletonComponent::class)
object DriveModule {

    @Provides
    @Singleton
    @DriveHttp
    fun driveHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS) // Drive can take a while to answer the last chunk of a large upload
        .build()

    @Provides
    @Singleton
    fun driveAuth(@ApplicationContext context: Context, @DriveHttp http: OkHttpClient): DriveAuth = GoogleDriveAuth(
        authorizer = PlayDriveAuthorizer(context, http, DriveRestApi.GOOGLE_APIS),
        store = DriveAccountStore(
            context.getSharedPreferences(DriveAccountStore.PREFS_NAME, Context.MODE_PRIVATE),
            key = DriveAccountStore::keystoreKey,
            dropKey = DriveAccountStore::deleteKeystoreKey,
        ),
        reasonText = { context.getString(it.text) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @Provides
    @Singleton
    fun driveApi(@DriveHttp http: OkHttpClient, auth: DriveAuth): DriveApi = DriveRestApi(http, auth)
}

/**
 * Images from Drive (thumbnailLink, photo content) for Coil: [http] with [DriveAuthInterceptor].
 */
fun driveImageClient(http: OkHttpClient, auth: DriveAuth): OkHttpClient = http.newBuilder().addInterceptor(DriveAuthInterceptor(auth)).build()

/**
 * Adds `Authorization: Bearer <token>` to HTTPS requests for Google's hosts only (`*.googleusercontent.com` for
 * thumbnails, `*.googleapis.com` for content); every other request goes out untouched, so the token never leaves
 * Google. A 401 drops the cached token, so the next image gets a fresh one.
 */
class DriveAuthInterceptor(private val auth: DriveAuth) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isHttps || !isGoogleHost(request.url.host)) return chain.proceed(request)
        // OkHttp threads only (Coil fetches off the main thread); Play services answers from its token cache.
        val token = runBlocking { auth.accessToken() }.getOrElse { throw IOException("no Drive access token", it) }
        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        if (response.code == 401) runBlocking { auth.invalidate(token) }
        return response
    }

    companion object {
        fun isGoogleHost(host: String): Boolean = listOf("googleusercontent.com", "googleapis.com").any { host == it || host.endsWith(".$it") }
    }
}
