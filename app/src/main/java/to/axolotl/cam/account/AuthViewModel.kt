package to.axolotl.cam.account

import android.app.Activity
import android.util.Patterns
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.axolotl.cam.R
import to.axolotl.cam.core.model.LocalProfile
import to.axolotl.cam.core.profile.LocalProfileDao
import to.axolotl.cam.core.ui.UiText
import javax.inject.Inject

/** Firebase enforces its own limit (6); the app asks for 8 as drawn. */
internal const val PASSWORD_MIN_LENGTH = 8

/** Resend cooldown for verification and reset mails (Firebase rate-limits them anyway). */
internal const val RESEND_COOLDOWN_S = 60

/** Where an auth screen goes next. */
enum class AuthDone { HOME, VERIFY_EMAIL, RESET_SENT }

/** Asked after sign-in when the profile cannot be linked without the user's decision. */
sealed interface LinkPrompt {
    data class Choose(val local: ProfileSummary, val remote: ProfileSummary) : LinkPrompt
    data class OtherAccount(val accountEmail: String?) : LinkPrompt
}

internal fun isEmail(text: String) = Patterns.EMAIL_ADDRESS.matcher(text.trim()).matches()

/**
 * State machine shared by Anmelden, Konto erstellen, Online-Konto hinzufügen and Passwort vergessen (each screen has
 * its own instance). Every successful sign-in links the profile before leaving; a link that cannot complete signs out
 * again, so no half-linked session stays behind.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val accounts: AccountRepository,
    profiles: LocalProfileDao,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val configured = accounts.isConfigured
    val profile: StateFlow<LocalProfile?> = profiles.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Onboarding only: before a profile exists, "Offline nutzen" is still an option (hidden until known). */
    val canContinueOffline: StateFlow<Boolean> =
        profiles.observe().map { it == null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Only the e-mail address survives process death; the password never goes into saved state.
    var email by mutableStateOf(savedState.get<String>(KEY_EMAIL).orEmpty())
        private set
    var password by mutableStateOf("")
        private set
    var termsAccepted by mutableStateOf(false)
        private set

    /** Field errors appear after the first submit. */
    private var validate by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<UiText?>(null)
        private set
    var prompt by mutableStateOf<LinkPrompt?>(null)
        private set
    var done by mutableStateOf<AuthDone?>(null)
        private set
    val resendCooldown = Cooldown(viewModelScope)

    private var afterLink: AuthDone? = null

    @get:StringRes
    val emailError: Int?
        get() = R.string.account_error_email_format.takeIf { validate && !isEmail(email) }

    @StringRes
    fun passwordError(newPassword: Boolean): Int? = when {
        !validate -> null
        password.isEmpty() -> R.string.account_error_password_empty
        newPassword && password.length < PASSWORD_MIN_LENGTH -> R.string.account_error_password_short
        else -> null
    }

    @get:StringRes
    val termsError: Int?
        get() = R.string.account_error_terms.takeIf { validate && !termsAccepted }

    fun onEmail(value: String) {
        email = value
        savedState[KEY_EMAIL] = value
        message = null
    }

    fun onPassword(value: String) {
        password = value
        message = null
    }

    fun onTerms(accepted: Boolean) {
        termsAccepted = accepted
    }

    /** An unverified address gets a fresh link and the verification step after linking. */
    fun signIn() = submit(newPassword = false, needsTerms = false) {
        accounts.signInEmail(email, password).then { link(MergeStrategy.ASK, next = null) }
    }

    /** Konto erstellen / Online-Konto hinzufügen with e-mail: the new account is empty, so the profile moves in. */
    fun create() = submit(newPassword = true, needsTerms = true) {
        accounts.createEmail(email, password).then { link(MergeStrategy.ASK, next = AuthDone.VERIFY_EMAIL) }
    }

    fun signInGoogle(activity: Activity, needsTerms: Boolean) {
        if (busy) return
        if (needsTerms && !termsAccepted) {
            validate = true
            return
        }
        run { accounts.signInGoogle(activity).then { link(MergeStrategy.ASK, next = AuthDone.HOME) } }
    }

    fun sendReset() {
        validate = true
        if (busy || emailError != null) return
        run {
            accounts.sendPasswordReset(email).onSuccess {
                resendCooldown.start(RESEND_COOLDOWN_S)
                done = AuthDone.RESET_SENT
            }
        }
    }

    fun resendReset() {
        if (busy || resendCooldown.secondsLeft > 0) return
        run {
            accounts.sendPasswordReset(email).onSuccess {
                resendCooldown.start(RESEND_COOLDOWN_S)
                message = UiText.Res(R.string.account_link_sent)
            }
        }
    }

    fun choose(strategy: MergeStrategy) {
        prompt = null
        run { link(strategy, next = afterLink) }
    }

    /** The user declined the chooser or the account switch: back to the state before signing in. */
    fun dismissPrompt() {
        prompt = null
        viewModelScope.launch { accounts.signOut() }
    }

    fun navigated() {
        done = null
    }

    private fun submit(newPassword: Boolean, needsTerms: Boolean, action: suspend () -> Result<Unit>) {
        validate = true
        if (busy || emailError != null || passwordError(newPassword) != null || (needsTerms && termsError != null)) return
        run(action)
    }

    /** Runs [action] with the busy flag; its failure becomes [message]. */
    private fun run(action: suspend () -> Result<Unit>) {
        busy = true
        message = null
        viewModelScope.launch {
            action().onFailure { message = it.toAccountMessage() }
            busy = false
        }
    }

    /** [next] == null: decided after linking by the e-mail verification state. Prompts wait for [choose]. */
    private suspend fun link(strategy: MergeStrategy, next: AuthDone?): Result<Unit> {
        afterLink = next
        when (val error = accounts.linkGuestProfile(strategy).exceptionOrNull()) {
            null -> done = next ?: verificationStep()
            is MergeConflict -> prompt = LinkPrompt.Choose(error.local, error.remote)
            is LinkedToOtherAccount -> prompt = LinkPrompt.OtherAccount(error.accountEmail)
            else -> {
                accounts.signOut()
                return Result.failure(error)
            }
        }
        return Result.success(Unit)
    }

    private suspend fun verificationStep(): AuthDone =
        if (accounts.reloadVerification().getOrDefault(true)) {
            AuthDone.HOME
        } else {
            accounts.sendVerification()
            AuthDone.VERIFY_EMAIL
        }

    private companion object {
        const val KEY_EMAIL = "email"
    }
}

private inline fun Result<Unit>.then(next: () -> Result<Unit>): Result<Unit> = if (isSuccess) next() else this

/** Seconds until an action may be repeated, counted down in [scope]. */
class Cooldown(private val scope: CoroutineScope) {
    var secondsLeft by mutableIntStateOf(0)
        private set
    private var job: Job? = null

    fun start(seconds: Int) {
        job?.cancel()
        secondsLeft = seconds
        job = scope.launch {
            while (secondsLeft > 0) {
                delay(1_000)
                secondsLeft--
            }
        }
    }
}
