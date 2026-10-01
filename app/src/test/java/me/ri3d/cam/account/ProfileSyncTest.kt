package me.ri3d.cam.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.model.AppPreferences
import me.ri3d.cam.core.model.AppTheme
import me.ri3d.cam.core.model.ExportQuality
import me.ri3d.cam.core.model.LocalProfile
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val guest = LocalProfile("p1", "Mein Auto", avatarPath = null, createdAt = 5L, linkedUid = null)
    private val accountProfile = RemoteProfile("Janes Auto", photoPath = null, mapOf("theme" to "BLACK", "exportQuality" to "Q1080"), 2L, 3L)

    /** Local preferences with device-local flags set, to prove they never change through sync. */
    private val localPrefs = AppPreferences(theme = AppTheme.MATERIAL_YOU, platesLive = true, backupOnMobileData = true)

    private class Env(val sync: ProfileSync, val dao: FakeProfileDao, val remote: FakeProfileRemote, val prefs: PreferencesRepository)

    private suspend fun TestScope.env(local: LocalProfile?, remote: Map<String, RemoteProfile> = emptyMap()): Env {
        val prefs = PreferencesRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "sync.preferences_pb") })
        prefs.update { localPrefs }
        val dao = FakeProfileDao(local)
        val fakeRemote = FakeProfileRemote(remote)
        return Env(ProfileSync(context, fakeRemote, dao, prefs), dao, fakeRemote, prefs)
    }

    @Test
    fun `empty account - the guest profile and synced preferences move in`() = runTest {
        val env = env(guest)

        env.sync.link("me", "jane@example.com", "jane", MergeStrategy.ASK)

        assertThat(env.dao.stored.value).isEqualTo(guest.copy(linkedUid = "me"))
        val saved = env.remote.docs.value.getValue("me")
        assertThat(saved.displayName).isEqualTo("Mein Auto")
        assertThat(saved.preferences).containsExactly("theme", "MATERIAL_YOU", "exportQuality", "Q1440")
        assertThat(saved.createdAt).isEqualTo(FakeProfileRemote.NOW)
    }

    @Test
    fun `conflict - nothing changes until the user chooses`() = runTest {
        val env = env(guest, mapOf("me" to accountProfile))

        val error = runCatching { env.sync.link("me", "jane@example.com", "jane", MergeStrategy.ASK) }.exceptionOrNull()

        assertThat(error).isInstanceOf(MergeConflict::class.java)
        error as MergeConflict
        assertThat(error.local).isEqualTo(ProfileSummary("Mein Auto", null, 5L))
        assertThat(error.remote).isEqualTo(ProfileSummary("Janes Auto", null, 3L))
        assertThat(env.dao.stored.value).isEqualTo(guest)
        assertThat(env.remote.saves).isEmpty()
        assertThat(env.prefs.preferences.first()).isEqualTo(localPrefs)
    }

    @Test
    fun `keep remote - account data replaces the phone's, device-local flags stay`() = runTest {
        val env = env(guest, mapOf("me" to accountProfile))

        env.sync.link("me", null, "jane", MergeStrategy.KEEP_REMOTE)

        assertThat(env.dao.stored.value).isEqualTo(guest.copy(displayName = "Janes Auto", linkedUid = "me"))
        assertThat(env.prefs.preferences.first()).isEqualTo(localPrefs.copy(theme = AppTheme.BLACK, exportQuality = ExportQuality.Q1080))
        assertThat(env.remote.saves).isEmpty()
    }

    @Test
    fun `keep local - the account is overwritten but keeps its creation date`() = runTest {
        val env = env(guest, mapOf("me" to accountProfile))

        env.sync.link("me", null, "jane", MergeStrategy.KEEP_LOCAL)

        val saved = env.remote.docs.value.getValue("me")
        assertThat(saved.displayName).isEqualTo("Mein Auto")
        assertThat(saved.createdAt).isEqualTo(2L)
        assertThat(env.dao.stored.value?.linkedUid).isEqualTo("me")
        assertThat(env.prefs.preferences.first()).isEqualTo(localPrefs)
    }

    @Test
    fun `profile of another account - explained, not touched`() = runTest {
        val other = guest.copy(linkedUid = "other")
        val env = env(other, mapOf("me" to accountProfile))

        val error = runCatching { env.sync.link("me", "jane@example.com", "jane", MergeStrategy.ASK) }.exceptionOrNull()

        assertThat((error as LinkedToOtherAccount).accountEmail).isEqualTo("jane@example.com")
        assertThat(env.dao.stored.value).isEqualTo(other)
        assertThat(env.remote.saves).isEmpty()
    }

    @Test
    fun `fresh install with an empty account creates the profile from the account name`() = runTest {
        val env = env(local = null)

        env.sync.link("me", "jane@example.com", "jane", MergeStrategy.ASK)

        val created = env.dao.stored.value!!
        assertThat(created.displayName).isEqualTo("jane")
        assertThat(created.linkedUid).isEqualTo("me")
        assertThat(env.remote.docs.value.getValue("me").displayName).isEqualTo("jane")
    }

    @Test
    fun `a failed server read links nothing`() = runTest {
        val env = env(guest)
        env.remote.loadError = IllegalStateException("offline")

        assertThat(runCatching { env.sync.link("me", null, "jane", MergeStrategy.ASK) }.isFailure).isTrue()
        assertThat(env.dao.stored.value).isEqualTo(guest)
        assertThat(env.remote.saves).isEmpty()
    }

    @Test
    fun `linking downscales the guest picture and uploads it`() = runTest {
        val original = File(context.filesDir, "profile/avatar-p1").apply { parentFile?.mkdirs() }
        original.outputStream().use { Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val env = env(guest.copy(avatarPath = "profile/avatar-p1"))

        env.sync.link("me", null, "jane", MergeStrategy.ASK)

        val linked = env.dao.stored.value!!
        val small = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(File(context.filesDir, linked.avatarPath!!).path, it) }
        assertThat(small.outWidth to small.outHeight).isEqualTo(AVATAR_MAX_PX to AVATAR_MAX_PX / 2)
        assertThat(original.exists()).isFalse()
        assertThat(env.remote.docs.value.getValue("me").photoPath).isEqualTo("users/me/avatar.jpg")
    }

    @Test
    fun `while signed in - remote changes apply here and local edits go up without echo`() = runTest {
        val linked = guest.copy(displayName = "Janes Auto", linkedUid = "me")
        val env = env(linked, mapOf("me" to accountProfile.copy(preferences = localPrefs.toSynced())))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { env.sync.run("me") }

        // Another phone switches to the black theme.
        env.remote.docs.value = mapOf("me" to accountProfile)
        assertThat(env.prefs.preferences.first { it.theme == AppTheme.BLACK }).isEqualTo(
            localPrefs.copy(theme = AppTheme.BLACK, exportQuality = ExportQuality.Q1080),
        )
        assertThat(env.remote.saves).isEmpty()

        // Renamed on this phone.
        env.dao.upsert(env.dao.stored.value!!.copy(displayName = "Familienauto"))
        assertThat(env.remote.docs.first { it["me"]?.displayName == "Familienauto" }.getValue("me").preferences)
            .containsExactly("theme", "BLACK", "exportQuality", "Q1080")
        assertThat(env.remote.saves).hasSize(1)
    }

    @Test
    fun `signing in again keeps the phone's picture when the account has none`() = runTest {
        writeJpeg("profile/avatar-p1", 2000, 1000)
        val env = env(guest.copy(avatarPath = "profile/avatar-p1"))
        env.remote.failTransfers = true // no Storage: the upload fails, the account has no photoPath

        env.sync.link("me", null, "jane", MergeStrategy.ASK)
        val picture = env.dao.stored.value!!.avatarPath!!
        assertThat(env.remote.docs.value.getValue("me").photoPath).isNull()

        env.sync.link("me", null, "jane", MergeStrategy.ASK) // same account, account copy applied

        assertThat(env.dao.stored.value!!.avatarPath).isEqualTo(picture)
        assertThat(File(context.filesDir, picture).isFile).isTrue()
    }

    @Test
    fun `while signed in - the phone's picture goes up once Storage works, without re-encoding`() = runTest {
        writeJpeg("profile/avatar-small.jpg", 400, 300)
        val linked = guest.copy(displayName = "Janes Auto", avatarPath = "profile/avatar-small.jpg", linkedUid = "me")
        val env = env(linked, mapOf("me" to accountProfile.copy(preferences = localPrefs.toSynced())))

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { env.sync.run("me") }

        assertThat(env.remote.docs.first { it["me"]?.photoPath != null }.getValue("me").photoPath).isEqualTo("users/me/avatar.jpg")
        assertThat(env.remote.uploads).containsExactly(File(context.filesDir, "profile/avatar-small.jpg"))
        assertThat(env.dao.stored.value!!.avatarPath).isEqualTo("profile/avatar-small.jpg")
    }

    @Test
    fun `while signed in - a remote change of name and preference together is not reverted`() = runTest {
        val linked = guest.copy(displayName = "Janes Auto", linkedUid = "me")
        val env = env(linked, mapOf("me" to accountProfile.copy(preferences = localPrefs.toSynced())))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { env.sync.run("me") }

        env.remote.docs.value = mapOf("me" to accountProfile.copy(displayName = "Familienauto"))

        assertThat(env.prefs.preferences.first { it.theme == AppTheme.BLACK }.exportQuality).isEqualTo(ExportQuality.Q1080)
        assertThat(env.dao.stored.first { it?.displayName == "Familienauto" }).isNotNull()
        withContext(Dispatchers.Default) { delay(300) } // real time: a wrong push would come from a round still in flight
        assertThat(env.remote.saves).isEmpty()
        assertThat(env.remote.docs.value.getValue("me").preferences).containsExactly("theme", "BLACK", "exportQuality", "Q1080")
    }

    private fun writeJpeg(path: String, width: Int, height: Int) {
        val file = File(context.filesDir, path).apply { parentFile?.mkdirs() }
        file.outputStream().use { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test
    fun `sample size keeps the long side at or above the target`() {
        assertThat(sampleSize(4000, 3000, 512)).isEqualTo(4)
        assertThat(sampleSize(512, 512, 512)).isEqualTo(1)
        assertThat(sampleSize(100, 80, 512)).isEqualTo(1)
    }
}
