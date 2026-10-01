package to.axolotl.cam.account

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import to.axolotl.cam.core.model.LocalProfile
import to.axolotl.cam.core.profile.LocalProfileDao
import java.io.File

class FakeProfileDao(initial: LocalProfile? = null) : LocalProfileDao {
    val stored = MutableStateFlow(initial)
    override fun observe(): Flow<LocalProfile?> = stored
    override suspend fun upsert(profile: LocalProfile) {
        stored.value = profile
    }
}

/** In-memory `users/{uid}`; [save] behaves like Firestore's latency-compensated write (visible to [observe] at once). */
class FakeProfileRemote(initial: Map<String, RemoteProfile> = emptyMap()) : ProfileRemote {
    val docs = MutableStateFlow(initial)
    val saves = mutableListOf<RemoteProfile>()
    var loadError: Exception? = null

    override suspend fun load(uid: String): RemoteProfile? {
        loadError?.let { throw it }
        return docs.value[uid]
    }

    override fun observe(uid: String): Flow<RemoteProfile?> = docs.map { it[uid] }

    override fun save(uid: String, displayName: String, photoPath: String?, preferences: Map<String, String>, create: Boolean) {
        val previous = docs.value[uid]
        val saved = RemoteProfile(displayName, photoPath, preferences, if (create) NOW else previous?.createdAt, NOW)
        saves += saved
        docs.value = docs.value + (uid to saved)
    }

    override suspend fun uploadAvatar(uid: String, jpeg: File): String = "users/$uid/avatar.jpg"

    override suspend fun downloadAvatar(path: String, target: File) {
        target.writeBytes(byteArrayOf(1, 2, 3))
    }

    companion object {
        const val NOW = 1_000L
    }
}

/** Scripted results; records the calls the view models make. */
class FakeAccountRepository(override val isConfigured: Boolean = true) : AccountRepository {
    override val state = MutableStateFlow<AccountState>(AccountState.Guest)
    val calls = mutableListOf<String>()

    var signInResult: Result<Unit> = Result.success(Unit)
    var createResult: Result<Unit> = Result.success(Unit)
    var resetResult: Result<Unit> = Result.success(Unit)
    var sendVerificationResult: Result<Unit> = Result.success(Unit)

    /** Consumed one per call; the last one repeats. */
    val linkResults = ArrayDeque<Result<Unit>>(listOf(Result.success(Unit)))
    val verificationResults = ArrayDeque<Result<Boolean>>(listOf(Result.success(true)))

    private fun <T> notConfigured(): Result<T>? = if (isConfigured) null else Result.failure(AccountNotConfigured)

    override suspend fun signInGoogle(activity: Activity): Result<Unit> {
        calls += "google"
        return notConfigured() ?: signInResult
    }

    override suspend fun signInEmail(email: String, password: String): Result<Unit> {
        calls += "signIn:$email"
        return notConfigured() ?: signInResult
    }

    override suspend fun createEmail(email: String, password: String): Result<Unit> {
        calls += "create:$email"
        return notConfigured() ?: createResult
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> {
        calls += "reset:$email"
        return notConfigured() ?: resetResult
    }

    override suspend fun sendVerification(): Result<Unit> {
        calls += "sendVerification"
        return notConfigured() ?: sendVerificationResult
    }

    override suspend fun reloadVerification(): Result<Boolean> {
        calls += "reload"
        return notConfigured() ?: if (verificationResults.size > 1) verificationResults.removeFirst() else verificationResults.first()
    }

    override suspend fun signOut() {
        calls += "signOut"
    }

    override suspend fun linkGuestProfile(strategy: MergeStrategy): Result<Unit> {
        calls += "link:$strategy"
        return notConfigured() ?: if (linkResults.size > 1) linkResults.removeFirst() else linkResults.first()
    }
}
