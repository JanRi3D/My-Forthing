package me.ri3d.dashcam.account

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.core.log.Log
import me.ri3d.dashcam.core.model.AppPreferences
import me.ri3d.dashcam.core.model.AppTheme
import me.ri3d.dashcam.core.model.ExportQuality
import me.ri3d.dashcam.core.model.LocalProfile
import me.ri3d.dashcam.core.profile.LocalProfileDao
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
        // The phone's synced state right after the last apply or push. A different state now is a local edit; an
        // unchanged one means any difference came from the account. Null at start: the account's copy wins.
        var lastSynced: SyncedState? = null
        var pictureChecked = false
        var pictureCheckedFor: String? = null
        val remoteContent = remote.observe(uid).distinctUntilChangedBy { it?.copy(createdAt = null, updatedAt = null) }
        // Emissions only start a round and the state is re-read, so a write of ours still on its way through Room or
        // DataStore never looks like a user edit. Timestamps are ignored: acknowledgements are not remote changes.
        combine(remoteContent, profiles.observe(), preferences.preferences) { remoteProfile, _, _ -> remoteProfile }.collect { remoteProfile ->
            if (remoteProfile == null) return@collect
            val local = linkedProfile(uid) ?: return@collect
            val prefs = preferences.preferences.first()
            val state = SyncedState(local.displayName, prefs.toSynced())
            val remoteState = SyncedState(remoteProfile.displayName.take(PROFILE_NAME_MAX), prefs.withSynced(remoteProfile.preferences).toSynced())
            lastSynced = when {
                state == remoteState -> state
                lastSynced == null || state == lastSynced -> {
                    if (local.displayName != remoteState.displayName) profiles.upsert(local.copy(displayName = remoteState.displayName))
                    preferences.update { it.withSynced(remoteProfile.preferences) }
                    remoteState
                }
                else -> {
                    // Unknown keys from newer app versions are kept.
                    remote.save(uid, local.displayName, remoteProfile.photoPath, remoteProfile.preferences + state.preferences, create = false)
                    state
                }
            }
            if (!pictureChecked || remoteProfile.photoPath != pictureCheckedFor) {
                pictureChecked = true
                pictureCheckedFor = remoteProfile.photoPath
                syncPicture(uid, remoteProfile)
            }
        }
    }

    private data class SyncedState(val displayName: String, val preferences: Map<String, String>)

    private suspend fun linkedProfile(uid: String) = profiles.observe().first()?.takeIf { it.linkedUid == uid }

    /**
     * Fetches the account's picture when the phone has none, or uploads the phone's when the account has none
     * (Storage enabled later, or an earlier upload failed).
     */
    // ponytail: a picture replaced on another phone arrives with the next sign-in; only a missing one is fetched here.
    private suspend fun syncPicture(uid: String, remoteProfile: RemoteProfile) {
        val local = linkedProfile(uid) ?: return
        val photoPath = remoteProfile.photoPath
        val localPath = local.avatarPath
        if (localPath == null && photoPath != null) {
            val downloaded = download(photoPath) ?: return
            keepOrDelete(downloaded) { profiles.upsert(local.copy(avatarPath = downloaded)) }
        } else if (localPath != null && photoPath == null) {
            val small = ensureSmall(localPath) ?: return
            val uploaded = attempt("Uploading the profile picture") { remote.uploadAvatar(uid, File(filesDir, small)) }
            if (small != localPath) {
                keepOrDelete(small) { profiles.upsert(local.copy(avatarPath = small)) }
                File(filesDir, localPath).delete()
            }
            if (uploaded != null) {
                val synced = remoteProfile.preferences + preferences.preferences.first().toSynced()
                remote.save(uid, local.displayName, uploaded, synced, create = false)
            }
        }
    }

    private fun newProfile(name: String) =
        LocalProfile(UUID.randomUUID().toString(), name.trim().take(PROFILE_NAME_MAX), null, System.currentTimeMillis(), linkedUid = null)

    private suspend fun uploadLocal(uid: String, profile: LocalProfile, create: Boolean) {
        // The guest copy is the original picture; linked profiles keep (and upload) one of at most 512 px.
        val small = profile.avatarPath?.let { ensureSmall(it) }
        val created = small?.takeIf { it != profile.avatarPath }
        val photoPath = small?.let { path -> attempt("Uploading the profile picture") { remote.uploadAvatar(uid, File(filesDir, path)) } }
        val linked = profile.copy(linkedUid = uid, avatarPath = small ?: profile.avatarPath)
        keepOrDelete(created) {
            remote.save(uid, linked.displayName, photoPath, preferences.preferences.first().toSynced(), create)
            profiles.upsert(linked)
        }
        // The original is replaced by the downscaled copy.
        profile.avatarPath?.takeIf { created != null }?.let { File(filesDir, it).delete() }
    }

    private suspend fun applyRemote(uid: String, local: LocalProfile?, remoteProfile: RemoteProfile) {
        val downloaded = remoteProfile.photoPath?.let { download(it) }
        // Without a new picture the same account keeps the phone's copy: the account may just have none in Storage.
        val avatar = downloaded ?: local?.avatarPath?.takeIf { local.linkedUid == uid }
        val base = local ?: newProfile(remoteProfile.displayName)
        keepOrDelete(downloaded) {
            profiles.upsert(base.copy(displayName = remoteProfile.displayName.take(PROFILE_NAME_MAX), avatarPath = avatar, linkedUid = uid))
        }
        preferences.update { it.withSynced(remoteProfile.preferences) }
        // ponytail: a picture dropped without a replacement (guest or other account) stays on disk unreferenced.
        if (downloaded != null) local?.avatarPath?.let { File(filesDir, it).delete() }
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

    /** The phone's picture as a JPEG of at most 512 px: the same file if it already is one, else a new copy (or null). */
    private suspend fun ensureSmall(path: String): String? {
        val source = File(filesDir, path)
        if (withContext(Dispatchers.IO) { isSmallJpeg(source) }) return path
        return newPicture("Downscaling the profile picture") { withContext(Dispatchers.IO) { writeSmallAvatar(source, it) } }
    }

    private suspend fun download(storagePath: String): String? =
        newPicture("Downloading the profile picture") { remote.downloadAvatar(storagePath, it) }

    /** Writes a new picture file and returns its path relative to filesDir; on failure or cancellation nothing is left. */
    private suspend fun newPicture(what: String, write: suspend (File) -> Unit): String? {
        val path = "profile/avatar-${UUID.randomUUID()}.jpg"
        val file = File(filesDir, path)
        var written = false
        try {
            return attempt(what) {
                file.parentFile?.mkdirs()
                write(file)
                written = true
                path
            }
        } finally {
            if (!written) file.delete()
        }
    }

    /** Runs [save]; if it fails or is cancelled, the new picture at [path] would be orphaned and is deleted. */
    private inline fun keepOrDelete(path: String?, save: () -> Unit) {
        try {
            save()
        } catch (e: Throwable) {
            path?.let { File(filesDir, it).delete() }
            throw e
        }
    }

    /** Pictures are best effort: a missing Storage bucket or a failed transfer never blocks linking or sync. */
    private inline fun <T> attempt(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed: ${e.logLabel()}")
        null
    }

    private companion object {
        const val TAG = "ProfileSync"
    }
}
