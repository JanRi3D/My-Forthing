package me.ri3d.dashcam.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.ri3d.dashcam.core.model.AppPreferences
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toAppPreferences() }
        .distinctUntilChanged()

    suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        dataStore.edit { it.write(transform(it.toAppPreferences())) }
    }
}

private object Keys {
    val theme = stringPreferencesKey("theme")
    val platesLive = booleanPreferencesKey("plates_live")
    val platesClips = booleanPreferencesKey("plates_clips")
    val liveUpscale = booleanPreferencesKey("live_upscale")
    val exportQuality = stringPreferencesKey("export_quality")
    val backupMode = stringPreferencesKey("backup_mode")
    val backupOnMobileData = booleanPreferencesKey("backup_on_mobile_data")
    val backupRequireInternetWifi = booleanPreferencesKey("backup_require_internet_wifi")
    val backupIncludePlateMetadata = booleanPreferencesKey("backup_include_plate_metadata")
}

private val defaults = AppPreferences()

private fun Preferences.toAppPreferences() = AppPreferences(
    theme = enumOr(this[Keys.theme], defaults.theme),
    platesLive = this[Keys.platesLive] ?: defaults.platesLive,
    platesClips = this[Keys.platesClips] ?: defaults.platesClips,
    liveUpscale = this[Keys.liveUpscale] ?: defaults.liveUpscale,
    exportQuality = enumOr(this[Keys.exportQuality], defaults.exportQuality),
    backupMode = enumOr(this[Keys.backupMode], defaults.backupMode),
    backupOnMobileData = this[Keys.backupOnMobileData] ?: defaults.backupOnMobileData,
    backupRequireInternetWifi = this[Keys.backupRequireInternetWifi] ?: defaults.backupRequireInternetWifi,
    backupIncludePlateMetadata = this[Keys.backupIncludePlateMetadata] ?: defaults.backupIncludePlateMetadata,
)

private fun MutablePreferences.write(p: AppPreferences) {
    this[Keys.theme] = p.theme.name
    this[Keys.platesLive] = p.platesLive
    this[Keys.platesClips] = p.platesClips
    this[Keys.liveUpscale] = p.liveUpscale
    this[Keys.exportQuality] = p.exportQuality.name
    this[Keys.backupMode] = p.backupMode.name
    this[Keys.backupOnMobileData] = p.backupOnMobileData
    this[Keys.backupRequireInternetWifi] = p.backupRequireInternetWifi
    this[Keys.backupIncludePlateMetadata] = p.backupIncludePlateMetadata
}

/** Unknown names (e.g. written by a newer app version) fall back to the default instead of crashing. */
private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
