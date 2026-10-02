package me.ri3d.dashcam.account

import android.net.Uri
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import me.ri3d.dashcam.core.log.Log
import java.io.File
import javax.inject.Inject

/** `users/{uid}` as stored in Firestore. [preferences] holds the synced subset only (see [toSynced]). */
data class RemoteProfile(
    val displayName: String,
    val photoPath: String?,
    val preferences: Map<String, String>,
    val createdAt: Long?,
    val updatedAt: Long?,
)

/** Firestore profile document plus the avatar in Firebase Storage; a seam so sync logic is testable without Firebase. */
interface ProfileRemote {
    /** The account's profile, or null while `users/{uid}` is empty. Server read only: deciding a merge from a stale cache could overwrite data. */
    suspend fun load(uid: String): RemoteProfile?

    /** Live document including pending local writes (works offline from the cache). */
    fun observe(uid: String): Flow<RemoteProfile?>

    /** Fire-and-forget merge write; Firestore queues it while offline. [create] also stamps `createdAt`. */
    fun save(uid: String, displayName: String, photoPath: String?, preferences: Map<String, String>, create: Boolean)

    /** Uploads a downscaled JPEG and returns its storage path. */
    suspend fun uploadAvatar(uid: String, jpeg: File): String
    suspend fun downloadAvatar(path: String, target: File)
}

class FirestoreProfileRemote @Inject constructor(private val firebase: FirebaseHandles) : ProfileRemote {
    private fun doc(uid: String) = firebase.firestore.collection("users").document(uid)

    override suspend fun load(uid: String): RemoteProfile? = doc(uid).get(Source.SERVER).await().toRemoteProfile()

    override fun observe(uid: String): Flow<RemoteProfile?> = callbackFlow {
        val registration = doc(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                // e.g. PERMISSION_DENIED right after sign-out; sync simply stops.
                Log.w(TAG, "Profile listener stopped: ${error.code}")
                close()
            } else {
                trySend(snapshot?.toRemoteProfile())
            }
        }
        awaitClose { registration.remove() }
    }

    override fun save(uid: String, displayName: String, photoPath: String?, preferences: Map<String, String>, create: Boolean) {
        val data = buildMap {
            put("displayName", displayName)
            put("photoPath", photoPath)
            put("preferences", preferences)
            put("updatedAt", FieldValue.serverTimestamp())
            if (create) put("createdAt", FieldValue.serverTimestamp())
        }
        doc(uid).set(data, SetOptions.merge()).addOnFailureListener { Log.w(TAG, "Saving the profile failed: ${it.logLabel()}") }
    }

    override suspend fun uploadAvatar(uid: String, jpeg: File): String {
        val path = "users/$uid/avatar.jpg"
        val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
        firebase.storage.reference.child(path).putFile(Uri.fromFile(jpeg), metadata).await()
        return path
    }

    override suspend fun downloadAvatar(path: String, target: File) {
        firebase.storage.reference.child(path).getFile(target).await()
    }

    private companion object {
        const val TAG = "ProfileRemote"
    }
}

private fun DocumentSnapshot.toRemoteProfile(): RemoteProfile? {
    val name = getString("displayName") ?: return null
    val estimate = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
    return RemoteProfile(
        displayName = name,
        photoPath = getString("photoPath"),
        preferences = (get("preferences") as? Map<*, *>).orEmpty().entries
            .filter { (key, value) -> key is String && value is String }
            .associate { (key, value) -> key as String to value as String },
        createdAt = getTimestamp("createdAt", estimate)?.toDate()?.time,
        updatedAt = getTimestamp("updatedAt", estimate)?.toDate()?.time,
    )
}
