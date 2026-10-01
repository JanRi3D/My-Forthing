package me.ri3d.cam.dashcam

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.ri3d.cam.BuildConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DashcamModule {
    @Provides
    @Singleton
    fun connectionManager(@ApplicationContext context: Context): RecorderConnectionManager =
        RecorderConnectionManagerImpl(
            wifi = AndroidRecorderWifi(context),
            rsaKey = BuildConfig.DASHCAM_RSA_KEY,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ).also { manager ->
            // Lifecycle observers must be added on the main thread.
            ContextCompat.getMainExecutor(context).execute {
                ProcessLifecycleOwner.get().lifecycle.addObserver(manager.processObserver)
            }
        }
}
