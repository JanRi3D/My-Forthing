package to.axolotl.cam.drive

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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
            DriveAccountStore::keystoreKey,
        ),
        reasonText = { context.getString(it.text) },
    )

    @Provides
    @Singleton
    fun driveApi(@DriveHttp http: OkHttpClient, auth: DriveAuth): DriveApi = DriveRestApi(http, auth)
}
