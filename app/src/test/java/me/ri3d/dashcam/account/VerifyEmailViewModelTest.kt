package me.ri3d.dashcam.account

import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.UiText

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VerifyEmailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeAccountRepository().apply {
        state.value = AccountState.SignedIn("me", "jane@example.com", emailVerified = false, "Mein Auto", null, online = true)
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun verifications(results: List<Result<Boolean>>) {
        repo.verificationResults.clear()
        repo.verificationResults.addAll(results)
    }

    @Test
    fun `shows the address of the signed-in account`() = runTest(dispatcher) {
        assertThat(VerifyEmailViewModel(repo).email.value).isEqualTo("jane@example.com")
    }

    @Test
    fun `resend waits for the cooldown that starts with the screen`() = runTest(dispatcher) {
        val vm = VerifyEmailViewModel(repo)
        assertThat(vm.resendCooldown.secondsLeft).isEqualTo(RESEND_COOLDOWN_S)

        vm.resend()
        runCurrent()
        assertThat(repo.calls).isEmpty()

        advanceTimeBy(30_001)
        assertThat(vm.resendCooldown.secondsLeft).isEqualTo(30)
        advanceTimeBy(30_001)
        vm.resend()
        runCurrent()

        assertThat(repo.calls).containsExactly("sendVerification")
        assertThat(vm.message).isEqualTo(UiText.Res(R.string.account_link_sent))
        assertThat(vm.resendCooldown.secondsLeft).isEqualTo(RESEND_COOLDOWN_S)
    }

    @Test
    fun `polling checks at once and then every few seconds until verified`() = runTest(dispatcher) {
        verifications(listOf(Result.success(false), Result.success(false), Result.success(true)))
        val vm = VerifyEmailViewModel(repo)
        val polling = backgroundScope.launch { vm.pollVerification() }

        runCurrent()
        assertThat(repo.calls).containsExactly("reload")
        advanceTimeBy(VERIFY_POLL_MS)
        runCurrent()
        assertThat(repo.calls).hasSize(2)
        assertThat(vm.verified).isFalse()
        advanceTimeBy(VERIFY_POLL_MS)
        runCurrent()

        assertThat(repo.calls).hasSize(3)
        assertThat(vm.verified).isTrue()
        assertThat(polling.isCompleted).isTrue()
    }

    @Test
    fun `polling rides out being offline`() = runTest(dispatcher) {
        verifications(listOf(Result.failure(FirebaseNetworkException("offline")), Result.success(true)))
        val vm = VerifyEmailViewModel(repo)
        backgroundScope.launch { vm.pollVerification() }

        runCurrent()
        assertThat(vm.verified).isFalse()
        assertThat(vm.message).isNull()
        advanceTimeBy(VERIFY_POLL_MS)
        runCurrent()

        assertThat(vm.verified).isTrue()
    }

    @Test
    fun `confirm before clicking the link explains what to do`() = runTest(dispatcher) {
        verifications(listOf(Result.success(false)))
        val vm = VerifyEmailViewModel(repo)

        vm.confirm()
        runCurrent()

        assertThat(vm.verified).isFalse()
        assertThat(vm.message).isEqualTo(UiText.Res(R.string.verify_not_yet))
        assertThat(vm.busy).isFalse()
    }

    @Test
    fun `confirm after clicking the link continues`() = runTest(dispatcher) {
        val vm = VerifyEmailViewModel(repo)

        vm.confirm()
        runCurrent()

        assertThat(vm.verified).isTrue()
    }
}
