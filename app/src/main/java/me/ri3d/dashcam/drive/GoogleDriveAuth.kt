package me.ri3d.dashcam.drive

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
import me.ri3d.dashcam.core.log.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Seam over Play services' `AuthorizationClient`, so the state machine below is testable without Google Play. */
interface DriveAuthorizer {
    /** Silent when Google can; [Authorization.NeedsUi] when it must show the account picker or consent screen. */
    suspend fun authorize(accountEmail: String?, chooseAccount: Boolean): Authorization

    /** Reads the consent activity's result. Throws [ApiException] (e.g. status 10 when the Cloud setup is missing). */
    fun fromIntent(data: Intent): Authorization

    suspend fun clearToken(token: String)

    suspend fun revoke(accountEmail: String)

    /** E-mail of the account behind [token] (Drive `about.user`). Throws [DriveError] when it cannot be read. */
    suspend fun accountEmail(token: String): String
}

sealed interface Authorization {
    data class Granted(val token: String, val scopes: Set<String>) : Authorization

    class NeedsUi(val intent: PendingIntent) : Authorization
}

/**
 * [DriveAuth] state machine: NotConnected → (consent) → Connected ⇄ NeedsReconnect. Persists only e-mail, scopes,
 * connection time and the reconnect flag ([DriveAccountStore], Keystore work on [io]); tokens come fresh from Play
 * services every time.
 */
class GoogleDriveAuth(
    private val authorizer: DriveAuthorizer,
    private val store: DriveAccountStore,
    /** German explanation shown for NeedsReconnect. */
    private val reasonText: (ReconnectReason) -> String,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : DriveAuth {

    @Volatile private var account: StoredDriveAccount? = null

    /** Connection (`connectedAt`) each handed-out token belongs to, so a late 401 cannot flag a newer connection. */
    private val issued = ConcurrentHashMap<String, Long>()

    // ponytail: reads NotConnected for the few ms until the stored record is decrypted off the main thread.
    private val _state = MutableStateFlow<DriveAuthState>(DriveAuthState.NotConnected)
    override val state: StateFlow<DriveAuthState> = _state.asStateFlow()

    private val loaded = scope.async(io) {
        account = store.load()
        _state.value = stateOf(account)
    }

    override suspend fun connect(activity: Activity, chooseAccount: Boolean): Result<Unit> =
        connectWith(chooseAccount) { launchConsent(activity as ComponentActivity, it) }

    /** [connect] with the consent screen abstracted, for tests. */
    suspend fun connectWith(chooseAccount: Boolean, consent: suspend (PendingIntent) -> ActivityResult): Result<Unit> = guard {
        loaded.await()
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
        // Without the e-mail neither silent re-authorisation nor revoking works: fail instead; a retry is silent.
        val email = authorizer.accountEmail(granted.token)
        val oldEmail = previous?.email
        if (oldEmail != null && !oldEmail.equals(email, ignoreCase = true)) {
            // Account switched: the old account's grant is no longer needed. Best effort.
            runCatching { authorizer.revoke(oldEmail) }.onFailure { rethrowCancellation(it); Log.w(TAG, "Revoking the previous account failed") }
        }
        update(StoredDriveAccount(email, granted.scopes, clock()))
    }

    override suspend fun reconnectSilently(accountEmail: String): Result<Unit> = guard {
        loaded.await()
        if (account != null) return@guard // stored (or pending reconnect): the existing flows own it
        when (val auth = authorizer.authorize(accountEmail, chooseAccount = false)) {
            is Authorization.Granted -> {
                if (DRIVE_FILE_SCOPE !in auth.scopes) throw DriveError.ScopeNotGranted()
                val email = authorizer.accountEmail(auth.token) // same checks as connect
                if (account == null) update(StoredDriveAccount(email, auth.scopes, clock()))
            }
            // Never launched from here. Not persisted: the next start tries silently again, and "Erneut verbinden"
            // (connect) asks Google for this account.
            is Authorization.NeedsUi -> if (account == null) {
                val pending = StoredDriveAccount(accountEmail, emptySet(), clock(), ReconnectReason.CONSENT_REQUIRED)
                account = pending
                _state.value = stateOf(pending)
            }
        }
    }

    override suspend fun disconnect() {
        loaded.await()
        // A pending silent reconnect (no scopes: nothing was granted on this phone) is only forgotten: revoking acts on
        // the Google account as a whole and would end the grant another phone of this account uses.
        val email = account?.takeIf { it.scopes.isNotEmpty() }?.email
        account = null
        issued.clear()
        _state.value = DriveAuthState.NotConnected
        withContext(NonCancellable) {
            store.clear()
            // ponytail: offline the grant stays at Google until the user removes it in the Google account settings.
            if (email != null) {
                runCatching { authorizer.revoke(email) }.onFailure { Log.w(TAG, "Revoking Drive access failed") }
            }
        }
    }

    override suspend fun accessToken(): Result<String> {
        loaded.await()
        val current = account ?: return Result.failure(DriveError.NotConnected())
        if (current.reconnect != null) return Result.failure(DriveError.NeedsReconnect())
        return guard {
            val auth = try {
                authorizer.authorize(current.email, chooseAccount = false)
            } catch (e: ApiException) {
                // The silent path reports a removed grant as SIGN_IN_REQUIRED or invalid_grant.
                if (e.statusCode == CommonStatusCodes.SIGN_IN_REQUIRED || e.message?.contains("invalid_grant") == true) {
                    needsReconnect(ReconnectReason.REVOKED, current.connectedAt)
                }
                throw e
            }
            when (auth) {
                is Authorization.Granted ->
                    if (DRIVE_FILE_SCOPE in auth.scopes) {
                        auth.token.also { issued[it] = current.connectedAt }
                    } else {
                        needsReconnect(ReconnectReason.CONSENT_REQUIRED, current.connectedAt)
                    }
                is Authorization.NeedsUi -> needsReconnect(ReconnectReason.CONSENT_REQUIRED, current.connectedAt)
            }
        }
    }

    override suspend fun invalidate(token: String, revoked: Boolean) {
        loaded.await()
        val connectedAt = issued.remove(token)
        runCatching { authorizer.clearToken(token) }.onFailure { rethrowCancellation(it); Log.w(TAG, "Clearing a cached token failed") }
        if (revoked) markReconnect(ReconnectReason.REVOKED, connectedAt)
    }

    private suspend fun needsReconnect(reason: ReconnectReason, connectedAt: Long): Nothing {
        markReconnect(reason, connectedAt)
        throw DriveError.NeedsReconnect()
    }

    /** Only for the connection [connectedAt] refers to; a reconnect in between wins. */
    private suspend fun markReconnect(reason: ReconnectReason, connectedAt: Long?) {
        val current = account ?: return
        if (current.connectedAt == connectedAt) update(current.copy(reconnect = reason))
    }

    /** In-memory state first; a failed Keystore save is logged by the store and only costs persistence. */
    private suspend fun update(new: StoredDriveAccount) {
        account = new
        _state.value = stateOf(new)
        withContext(io) { store.save(new) }
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

/** Maps Play services and network failures to [DriveError]; anything else (Keystore, wrong activity) to a failure, never a crash. */
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
} catch (e: Exception) {
    Result.failure(e)
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

    /** HTTP errors keep their reason (e.g. 403 `accessNotConfigured` = Drive API disabled); no e-mail counts as Offline. */
    override suspend fun accountEmail(token: String): String {
        val url = baseUrl.resolve("drive/v3/about")!!.newBuilder().addQueryParameter("fields", "user(emailAddress)").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val (code, body) = try {
            withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code to it.body.string() } }
        } catch (e: IOException) {
            throw DriveError.Offline(e)
        }
        if (code !in 200..299) throw DriveError.Http(code, driveErrorReason(body))
        return runCatching { driveJson.decodeFromString<AboutUser>(body).user?.emailAddress }.getOrNull()
            ?: throw DriveError.Offline(IOException("Drive returned no account e-mail"))
    }

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
