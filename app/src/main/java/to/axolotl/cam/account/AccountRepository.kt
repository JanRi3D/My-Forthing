package to.axolotl.cam.account

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** CONTRACTS §9. [SignedIn] only while the Firebase user is the one this phone's profile is linked to. */
sealed interface AccountState {
    data object Guest : AccountState

    data class SignedIn(
        val uid: String,
        val email: String?,
        val emailVerified: Boolean,
        /** The profile name on this phone (synchronised with the account). */
        val displayName: String?,
        val photoUrl: String?,
        val online: Boolean,
    ) : AccountState
}

enum class MergeStrategy { KEEP_LOCAL, KEEP_REMOTE, ASK }

interface AccountRepository {
    /** False when this build carries no Firebase configuration; every auth call then fails with [AccountNotConfigured]. */
    val isConfigured: Boolean
    val state: StateFlow<AccountState>
    suspend fun signInGoogle(activity: Activity): Result<Unit>
    suspend fun signInEmail(email: String, password: String): Result<Unit>

    /** Creates the account and sends the verification link. */
    suspend fun createEmail(email: String, password: String): Result<Unit>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    suspend fun sendVerification(): Result<Unit>

    /** Reloads the user from the server; true once the e-mail address is verified. */
    suspend fun reloadVerification(): Result<Boolean>

    /** The profile stays on the phone (still linked), it just stops synchronising. */
    suspend fun signOut()

    /**
     * Links this phone's profile to the signed-in account (call after every sign-in). Fails with [MergeConflict] when
     * both sides hold a profile and [strategy] is [MergeStrategy.ASK], and with [LinkedToOtherAccount] when the phone's
     * profile belongs to another account and [strategy] is not [MergeStrategy.KEEP_REMOTE]. Never overwrites silently.
     */
    suspend fun linkGuestProfile(strategy: MergeStrategy): Result<Unit>
}

/** This installation has no Firebase configuration (no `app/google-services.json`). Guest mode is unaffected. */
object AccountNotConfigured : IllegalStateException("Account service is not configured in this build") {
    private fun readResolve(): Any = AccountNotConfigured
}

/** The account already holds a profile and the phone has its own: the user picks one. */
class MergeConflict(val local: ProfileSummary, val remote: ProfileSummary) : Exception("Profile conflict")

/** The phone's profile is linked to a different account than the one that just signed in. */
class LinkedToOtherAccount(val accountEmail: String?) : Exception("Profile linked to another account")

/** One side of the profile chooser. [updatedAt] is the creation time for a local profile. */
data class ProfileSummary(val displayName: String, val avatar: File?, val updatedAt: Long?)
