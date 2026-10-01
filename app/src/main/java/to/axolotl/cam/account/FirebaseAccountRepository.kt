package to.axolotl.cam.account

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import to.axolotl.cam.R
import to.axolotl.cam.core.log.Log
import to.axolotl.cam.core.profile.LocalProfileDao
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Auth + profile sync. Guests never initialise Firebase: it starts with the first account action, or at app
 * start when this phone's profile is linked to an account (to restore the session and sync).
 */
@Singleton
class FirebaseAccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebase: FirebaseHandles,
    private val sync: ProfileSync,
    private val profiles: LocalProfileDao,
) : AccountRepository {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Account background work failed", e) },
    )
    private val user = MutableStateFlow<AuthUser?>(null)
    private val listening = AtomicBoolean(false)

    override val isConfigured: Boolean = firebase.configured

    override val state: StateFlow<AccountState> =
        if (!isConfigured) {
            MutableStateFlow(AccountState.Guest).asStateFlow()
        } else {
            combine(user, profiles.observe(), context.internetAvailable()) { user, profile, online ->
                if (user == null || profile?.linkedUid != user.uid) {
                    AccountState.Guest
                } else {
                    AccountState.SignedIn(user.uid, user.email, user.emailVerified, profile.displayName, user.photoUrl, online)
                }
            }.stateIn(scope, SharingStarted.Eagerly, AccountState.Guest)
        }

    init {
        if (isConfigured) {
            scope.launch {
                if (profiles.observe().first()?.linkedUid != null) auth()
                state.map { (it as? AccountState.SignedIn)?.uid }.distinctUntilChanged().collectLatest { uid ->
                    if (uid != null) sync.run(uid)
                }
            }
        }
    }

    override suspend fun signInGoogle(activity: Activity): Result<Unit> = call { auth ->
        if (firebase.webClientId.isBlank()) throw AccountNotConfigured
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(firebase.webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setNonce(UUID.randomUUID().toString())
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type"
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        refresh(auth)
    }

    override suspend fun signInEmail(email: String, password: String): Result<Unit> = call { auth ->
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        refresh(auth)
    }

    override suspend fun createEmail(email: String, password: String): Result<Unit> = call { auth ->
        val created = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
        refresh(auth)
        // The account exists either way; the verification screen offers "Erneut senden".
        runCatching { created?.sendEmailVerification()?.await() }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Sending the verification mail failed", it) }
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> = call { auth ->
        auth.sendPasswordResetEmail(email.trim()).await()
        Unit
    }

    override suspend fun sendVerification(): Result<Unit> = call { auth ->
        currentUser(auth).sendEmailVerification().await()
        Unit
    }

    override suspend fun reloadVerification(): Result<Boolean> = call { auth ->
        currentUser(auth).reload().await()
        refresh(auth)
        auth.currentUser?.isEmailVerified == true
    }

    override suspend fun signOut() {
        if (!isConfigured) return
        auth().signOut()
        user.value = null
        // Lets the next Google sign-in show the account picker again.
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Clearing the credential state failed", it) }
    }

    override suspend fun linkGuestProfile(strategy: MergeStrategy): Result<Unit> = call { auth ->
        val user = currentUser(auth)
        val fallbackName = user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.account_default_profile_name)
        sync.link(user.uid, user.email, fallbackName, strategy)
    }

    /** First use attaches the auth listener (and with it initialises Firebase, off the main thread). */
    private suspend fun auth(): FirebaseAuth = withContext(Dispatchers.IO) {
        firebase.auth.also { auth ->
            if (listening.compareAndSet(false, true)) auth.addAuthStateListener { user.value = it.currentUser?.toAuthUser() }
        }
    }

    private fun refresh(auth: FirebaseAuth) {
        user.value = auth.currentUser?.toAuthUser()
    }

    private fun currentUser(auth: FirebaseAuth): FirebaseUser = checkNotNull(auth.currentUser) { "Not signed in" }

    private suspend fun <T> call(block: suspend (FirebaseAuth) -> T): Result<T> {
        if (!isConfigured) return Result.failure(AccountNotConfigured)
        return try {
            Result.success(block(auth()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Firebase messages may contain the e-mail address: class name only.
            Log.w(TAG, "Account action failed: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    private data class AuthUser(val uid: String, val email: String?, val emailVerified: Boolean, val photoUrl: String?)

    private fun FirebaseUser.toAuthUser() = AuthUser(uid, email, isEmailVerified, photoUrl?.toString())

    private companion object {
        const val TAG = "Accounts"
    }
}

/** True while the default network has validated internet access. */
private fun Context.internetAvailable(): Flow<Boolean> = callbackFlow {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }

        override fun onLost(network: Network) {
            trySend(false)
        }
    }
    trySend(connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
