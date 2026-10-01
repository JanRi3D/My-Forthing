package me.ri3d.cam.account

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.storage.FirebaseStorage
import me.ri3d.cam.R

/**
 * Firebase for the account feature only, built from `BuildConfig.FIREBASE_*` (no google-services plugin) and
 * initialised on first use. With [options] == null nothing here may be touched; every accessor throws
 * [AccountNotConfigured] instead of initialising anything.
 */
class FirebaseHandles(private val context: Context, private val options: FirebaseOptions?, val webClientId: String) {
    val configured: Boolean get() = options != null

    private val app: FirebaseApp by lazy {
        val options = options ?: throw AccountNotConfigured
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context, options)
    }

    val auth: FirebaseAuth by lazy {
        // Verification and reset mails in the app's language.
        FirebaseAuth.getInstance(app).apply { setLanguageCode(context.getString(R.string.account_mail_language)) }
    }

    val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(app).apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        }
    }

    /** Throws when the configuration has no storage bucket; avatar sync is best effort. */
    val storage: FirebaseStorage by lazy {
        FirebaseStorage.getInstance(app).apply {
            maxUploadRetryTimeMillis = STORAGE_RETRY_MS
            maxDownloadRetryTimeMillis = STORAGE_RETRY_MS
        }
    }

    private companion object {
        const val STORAGE_RETRY_MS = 30_000L
    }
}

/** Null unless project, app id and API key are all present (the storage bucket is optional). */
fun firebaseOptions(projectId: String, appId: String, apiKey: String, storageBucket: String): FirebaseOptions? =
    if (projectId.isBlank() || appId.isBlank() || apiKey.isBlank()) {
        null
    } else {
        FirebaseOptions.Builder()
            .setProjectId(projectId)
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .apply { if (storageBucket.isNotBlank()) setStorageBucket(storageBucket) }
            .build()
    }
