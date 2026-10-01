package to.axolotl.cam.core.profile

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import to.axolotl.cam.core.data.AppDatabase
import to.axolotl.cam.core.model.LocalProfile

@RunWith(RobolectricTestRunner::class)
class LocalProfileDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.localProfileDao()

    @After
    fun tearDown() = db.close()

    @Test
    fun `observe is null before onboarding`() = runTest {
        assertThat(dao.observe().first()).isNull()
    }

    @Test
    fun `upsert stores and replaces the profile`() = runTest {
        val guest = LocalProfile("p1", "Mein Auto", avatarPath = "profile/avatar-p1", createdAt = 10L, linkedUid = null)
        dao.upsert(guest)
        assertThat(dao.observe().first()).isEqualTo(guest)

        val linked = guest.copy(displayName = "Firmenwagen", linkedUid = "uid-1")
        dao.upsert(linked)
        assertThat(dao.observe().first()).isEqualTo(linked)
    }
}
