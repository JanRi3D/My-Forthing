package me.ri3d.cam.core.profile

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ri3d.cam.core.model.LocalProfile
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LocalProfileDao,
) {
    /**
     * Creates the guest profile. The picked picture is copied into app storage because
     * Photo Picker read grants do not survive the process. On any failure no partial avatar file is left behind.
     */
    suspend fun createLocal(displayName: String, avatar: Uri?): LocalProfile = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val avatarPath = "profile/avatar-$id"
        val avatarFile = File(context.filesDir, avatarPath)
        try {
            if (avatar != null) {
                avatarFile.parentFile?.mkdirs()
                // ponytail: copies the original bytes without downscaling; resize when avatars get uploaded (accounts feature).
                val input = context.contentResolver.openInputStream(avatar) ?: throw FileNotFoundException("avatar")
                input.use { source -> avatarFile.outputStream().use { source.copyTo(it) } }
            }
            LocalProfile(id, displayName.trim(), avatarPath.takeIf { avatar != null }, System.currentTimeMillis(), linkedUid = null)
                .also { dao.upsert(it) }
        } catch (e: Throwable) {
            avatarFile.delete()
            throw e
        }
    }
}
