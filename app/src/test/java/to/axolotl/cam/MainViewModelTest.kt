package to.axolotl.cam

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.axolotl.cam.core.data.PreferencesRepository
import to.axolotl.cam.core.model.AppTheme
import to.axolotl.cam.core.model.LocalProfile
import to.axolotl.cam.core.navigation.Home
import to.axolotl.cam.core.navigation.Welcome
import to.axolotl.cam.core.profile.LocalProfileDao
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val profile = LocalProfile("id", "Mein Auto", avatarPath = null, createdAt = 1L, linkedUid = null)

    private class FakeDao(initial: LocalProfile?) : LocalProfileDao {
        val flow = MutableStateFlow(initial)
        override fun observe(): Flow<LocalProfile?> = flow
        override suspend fun upsert(profile: LocalProfile) {
            flow.value = profile
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.preferences() = PreferencesRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "main.preferences_pb") },
    )

    @Test
    fun `without a profile the app starts at Welcome`() = runTest {
        val state = MainViewModel(preferences(), FakeDao(null)).state.filterNotNull().first()

        assertThat(state.startDestination).isEqualTo(Welcome)
        assertThat(state.theme).isEqualTo(AppTheme.MATERIAL_YOU)
    }

    @Test
    fun `with a profile the app starts at Home`() = runTest {
        val state = MainViewModel(preferences(), FakeDao(profile)).state.filterNotNull().first()

        assertThat(state.startDestination).isEqualTo(Home)
    }

    @Test
    fun `start destination stays fixed while the theme follows preferences`() = runTest {
        val preferences = preferences()
        val dao = FakeDao(null)
        val viewModel = MainViewModel(preferences, dao)
        viewModel.state.filterNotNull().first()

        dao.upsert(profile)
        preferences.update { it.copy(theme = AppTheme.BLACK) }
        val state = viewModel.state.filterNotNull().first { it.theme == AppTheme.BLACK }

        assertThat(state.startDestination).isEqualTo(Welcome)
    }
}
