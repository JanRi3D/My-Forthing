package to.axolotl.cam.drive

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import to.axolotl.cam.core.log.Log
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

/** Seam over Play services' `AuthorizationClient`, so the state machine below is testable without Google Play. */
interface DriveAuthorizer {
    /** Silent when Google can; [Authorization.NeedsUi] when it must show the account picker or consent screen. */
    suspend fun authorize(accountEmail: String?, chooseAccount: Boolean): Authorization

    /** Reads the consent activity's result. Throws [ApiException] (e.g. status 10 when the Cloud setup is missing). */
    fun fromIntent(data: Intent): Authorization

    suspend fun clearToken(token: String)

    suspend fun revoke(accountEmail: String)

    /** E-mail of the account behind [token] (Drive `about.user`); null when it cannot be read. */
    suspend fun accountEmail(token: String): String?
}

sealed interface Authorization {
    data class Granted(val token: String, val scopes: Set<String>) : Authorization

    class NeedsUi(val intent: PendingIntent) : Authorization
}

/**
 * [DriveAuth] state machine: NotConnected → (consent) → Connected ⇄ NeedsReconnect. Persists only e-mail, scopes,
 * connection time and the reconnect flag ([DriveAccountStore]); tokens come fresh from Play services every time.
 */
class GoogleDriveAuth(
    private val authorizer: DriveAuthorizer,
    private val store: DriveAccountStore,
    /** German explanation shown for NeedsReconnect. */
    private val reasonText: (ReconnectReason) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : DriveAuth {

    // ponytail: one synchronous Keystore decrypt at first injection (a few ms); move off the main thread if it shows up in traces.
    @Volatile private var account: StoredDriveAccount? = store.load()
    private val _state = MutableStateFlow(stateOf(account))
    override val state: StateFlow<DriveAuthState> = _state.asStateFlow()

    override suspend fun connect(activity: Activity, chooseAccount: Boolean): Result<Unit> =
        connectWith(chooseAccount) { launchConsent(activity as ComponentActivity, it) }

    /** [connect] with the consent screen abstracted, for tests. */
    suspend fun connectWith(chooseAccount: Boolean, consent: suspend (PendingIntent) -> ActivityResult): Result<Unit> = guard {
        val previous = account
        val granted = when (val first = authorizer.authorize(previous?.email.takeUnless { chooseAccount }, chooseAccount)) {
            is Authorization.Granted -> first
            is Authorization.NeedsUi -> {
                // Play services may report errors (e.g. missing Cloud configuration) with RESULT_CANCELED plus data.
                val data = consent(first.intent).data ?: throw DriveError.Cancelled()
                authorizer.fromIntent(data) as? Authorization.Granted ?: throw DriveError.Cancelled()
            }
        }
        if (DRIVE_FILE_SCOPE !in granted.scopes) throw DriveError.ScopeNotGranted()
        val email = authorizer.accountEmail(granted.token)
        val oldEmail = previous?.email
        if (oldEmail != null && email != null && !oldEmail.equals(email, ignoreCase = true)) {
            // Account switched: the old account's grant is no longer needed. Best effort.
            runCatching { authorizer.revoke(oldEmail) }.onFailure { rethrowCancellation(it); Log.w(TAG, "Revoking the previous account failed") }
        }
        update(StoredDriveAccount(email, granted.scopes, clock()))
    }

    override suspend fun disconnect() {
        account?.email?.let { email ->
            // ponytail: offline the grant stays at Google until the user removes it in the Google account settings.
            runCatching { authorizer.revoke(email) }.onFailure { rethrowCancellation(it); Log.w(TAG, "Revoking Drive access failed") }
        }
        store.clear()
        account = null
        _state.value = DriveAuthState.NotConnected
    }

    override suspend fun accessToken(): Result<String> {
        val current = account ?: return Result.failure(DriveError.NotConnected())
        if (current.reconnect != null) return Result.failure(DriveError.NeedsReconnect())
        return guard {
            val auth = try {
                authorizer.authorize(current.email, chooseAccount = false)
            } catch (e: ApiException) {
                // The silent path reports a removed grant as SIGN_IN_REQUIRED or invalid_grant.
                if (e.statusCode == CommonStatusCodes.SIGN_IN_REQUIRED || e.message?.contains("invalid_grant") == true) {
                    needsReconnect(ReconnectReason.REVOKED)
                }
                throw e
            }
            when (auth) {
                is Authorization.Granted ->
                    if (DRIVE_FILE_SCOPE in auth.scopes) auth.token else needsReconnect(ReconnectReason.CONSENT_REQUIRED)
                is Authorization.NeedsUi -> needsReconnect(ReconnectReason.CONSENT_REQUIRED)
            }
        }
    }

    override suspend fun invalidate(token: String, revoked: Boolean) {
        runCatching { authorizer.clearToken(token) }.onFailure { rethrowCancellation(it); Log.w(TAG, "Clearing a cached token failed") }
        if (revoked) markReconnect(ReconnectReason.REVOKED)
    }

    private fun needsReconnect(reason: ReconnectReason): Nothing {
        markReconnect(reason)
        throw DriveError.NeedsReconnect()
    }

    private fun markReconnect(reason: ReconnectReason) {
        account?.let { update(it.copy(reconnect = reason)) }
    }

    private fun update(new: StoredDriveAccount) {
        store.save(new)
        account = new
        _state.value = stateOf(new)
    }

    private fun stateOf(stored: StoredDriveAccount?): DriveAuthState = when {
        stored == null -> DriveAuthState.NotConnected
        stored.reconnect != null -> DriveAuthState.NeedsReconnect(reasonText(stored.reconnect), stored.email)
        else -> DriveAuthState.Connected(stored.email, stored.scopes)
    }

    private companion object {
        const val TAG = "DriveAuth"
    }
}

/** Maps Play services and network failures to [DriveError]. */
private suspend fun <T> guard(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: DriveError) {
    Result.failure(e)
} catch (e: ApiException) {
    Result.failure(
        when (e.statusCode) {
            CommonStatusCodes.CANCELED, SIGN_IN_CANCELLED -> DriveError.Cancelled()
            CommonStatusCodes.NETWORK_ERROR -> DriveError.Offline(IOException("Play services network error", e))
            else -> DriveError.Authorization(e.statusCode, e)
        },
    )
} catch (e: IOException) {
    Result.failure(DriveError.Offline(e))
}

/** `GoogleSignInStatusCodes.SIGN_IN_CANCELLED`, kept local to avoid the deprecated Sign-In API. */
private const val SIGN_IN_CANCELLED = 12501

private fun rethrowCancellation(error: Throwable) {
    if (error is CancellationException) throw error
}

/**
 * Launches Google's consent screen through the activity's result registry and waits for the result.
 * ponytail: an activity recreation while the consent screen is open drops the result; the caller's coroutine is then
 * cancelled with the old screen and the user taps "Verbinden" again (Google remembers a granted consent).
 */
private suspend fun launchConsent(activity: ComponentActivity, intent: PendingIntent): ActivityResult =
    withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>
            launcher = activity.activityResultRegistry.register(
                "drive-consent-${UUID.randomUUID()}",
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                launcher.unregister()
                continuation.resume(result)
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(IntentSenderRequest.Builder(intent).build())
        }
    }

/** Production [DriveAuthorizer] on Google Identity Services (`play-services-auth`). */
class PlayDriveAuthorizer(
    context: Context,
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl,
) : DriveAuthorizer {
    private val client = Identity.getAuthorizationClient(context)
    private val scopes = listOf(Scope(DRIVE_FILE_SCOPE))

    override suspend fun authorize(accountEmail: String?, chooseAccount: Boolean): Authorization {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .apply {
                if (chooseAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
                else if (accountEmail != null) setAccount(Account(accountEmail, GOOGLE_ACCOUNT_TYPE))
            }
            .build()
        return client.authorize(request).await().toAuthorization()
    }

    override fun fromIntent(data: Intent): Authorization = client.getAuthorizationResultFromIntent(data).toAuthorization()

    override suspend fun clearToken(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    override suspend fun revoke(accountEmail: String) {
        client.revokeAccess(
            RevokeAccessRequest.builder().setAccount(Account(accountEmail, GOOGLE_ACCOUNT_TYPE)).setScopes(scopes).build(),
        ).await()
    }

    override suspend fun accountEmail(token: String): String? = runCatching {
        val url = baseUrl.resolve("drive/v3/about")!!.newBuilder().addQueryParameter("fields", "user(emailAddress)").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                driveJson.decodeFromString<AboutUser>(response.body.string()).user?.emailAddress
            }
        }
    }.onFailure { rethrowCancellation(it); Log.w("DriveAuth", "Reading the Drive account e-mail failed") }.getOrNull()

    private fun AuthorizationResult.toAuthorization(): Authorization {
        val pending = pendingIntent
        return if (hasResolution() && pending != null) {
            Authorization.NeedsUi(pending)
        } else {
            Authorization.Granted(accessToken ?: throw ApiException(Status(CommonStatusCodes.INTERNAL_ERROR)), grantedScopes.toSet())
        }
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

@Serializable
private data class AboutUser(val user: User? = null) {
    @Serializable
    data class User(val emailAddress: String? = null)
}
