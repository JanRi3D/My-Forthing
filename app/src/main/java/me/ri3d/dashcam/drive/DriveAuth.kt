package me.ri3d.dashcam.drive

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** The only Drive scope the app asks for: files the app created (or the user opened with it). */
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

sealed interface DriveAuthState {
    data object NotConnected : DriveAuthState

    data class Connected(val accountEmail: String?, val scopes: Set<String>) : DriveAuthState

    /** [reason] is German UI text. [accountEmail] is the account to reconnect (null when unknown). */
    data class NeedsReconnect(val reason: String, val accountEmail: String? = null) : DriveAuthState
}

/**
 * Google Drive authorisation (CONTRACTS §10). Independent of the app account: guests can connect, and a signed-in
 * user may connect a different Google account. No Firebase anywhere in `drive/`.
 */
interface DriveAuth {
    val state: StateFlow<DriveAuthState>

    /**
     * Shows Google's account picker / consent screen when needed. [chooseAccount] forces the account picker
     * ("Konto wechseln"); after a switch to another account the previous account's grant is revoked.
     */
    suspend fun connect(activity: Activity, chooseAccount: Boolean = false): Result<Unit>

    /**
     * Connects [accountEmail] without any UI (e.g. after a reinstall) while nothing is stored: connected when Google
     * grants it silently; when Google needs the user, [DriveAuthState.NeedsReconnect] for that account (in memory only,
     * nothing is launched), so the one-tap "Erneut verbinden" finishes it. A stored connection is never replaced.
     */
    suspend fun reconnectSilently(accountEmail: String): Result<Unit>

    /** Revokes the grant at Google (best effort, needs network) and forgets the account. Files stay in Drive. */
    suspend fun disconnect()

    /** A fresh access token, re-authorised silently. Never persisted by the app. */
    suspend fun accessToken(): Result<String>

    /**
     * Drops [token] from the Play services cache after an HTTP 401 so the next [accessToken] fetches a new one.
     * [revoked] = the API still answered 401 with a fresh token → [DriveAuthState.NeedsReconnect].
     */
    suspend fun invalidate(token: String, revoked: Boolean = false)
}

/** Failures of [DriveAuth] and [DriveApi], delivered as `Result.failure`. Messages are for logs, not for the UI. */
sealed class DriveError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConnected : DriveError("Drive is not connected")

    class NeedsReconnect : DriveError("Drive access must be renewed by the user")

    /** HTTP 403 `storageQuotaExceeded`. */
    class InsufficientStorage : DriveError("Drive storage is full")

    class Offline(cause: IOException) : DriveError("Network unavailable", cause)

    /** The user closed the consent screen. */
    class Cancelled : DriveError("Cancelled by the user")

    /** Play services refused the authorisation; [statusCode] is the raw `CommonStatusCodes` value. */
    class Authorization(val statusCode: Int, cause: Throwable? = null) : DriveError("Authorization failed: $statusCode", cause) {
        /** No Android OAuth client (package + SHA-1) in a Cloud project with the Drive API, see docs/features/drive.md. */
        val configurationMissing: Boolean get() = statusCode in CONFIGURATION_MISSING_CODES
    }

    /** The user unticked the Drive permission on Google's consent screen. */
    class ScopeNotGranted : DriveError("drive.file was not granted")

    class Http(val code: Int, val reason: String?) : DriveError("HTTP $code ${reason.orEmpty()}")

    companion object {
        /** DEVELOPER_ERROR (10) and the One-Tap "developer console is not set up correctly" code (28444). */
        val CONFIGURATION_MISSING_CODES = setOf(10, 28444)
    }
}
