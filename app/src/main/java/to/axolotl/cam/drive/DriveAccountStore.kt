package to.axolotl.cam.drive

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import to.axolotl.cam.core.log.Log
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
 * the direct Keystore use. An unreadable record (e.g. Keystore reset) is dropped: the user simply connects again.
 */
class DriveAccountStore(private val prefs: SharedPreferences, private val key: () -> SecretKey) {

    fun load(): StoredDriveAccount? {
        val blob = prefs.getString(PREF_KEY, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            driveJson.decodeFromString<StoredDriveAccount>(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString())
        }.onFailure {
            Log.w(TAG, "Stored Drive connection unreadable, forgetting it", it)
            clear()
        }.getOrNull()
    }

    fun save(account: StoredDriveAccount) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(driveJson.encodeToString(account).encodeToByteArray())
        check(cipher.iv.size == IV_BYTES)
        prefs.edit { putString(PREF_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)) }
    }

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
    }
}
