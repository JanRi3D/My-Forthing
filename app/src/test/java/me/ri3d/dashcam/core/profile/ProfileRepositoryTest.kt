package me.ri3d.dashcam.core.profile

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import me.ri3d.dashcam.core.model.LocalProfile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val picture = Uri.parse("content://media/picker/0/42")
    private val bytes = ByteArray(10_000) { it.toByte() }

    private class FakeDao(private val failing: Boolean = false) : LocalProfileDao {
        val stored = MutableStateFlow<LocalProfile?>(null)
        override fun observe(): Flow<LocalProfile?> = stored
        override suspend fun upsert(profile: LocalProfile) {
            if (failing) throw IllegalStateException("disk full")
            stored.value = profile
        }
    }

    private fun avatarFiles(): List<File> = File(context.filesDir, "profile").listFiles()?.toList().orEmpty()

    @Test
    fun `stores the profile with a copy of the picture`() = runTest {
        shadowOf(context.contentResolver).registerInputStream(picture, ByteArrayInputStream(bytes))
        val dao = FakeDao()

        val profile = ProfileRepository(context, dao).createLocal("  Mein Auto ", picture)

        assertThat(dao.stored.value).isEqualTo(profile)
        assertThat(profile.displayName).isEqualTo("Mein Auto")
        assertThat(profile.avatarPath).isEqualTo("profile/avatar-${profile.id}")
        assertThat(File(context.filesDir, profile.avatarPath!!).readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `without a picture no file is written`() = runTest {
        val profile = ProfileRepository(context, FakeDao()).createLocal("Mein Auto", avatar = null)

        assertThat(profile.avatarPath).isNull()
        assertThat(avatarFiles()).isEmpty()
    }

    @Test
    fun `a read error mid-copy leaves no partial file and no profile`() = runTest {
        val breaking = object : InputStream() {
            private var left = 4_096
            override fun read(): Int = if (left-- > 0) 7 else throw IOException("picker revoked")
        }
        shadowOf(context.contentResolver).registerInputStream(picture, breaking)
        val dao = FakeDao()

        val result = runCatching { ProfileRepository(context, dao).createLocal("Mein Auto", picture) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(dao.stored.value).isNull()
        assertThat(avatarFiles()).isEmpty()
    }

    @Test
    fun `a database error removes the copied picture`() = runTest {
        shadowOf(context.contentResolver).registerInputStream(picture, ByteArrayInputStream(bytes))

        val result = runCatching { ProfileRepository(context, FakeDao(failing = true)).createLocal("Mein Auto", picture) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(avatarFiles()).isEmpty()
    }
}
