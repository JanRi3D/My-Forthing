package me.ri3d.cam.drive

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import me.ri3d.cam.core.log.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the app remembers about the Drive connection. Access tokens are never stored. */
@Serializable
data class StoredDriveAccount(
    val email: String?,
    val scopes: Set<String>,
    val connectedAt: Long,
    /** Set while the user must reconnect; survives restarts. */
    val reconnect: ReconnectReason? = null,
)

enum class ReconnectReason { REVOKED, CONSENT_REQUIRED }

/**
 * [StoredDriveAccount] as AES-256-GCM ciphertext (IV ‖ ciphertext, Base64) in SharedPreferences, keyed by a
 * non-exportable Android Keystore key. androidx.security-crypto (EncryptedSharedPreferences) is deprecated, hence
 * the direct Keystore use. An unreadable record (e.g. Keystore reset) is dropped together with the key, so the next
 * save creates a fresh key; the user simply connects again. Keystore work is slow: call from a background thread.
 */
class DriveAccountStore(
    private val prefs: SharedPreferences,
    private val key: () -> SecretKey,
    private val dropKey: () -> Unit = {},
) {

    fun load(): StoredDriveAccount? {
        val blob = prefs.getString(PREF_KEY, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            driveJson.decodeFromString<StoredDriveAccount>(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString())
        }.onFailure {
            // Only the type: a JSON error message would quote the decrypted record (e-mail).
            Log.w(TAG, "Stored Drive connection unreadable (${it.javaClass.simpleName}), forgetting it")
            clear()
            runCatching(dropKey).onFailure { e -> Log.w(TAG, "Deleting the Keystore key failed (${e.javaClass.simpleName})") }
        }.getOrNull()
    }

    /** False when the Keystore failed; the caller keeps its in-memory state, the old record stays on disk. */
    fun save(account: StoredDriveAccount): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(cipher.iv.size == IV_BYTES)
        val bytes = cipher.iv + cipher.doFinal(driveJson.encodeToString(account).encodeToByteArray())
        prefs.edit { putString(PREF_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)) }
    }.onFailure { Log.w(TAG, "Saving the Drive connection failed (${it.javaClass.simpleName})") }.isSuccess

    fun clear() = prefs.edit { remove(PREF_KEY) }

    companion object {
        const val PREFS_NAME = "drive_account"
        private const val PREF_KEY = "account"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG = "DriveAccountStore"
        private const val KEY_ALIAS = "axo_drive_account"

        /** The Keystore key, created on first use. */
        fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }

        fun deleteKeystoreKey() {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }
}
