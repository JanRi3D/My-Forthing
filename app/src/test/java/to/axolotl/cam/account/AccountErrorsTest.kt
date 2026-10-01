package to.axolotl.cam.account

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import to.axolotl.cam.R
import to.axolotl.cam.core.ui.UiText

@RunWith(RobolectricTestRunner::class)
class AccountErrorsTest {
    private fun text(error: Throwable) = (error.toAccountMessage() as UiText.Res).id

    @Test
    fun `auth error codes map to German texts`() {
        assertThat(authErrorText("ERROR_INVALID_EMAIL")).isEqualTo(R.string.account_error_invalid_email)
        assertThat(authErrorText("ERROR_EMAIL_ALREADY_IN_USE")).isEqualTo(R.string.account_error_email_in_use)
        assertThat(authErrorText("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL")).isEqualTo(R.string.account_error_other_method)
        assertThat(authErrorText("ERROR_WEAK_PASSWORD")).isEqualTo(R.string.account_error_weak_password)
        assertThat(authErrorText("ERROR_USER_DISABLED")).isEqualTo(R.string.account_error_user_disabled)
        assertThat(authErrorText("ERROR_OPERATION_NOT_ALLOWED")).isEqualTo(R.string.account_error_method_disabled)
        assertThat(authErrorText("ERROR_REQUIRES_RECENT_LOGIN")).isEqualTo(R.string.account_error_sign_in_again)
        assertThat(authErrorText("ERROR_APP_NOT_AUTHORIZED")).isEqualTo(R.string.account_not_configured)
        assertThat(authErrorText("ERROR_SOMETHING_NEW")).isEqualTo(R.string.account_error_generic)
    }

    @Test
    fun `wrong password and unknown user read the same`() {
        listOf("ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND").forEach {
            assertThat(authErrorText(it)).isEqualTo(R.string.account_error_wrong_credentials)
        }
    }

    @Test
    fun `exceptions map by type and code`() {
        assertThat(text(FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "bad")))
            .isEqualTo(R.string.account_error_wrong_credentials)
        assertThat(text(FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "taken")))
            .isEqualTo(R.string.account_error_email_in_use)
        assertThat(text(FirebaseAuthException("ERROR_TOO_MANY_REQUESTS", "slow down"))).isEqualTo(R.string.account_error_too_many)
        assertThat(text(FirebaseNetworkException("offline"))).isEqualTo(R.string.account_error_network)
        assertThat(text(FirebaseTooManyRequestsException("slow down"))).isEqualTo(R.string.account_error_too_many)
        assertThat(text(FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)))
            .isEqualTo(R.string.account_error_network)
        assertThat(text(NoCredentialException())).isEqualTo(R.string.account_error_no_google_account)
        assertThat(text(IllegalStateException("boom"))).isEqualTo(R.string.account_error_generic)
    }

    @Test
    fun `log labels carry class and code, never the message`() {
        assertThat(FirebaseAuthException("ERROR_INVALID_EMAIL", "jane@example.com is bad").logLabel())
            .isEqualTo("FirebaseAuthException ERROR_INVALID_EMAIL")
        assertThat(IllegalStateException("users/me/avatar.jpg").logLabel()).isEqualTo("IllegalStateException")
    }

    @Test
    fun `a cancelled Google sign-in shows nothing`() {
        assertThat(GetCredentialCancellationException().toAccountMessage()).isNull()
    }
}
