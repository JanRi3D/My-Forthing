package to.axolotl.cam.account

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import to.axolotl.cam.BuildConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {
    @Binds
    abstract fun accountRepository(impl: FirebaseAccountRepository): AccountRepository

    @Binds
    abstract fun profileRemote(impl: FirestoreProfileRemote): ProfileRemote

    companion object {
        @Provides
        @Singleton
        fun firebaseHandles(@ApplicationContext context: Context) = FirebaseHandles(
            context,
            firebaseOptions(
                projectId = BuildConfig.FIREBASE_PROJECT_ID,
                appId = BuildConfig.FIREBASE_APP_ID,
                apiKey = BuildConfig.FIREBASE_API_KEY,
                storageBucket = BuildConfig.FIREBASE_STORAGE_BUCKET,
            ),
            webClientId = BuildConfig.FIREBASE_WEB_CLIENT_ID,
        )
    }
}

/** Lets the nav graph create the repository at app start (see [accountGraph]). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccountEntryPoint {
    fun accounts(): AccountRepository
}
