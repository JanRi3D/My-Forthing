package to.axolotl.cam.account

import androidx.annotation.StringRes
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import to.axolotl.cam.R
import to.axolotl.cam.core.ui.UiText

/** German text for a failed account action; null when the user cancelled and nothing should be shown. */
fun Throwable.toAccountMessage(): UiText? = when (this) {
    is GetCredentialCancellationException -> null
    is NoCredentialException -> UiText.Res(R.string.account_error_no_google_account)
    is AccountNotConfigured -> UiText.Res(R.string.account_not_configured)
    is FirebaseNetworkException -> UiText.Res(R.string.account_error_network)
    is FirebaseTooManyRequestsException -> UiText.Res(R.string.account_error_too_many)
    is FirebaseAuthException -> UiText.Res(authErrorText(errorCode))
    is FirebaseFirestoreException ->
        UiText.Res(if (code == FirebaseFirestoreException.Code.UNAVAILABLE) R.string.account_error_network else R.string.account_error_generic)
    else -> UiText.Res(R.string.account_error_generic)
}

/** For logs: class name and error code only. Firebase messages can carry e-mail addresses, Storage ones bucket and path. */
internal fun Throwable.logLabel(): String = javaClass.simpleName + when (this) {
    is FirebaseAuthException -> " $errorCode"
    is FirebaseFirestoreException -> " $code"
    is StorageException -> " $errorCode/$httpResultCode"
    else -> ""
}

/** [FirebaseAuthException.getErrorCode] values. Wrong password and unknown user read the same (no account probing). */
@StringRes
internal fun authErrorText(code: String): Int = when (code) {
    "ERROR_INVALID_EMAIL" -> R.string.account_error_invalid_email
    "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" ->
        R.string.account_error_wrong_credentials
    "ERROR_USER_DISABLED" -> R.string.account_error_user_disabled
    "ERROR_EMAIL_ALREADY_IN_USE" -> R.string.account_error_email_in_use
    "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", "ERROR_CREDENTIAL_ALREADY_IN_USE" -> R.string.account_error_other_method
    "ERROR_WEAK_PASSWORD" -> R.string.account_error_weak_password
    "ERROR_TOO_MANY_REQUESTS" -> R.string.account_error_too_many
    "ERROR_NETWORK_REQUEST_FAILED" -> R.string.account_error_network
    "ERROR_REQUIRES_RECENT_LOGIN", "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN", "ERROR_USER_MISMATCH" ->
        R.string.account_error_sign_in_again
    "ERROR_OPERATION_NOT_ALLOWED" -> R.string.account_error_method_disabled
    // The configuration does not match the Firebase project (wrong key, SHA fingerprint not registered, ...).
    "ERROR_INVALID_API_KEY", "ERROR_APP_NOT_AUTHORIZED", "ERROR_API_NOT_AVAILABLE" -> R.string.account_not_configured
    else -> R.string.account_error_generic
}
