package me.ri3d.dashcam.account

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.core.model.LocalProfile
import me.ri3d.dashcam.core.ui.UiText
import java.io.File

/** A build without `app/google-services.json`: guest mode only, Firebase never initialised. */
@RunWith(RobolectricTestRunner::class)
class NotConfiguredTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `options need project, app id and API key`() {
        assertThat(firebaseOptions("", "1:1:android:1", "key", "")).isNull()
        assertThat(firebaseOptions("p", "", "key", "bucket")).isNull()
        assertThat(firebaseOptions("p", "1:1:android:1", " ", "bucket")).isNull()
        assertThat(firebaseOptions("p", "1:1:android:1", "key", "")?.storageBucket).isNull()
        assertThat(firebaseOptions("p", "1:1:android:1", "key", "b")?.projectId).isEqualTo("p")
    }

    @Test
    fun `every account action fails with AccountNotConfigured and the state stays Guest`() = runTest {
        val dao = FakeProfileDao(LocalProfile("p", "Mein Auto", null, 1L, linkedUid = "me"))
        val prefs = PreferencesRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "nc.preferences_pb") })
        val repository = FirebaseAccountRepository(
            context,
            FirebaseHandles(context, options = null, webClientId = ""),
            ProfileSync(context, FakeProfileRemote(), dao, prefs),
            dao,
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        assertThat(repository.isConfigured).isFalse()
        val results = listOf(
            repository.signInGoogle(activity),
            repository.signInEmail("jane@example.com", "secret123"),
            repository.createEmail("jane@example.com", "secret123"),
            repository.sendPasswordReset("jane@example.com"),
            repository.sendVerification(),
            repository.reloadVerification(),
            repository.linkGuestProfile(MergeStrategy.ASK),
        )
        repository.signOut()

        results.forEach { assertThat(it.exceptionOrNull()).isSameInstanceAs(AccountNotConfigured) }
        assertThat(repository.state.value).isEqualTo(AccountState.Guest)
        assertThat(FirebaseApp.getApps(context)).isEmpty()
        assertThat(AccountNotConfigured.toAccountMessage()).isEqualTo(UiText.Res(R.string.account_not_configured))
    }
}
