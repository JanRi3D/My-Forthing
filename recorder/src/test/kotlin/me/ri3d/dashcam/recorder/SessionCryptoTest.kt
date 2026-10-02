package me.ri3d.dashcam.recorder

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.interfaces.RSAPrivateCrtKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class SessionCryptoTest {
    private val key = "00112233445566778899aabbccddeeff"

    private fun rawAes(mode: Int, data: ByteArray, hex: String = key): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(SessionCrypto.hexToBytes(hex), 0, 16, "AES")) }.doFinal(data)

    @Test
    fun aes_roundTrip() {
        val json = """{"token":123,"msgId":8192,"param":{"chanNo":1,"soundSwitch":1}}"""
        val b64 = SessionCrypto.encrypt(json, key)
        assertThat(b64).doesNotContain("\n")
        assertThat(SessionCrypto.decrypt(b64, key)).isEqualTo(json)
    }

    @Test
    fun aes_zeroPadding_noExtraBlockWhenAligned_oneBlockWhenEmpty() {
        fun cipherLength(n: Int) = Base64.getDecoder().decode(SessionCrypto.encrypt("x".repeat(n), key)).size
        assertThat(cipherLength(0)).isEqualTo(16)
        assertThat(cipherLength(1)).isEqualTo(16)
        assertThat(cipherLength(16)).isEqualTo(16) // PKCS#7 would add a block here
        assertThat(cipherLength(17)).isEqualTo(32)
        val plain = rawAes(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(SessionCrypto.encrypt("abc", key)))
        assertThat(plain).isEqualTo("abc".toByteArray() + ByteArray(13))
    }

    @Test
    fun aes_isEcb_identicalBlocksEncryptIdentically() {
        val c = Base64.getDecoder().decode(SessionCrypto.encrypt("A".repeat(32), key))
        assertThat(c.copyOfRange(0, 16)).isEqualTo(c.copyOfRange(16, 32))
    }

    @Test
    fun decrypt_stripsTrailingNuls_decodesGb2312() {
        val gb = "{\"ssid\":\"测试\"}".toByteArray(charset("GB2312"))
        val padded = gb.copyOf((gb.size + 15) / 16 * 16)
        val b64 = Base64.getEncoder().encodeToString(rawAes(Cipher.ENCRYPT_MODE, padded))
        assertThat(SessionCrypto.decrypt(b64, key)).isEqualTo("{\"ssid\":\"测试\"}")
    }

    @Test
    fun hexToBytes_asTraced() {
        assertThat(SessionCrypto.hexToBytes("0aff")).isEqualTo(byteArrayOf(0x0a, 0xff.toByte()))
        assertThat(SessionCrypto.hexToBytes("0aff7")).isEqualTo(byteArrayOf(0x0a, 0xff.toByte())) // odd tail ignored
        assertThat(SessionCrypto.hexToBytes("  ")).isEmpty()
        val e = assertThrows(IllegalArgumentException::class.java) { SessionCrypto.hexToBytes("zz11") }
        assertThat(e.message).doesNotContain("zz")
    }

    @Test
    fun aes_onlyFirst16KeyBytesAreUsed_shortKeysRejected() {
        val longKey = key + "deadbeef"
        assertThat(SessionCrypto.encrypt("same", longKey)).isEqualTo(SessionCrypto.encrypt("same", key))
        assertThrows(IllegalArgumentException::class.java) { SessionCrypto.encrypt("x", "0011") }
    }

    @Test
    fun rsaUnwrap_generated1024BitKey_allAcceptedFormats() {
        val sim = RecorderSimulator(sessionKeyHex = key)
        val aescode = sim.wrapSessionKey()
        val pkcs8 = sim.privateKeyPkcs8
        assertThat((SessionCrypto.loadPrivateKey(pkcs8) as RSAPrivateCrtKey).modulus.bitLength()).isEqualTo(1024)

        val pkcs1 = pkcs1Of(pkcs8)
        val b64 = { der: ByteArray -> Base64.getMimeEncoder().encodeToString(der) }
        val formats = mapOf(
            "pkcs8 der" to pkcs8,
            "pkcs8 base64" to Base64.getEncoder().encode(pkcs8),
            "pkcs8 pem" to "-----BEGIN PRIVATE KEY-----\n${b64(pkcs8)}\n-----END PRIVATE KEY-----\n".toByteArray(),
            "pkcs1 der" to pkcs1,
            "pkcs1 base64" to Base64.getEncoder().encode(pkcs1),
            "pkcs1 pem" to "-----BEGIN RSA PRIVATE KEY-----\n${b64(pkcs1)}\n-----END RSA PRIVATE KEY-----".toByteArray(),
        )
        for ((name, material) in formats) {
            assertWithMessage(name).that(SessionCrypto.unwrapAesKey(aescode, material)).isEqualTo(key)
        }
    }

    @Test
    fun errors_neverContainKeyMaterial() {
        val garbageKey = "MIIthisIsNotAKeyButMustNotBeEchoed"
        val e = assertThrows(GeneralSecurityException::class.java) { SessionCrypto.loadPrivateKey(garbageKey.toByteArray()) }
        assertThat(e.message).doesNotContain(garbageKey)
        val sim = RecorderSimulator()
        val e2 = assertThrows(GeneralSecurityException::class.java) { SessionCrypto.unwrapAesKey("bm90LXJzYQ==", sim.privateKeyPkcs8) }
        assertThat(e2.message).doesNotContain("bm90LXJzYQ")
    }

    /** Extracts the PKCS#1 RSAPrivateKey from a JDK PKCS#8 encoding (30 82 LL LL | 02 01 00 | algId(15) | 04 82 LL LL | pkcs1). */
    private fun pkcs1Of(pkcs8: ByteArray): ByteArray {
        check(pkcs8[22] == 0x04.toByte() && pkcs8[23] == 0x82.toByte())
        val len = ((pkcs8[24].toInt() and 0xff) shl 8) or (pkcs8[25].toInt() and 0xff)
        return pkcs8.copyOfRange(26, 26 + len)
    }
}
