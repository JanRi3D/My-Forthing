package me.ri3d.cam.enhance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Qualifier of the enhance DataStore (device performance measurements only, nothing personal). */
const val ENHANCE_STORE = "enhance"

@Module
@InstallIn(SingletonComponent::class)
abstract class EnhanceModule {
    @Binds
    abstract fun frameEnhancer(impl: DefaultFrameEnhancer): FrameEnhancer

    @Binds
    abstract fun clipUpscaler(impl: DefaultClipUpscaler): ClipUpscaler

    companion object {
        @Provides
        @Singleton
        @Named(ENHANCE_STORE)
        fun store(@ApplicationContext context: Context): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("enhance_measurements") },
        )
    }
}
