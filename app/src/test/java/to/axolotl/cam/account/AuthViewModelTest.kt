package to.axolotl.cam.account

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import to.axolotl.cam.R
import to.axolotl.cam.core.ui.UiText

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeAccountRepository()
    private val local = ProfileSummary("Mein Auto", null, 1L)
    private val remote = ProfileSummary("Janes Auto", null, 2L)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(accounts: FakeAccountRepository = repo, handle: SavedStateHandle = SavedStateHandle()) =
        AuthViewModel(accounts, FakeProfileDao(), handle)

    private fun AuthViewModel.fill(email: String = "jane@example.com", password: String = "secret123") {
        onEmail(email)
        onPassword(password)
    }

    @Test
    fun `invalid input shows field errors only after submitting and calls nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.fill(email = "jane@", password = "short")
        assertThat(vm.emailError).isNull()

        vm.create()
        advanceUntilIdle()

        assertThat(vm.emailError).isEqualTo(R.string.account_error_email_format)
        assertThat(vm.passwordError(newPassword = true)).isEqualTo(R.string.account_error_password_short)
        assertThat(vm.termsError).isEqualTo(R.string.account_error_terms)
        assertThat(repo.calls).isEmpty()
    }

    @Test
    fun `sign-in needs an address and any password, then links and goes home`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.fill(password = "")
        vm.signIn()
        assertThat(vm.passwordError(newPassword = false)).isEqualTo(R.string.account_error_password_empty)

        vm.onPassword("x")
        vm.signIn()
        advanceUntilIdle()

        assertThat(repo.calls).containsExactly("signIn:jane@example.com", "link:ASK", "reload").inOrder()
        assertThat(vm.done).isEqualTo(AuthDone.HOME)
    }

    @Test
    fun `an unverified address gets a fresh link and the verification step`() = runTest(dispatcher) {
        repo.verificationResults.apply { clear(); add(Result.success(false)) }
        val vm = viewModel()
        vm.fill()

        vm.signIn()
        advanceUntilIdle()

        assertThat(repo.calls).containsExactly("signIn:jane@example.com", "link:ASK", "reload", "sendVerification").inOrder()
        assertThat(vm.done).isEqualTo(AuthDone.VERIFY_EMAIL)
    }

    @Test
    fun `creating an account links the profile and continues to verification`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.fill()
        vm.onTerms(true)

        vm.create()
        advanceUntilIdle()

        assertThat(repo.calls).containsExactly("create:jane@example.com", "link:ASK").inOrder()
        assertThat(vm.done).isEqualTo(AuthDone.VERIFY_EMAIL)
        assertThat(vm.busy).isFalse()
    }

    @Test
    fun `without configuration the not-configured text is shown`() = runTest(dispatcher) {
        val vm = viewModel(FakeAccountRepository(isConfigured = false))
        vm.fill()

        vm.signIn()
        advanceUntilIdle()

        assertThat(vm.configured).isFalse()
        assertThat(vm.message).isEqualTo(UiText.Res(R.string.account_not_configured))
        assertThat(vm.done).isNull()
    }

    @Test
    fun `wrong password shows a message and links nothing`() = runTest(dispatcher) {
        repo.signInResult = Result.failure(FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "bad"))
        val vm = viewModel()
        vm.fill()

        vm.signIn()
        advanceUntilIdle()

        assertThat(vm.message).isEqualTo(UiText.Res(R.string.account_error_wrong_credentials))
        assertThat(repo.calls).containsExactly("signIn:jane@example.com")
        vm.onPassword("other")
        assertThat(vm.message).isNull()
    }

    @Test
    fun `a conflict waits for the user's choice`() = runTest(dispatcher) {
        repo.linkResults.apply { clear(); add(Result.failure(MergeConflict(local, remote))); add(Result.success(Unit)) }
        val vm = viewModel()

        vm.signInGoogle(Robolectric.buildActivity(Activity::class.java).get(), needsTerms = false)
        advanceUntilIdle()
        assertThat(vm.prompt).isEqualTo(LinkPrompt.Choose(local, remote))
        assertThat(vm.done).isNull()

        vm.choose(MergeStrategy.KEEP_REMOTE)
        advanceUntilIdle()

        assertThat(repo.calls).containsExactly("google", "link:ASK", "link:KEEP_REMOTE").inOrder()
        assertThat(vm.prompt).isNull()
        assertThat(vm.done).isEqualTo(AuthDone.HOME)
    }

    @Test
    fun `declining the chooser signs out again`() = runTest(dispatcher) {
        repo.linkResults.apply { clear(); add(Result.failure(MergeConflict(local, remote))) }
        val vm = viewModel()
        vm.fill()
        vm.signIn()
        advanceUntilIdle()

        vm.dismissPrompt()
        advanceUntilIdle()

        assertThat(vm.prompt).isNull()
        assertThat(repo.calls.last()).isEqualTo("signOut")
        assertThat(vm.done).isNull()
    }

    @Test
    fun `another account's profile - switching links with KEEP_REMOTE`() = runTest(dispatcher) {
        repo.linkResults.apply { clear(); add(Result.failure(LinkedToOtherAccount("jane@example.com"))); add(Result.success(Unit)) }
        val vm = viewModel()
        vm.fill()
        vm.signIn()
        advanceUntilIdle()
        assertThat(vm.prompt).isEqualTo(LinkPrompt.OtherAccount("jane@example.com"))

        vm.choose(MergeStrategy.KEEP_REMOTE)
        advanceUntilIdle()

        assertThat(repo.calls).contains("link:KEEP_REMOTE")
        assertThat(vm.done).isEqualTo(AuthDone.HOME)
    }

    @Test
    fun `a failed link signs out and shows why`() = runTest(dispatcher) {
        repo.linkResults.apply { clear(); add(Result.failure(FirebaseNetworkException("offline"))) }
        val vm = viewModel()
        vm.fill()

        vm.signIn()
        advanceUntilIdle()

        assertThat(repo.calls.last()).isEqualTo("signOut")
        assertThat(vm.message).isEqualTo(UiText.Res(R.string.account_error_network))
        assertThat(vm.done).isNull()
    }

    @Test
    fun `Google on the create screens requires the terms`() = runTest(dispatcher) {
        val vm = viewModel()
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        vm.signInGoogle(activity, needsTerms = true)
        advanceUntilIdle()
        assertThat(vm.termsError).isEqualTo(R.string.account_error_terms)
        assertThat(vm.emailError).isNull() // the e-mail form is not in use
        assertThat(repo.calls).isEmpty()

        vm.onTerms(true)
        vm.signInGoogle(activity, needsTerms = true)
        advanceUntilIdle()
        assertThat(vm.done).isEqualTo(AuthDone.HOME)
    }

    @Test
    fun `reset link - resend waits for the cooldown`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEmail("jane@example.com")

        vm.sendReset()
        runCurrent()
        assertThat(vm.done).isEqualTo(AuthDone.RESET_SENT)
        assertThat(vm.resendCooldown.secondsLeft).isEqualTo(RESEND_COOLDOWN_S)

        vm.resendReset()
        runCurrent()
        assertThat(repo.calls).containsExactly("reset:jane@example.com")

        advanceTimeBy(RESEND_COOLDOWN_S * 1_000L + 1)
        assertThat(vm.resendCooldown.secondsLeft).isEqualTo(0)
        vm.resendReset()
        runCurrent()
        assertThat(repo.calls).containsExactly("reset:jane@example.com", "reset:jane@example.com")
        assertThat(vm.message).isEqualTo(UiText.Res(R.string.account_link_sent))
    }

    @Test
    fun `the address survives process death, the password does not`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        viewModel(handle = handle).fill()

        val restored = viewModel(handle = handle)

        assertThat(restored.email).isEqualTo("jane@example.com")
        assertThat(restored.password).isEmpty()
        assertThat(handle.keys()).containsExactly("email")
    }
}
