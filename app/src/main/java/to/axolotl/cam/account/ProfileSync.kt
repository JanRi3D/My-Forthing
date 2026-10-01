package to.axolotl.cam.account

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.axolotl.cam.core.data.PreferencesRepository
import to.axolotl.cam.core.log.Log
import to.axolotl.cam.core.model.AppPreferences
import to.axolotl.cam.core.model.AppTheme
import to.axolotl.cam.core.model.ExportQuality
import to.axolotl.cam.core.model.LocalProfile
import to.axolotl.cam.core.profile.LocalProfileDao
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Same limit as the offline profile form. */
internal const val PROFILE_NAME_MAX = 40

private const val KEY_THEME = "theme"
private const val KEY_EXPORT_QUALITY = "exportQuality"

/**
 * App preferences that follow the account. Everything else stays on this phone: plate recognition and live upscaling
 * depend on the phone's performance, and all backup options belong to the separate Google Drive feature.
 * Recorder settings are not part of [AppPreferences] at all.
 */
internal fun AppPreferences.toSynced(): Map<String, String> =
    mapOf(KEY_THEME to theme.name, KEY_EXPORT_QUALITY to exportQuality.name)

/** Applies the synced keys; unknown keys or values (e.g. from a newer app version) keep the local value. */
internal fun AppPreferences.withSynced(remote: Map<String, String>): AppPreferences = copy(
    theme = enumOrNull<AppTheme>(remote[KEY_THEME]) ?: theme,
    exportQuality = enumOrNull<ExportQuality>(remote[KEY_EXPORT_QUALITY]) ?: exportQuality,
)

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

internal enum class LinkDecision { UPLOAD_LOCAL, APPLY_REMOTE, ASK, OTHER_ACCOUNT }

/** The migration matrix of CONTRACTS §9: data only moves without asking when the other side has nothing to lose. */
internal fun decideLink(local: LocalProfile?, uid: String, remoteHasProfile: Boolean, strategy: MergeStrategy): LinkDecision = when {
    // First start signed in: the phone has no profile yet.
    local == null -> if (remoteHasProfile) LinkDecision.APPLY_REMOTE else LinkDecision.UPLOAD_LOCAL
    // Same account again: its copy is newer, every change made while signed in was written through.
    local.linkedUid == uid -> if (remoteHasProfile) LinkDecision.APPLY_REMOTE else LinkDecision.UPLOAD_LOCAL
    // The phone's profile belongs to someone else: only switch when the user asked for it.
    local.linkedUid != null && strategy != MergeStrategy.KEEP_REMOTE -> LinkDecision.OTHER_ACCOUNT
    !remoteHasProfile -> LinkDecision.UPLOAD_LOCAL
    strategy == MergeStrategy.KEEP_LOCAL -> LinkDecision.UPLOAD_LOCAL
    strategy == MergeStrategy.KEEP_REMOTE -> LinkDecision.APPLY_REMOTE
    else -> LinkDecision.ASK
}

/** Links the phone's [LocalProfile] to an account and keeps both in sync while signed in. */
class ProfileSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: ProfileRemote,
    private val profiles: LocalProfileDao,
    private val preferences: PreferencesRepository,
) {
    private val filesDir get() = context.filesDir

    /** [fallbackName] names a profile created from the account (Google name or e-mail prefix). */
    suspend fun link(uid: String, accountEmail: String?, fallbackName: String, strategy: MergeStrategy) {
        val local = profiles.observe().first()
        val remoteProfile = remote.load(uid)
        when (decideLink(local, uid, remoteProfile != null, strategy)) {
            LinkDecision.UPLOAD_LOCAL -> uploadLocal(uid, local ?: newProfile(fallbackName), create = remoteProfile == null)
            LinkDecision.APPLY_REMOTE -> applyRemote(uid, local, checkNotNull(remoteProfile))
            LinkDecision.ASK -> throw MergeConflict(checkNotNull(local).summary(), checkNotNull(remoteProfile).summary())
            LinkDecision.OTHER_ACCOUNT -> throw LinkedToOtherAccount(accountEmail)
        }
    }

    /**
     * Runs while [uid] is signed in: remote changes (another phone) are applied here, local changes (name, synced
     * preferences) are written to Firestore, which queues them while offline. Never returns normally.
     */
    suspend fun run(uid: String) {
        var seen: RemoteProfile? = null
        combine(remote.observe(uid), profiles.observe(), preferences.preferences, ::Triple).collect { (remoteProfile, local, prefs) ->
            if (remoteProfile == null || local == null || local.linkedUid != uid) return@collect
            val differs = local.displayName != remoteProfile.displayName || prefs.withSynced(remoteProfile.preferences) != prefs
            if (remoteProfile != seen) {
                seen = remoteProfile
                // ponytail: a picture replaced on another phone arrives with the next sign-in; only a missing one is fetched here.
                val avatar = local.avatarPath ?: remoteProfile.photoPath?.let { download(it) }
                val updated = local.copy(displayName = remoteProfile.displayName.take(PROFILE_NAME_MAX), avatarPath = avatar)
                if (updated != local) profiles.upsert(updated)
                if (differs) preferences.update { it.withSynced(remoteProfile.preferences) }
            } else if (differs) {
                // Unknown keys from newer app versions are kept.
                val synced = remoteProfile.preferences + prefs.toSynced()
                remote.save(uid, local.displayName, remoteProfile.photoPath, synced, create = false)
            }
        }
    }

    private fun newProfile(name: String) =
        LocalProfile(UUID.randomUUID().toString(), name.trim().take(PROFILE_NAME_MAX), null, System.currentTimeMillis(), linkedUid = null)

    private suspend fun uploadLocal(uid: String, profile: LocalProfile, create: Boolean) {
        // The guest copy is the original picture; linked profiles keep (and upload) a downscaled one.
        val small = profile.avatarPath?.let { shrink(it) }
        val photoPath = small?.let { path ->
            attempt("Uploading the profile picture") { remote.uploadAvatar(uid, File(filesDir, path)) }
        }
        val linked = profile.copy(linkedUid = uid, avatarPath = small ?: profile.avatarPath)
        remote.save(uid, linked.displayName, photoPath, preferences.preferences.first().toSynced(), create)
        profiles.upsert(linked)
        if (small != null) profile.avatarPath?.let { File(filesDir, it).delete() }
    }

    private suspend fun applyRemote(uid: String, local: LocalProfile?, remoteProfile: RemoteProfile) {
        val avatar = remoteProfile.photoPath?.let { download(it) }
        val base = local ?: newProfile(remoteProfile.displayName)
        profiles.upsert(base.copy(displayName = remoteProfile.displayName.take(PROFILE_NAME_MAX), avatarPath = avatar, linkedUid = uid))
        preferences.update { it.withSynced(remoteProfile.preferences) }
        local?.avatarPath?.let { File(filesDir, it).delete() }
    }

    private fun LocalProfile.summary() =
        ProfileSummary(displayName, avatarPath?.let { File(filesDir, it) }?.takeIf { it.isFile }, createdAt)

    private suspend fun RemoteProfile.summary(): ProfileSummary {
        val preview = File(context.cacheDir, "account/avatar-preview.jpg")
        val avatar = photoPath?.let { path ->
            attempt("Loading the account's profile picture") {
                preview.parentFile?.mkdirs()
                remote.downloadAvatar(path, preview)
                preview
            }
        }
        return ProfileSummary(displayName, avatar, updatedAt)
    }

    /** Returns the new path relative to filesDir, or null (the original stays). */
    private suspend fun shrink(path: String): String? = attempt("Downscaling the profile picture") {
        val target = "profile/avatar-${UUID.randomUUID()}.jpg"
        withContext(Dispatchers.IO) { writeSmallAvatar(File(filesDir, path), File(filesDir, target)) }
        target
    }

    private suspend fun download(storagePath: String): String? {
        val target = "profile/avatar-${UUID.randomUUID()}.jpg"
        val file = File(filesDir, target)
        return attempt("Downloading the profile picture") {
            file.parentFile?.mkdirs()
            remote.downloadAvatar(storagePath, file)
            target
        }.also { if (it == null) file.delete() }
    }

    /** Pictures are best effort: a missing Storage bucket or a failed transfer never blocks linking or sync. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
        null
    }

    private companion object {
        const val TAG = "ProfileSync"
    }
}
