package me.ri3d.cam.recorder

import java.nio.charset.Charset
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Session cryptography as traced (StartSessionBO, RSAUtils, AESUtils + native libAESUtils):
 *  - `aescode` is Base64 RSA/PKCS#1 v1.5 ciphertext; the plaintext is the session key as hexadecimal text.
 *  - Bodies are AES-128-ECB over zero-padded 16-byte blocks (no PKCS#7, no extra block when already aligned,
 *    one zero block for empty input), Base64 without line breaks. Outgoing JSON is UTF-8.
 *  - Incoming plaintext has trailing NUL bytes stripped and is decoded as GB2312.
 *
 * Error messages never contain key material.
 */
object SessionCrypto {
    private val GB2312: Charset = Charset.forName("GB2312")
    private val PEM_ARMOR = Regex("-----[A-Z0-9 ]+-----")
    private const val AES_KEY_BYTES = 16

    /** Unwraps `aescode` with the app-supplied RSA private key (see [loadPrivateKey] for accepted formats). */
    fun unwrapAesKey(aescodeB64: String, rsaPrivateKey: ByteArray): String =
        unwrapAesKey(aescodeB64, loadPrivateKey(rsaPrivateKey))

    fun unwrapAesKey(aescodeB64: String, key: PrivateKey): String = try {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding") // vendor: RSA/NONE/PKCS1Padding, same transform
        cipher.init(Cipher.DECRYPT_MODE, key)
        String(cipher.doFinal(Base64.getMimeDecoder().decode(aescodeB64)), Charsets.UTF_8)
    } catch (e: Exception) {
        throw GeneralSecurityException("aescode could not be unwrapped (${e.javaClass.simpleName})")
    }

    /**
     * Accepts PKCS#8 or PKCS#1 ("BEGIN RSA PRIVATE KEY"), either as DER bytes or as Base64 / PEM text bytes.
     */
    fun loadPrivateKey(material: ByteArray): PrivateKey = try {
        val der = if (material.isNotEmpty() && material[0] == 0x30.toByte()) material
        else Base64.getMimeDecoder().decode(String(material, Charsets.US_ASCII).replace(PEM_ARMOR, ""))
        val factory = KeyFactory.getInstance("RSA")
        try {
            factory.generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (e: InvalidKeySpecException) {
            factory.generatePrivate(PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)))
        }
    } catch (e: Exception) {
        throw GeneralSecurityException("RSA private key could not be loaded (expected PKCS#8 or PKCS#1, DER/Base64/PEM)")
    }

    /** JSON → AES-128-ECB (zero padding) → Base64 without line breaks. */
    fun encrypt(json: String, hexKey: String): String {
        val plain = json.toByteArray(Charsets.UTF_8)
        val padded = plain.copyOf(maxOf(AES_KEY_BYTES, (plain.size + 15) / 16 * 16))
        return Base64.getEncoder().encodeToString(aes(Cipher.ENCRYPT_MODE, hexKey).doFinal(padded))
    }

    /** Base64 → AES-128-ECB → trailing NULs stripped → GB2312 text. */
    fun decrypt(b64: String, hexKey: String): String {
        val plain = aes(Cipher.DECRYPT_MODE, hexKey).doFinal(Base64.getMimeDecoder().decode(b64))
        var end = plain.size
        while (end > 0 && plain[end - 1] == 0.toByte()) end--
        return String(plain, 0, end, GB2312)
    }

    /**
     * Hex text to bytes exactly like AESUtils.hexToBytes: blank → empty, `length / 2` pairs (a trailing odd
     * character is ignored). The native AES code then reads the first 16 bytes only (AES-128, 10 rounds).
     */
    fun hexToBytes(hex: String): ByteArray {
        if (hex.isBlank()) return ByteArray(0)
        return try {
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("session key is not hexadecimal text")
        }
    }

    internal fun isUsableKey(hexKey: String) =
        runCatching { hexToBytes(hexKey).size >= AES_KEY_BYTES }.getOrDefault(false)

    private fun aes(mode: Int, hexKey: String): Cipher {
        val key = hexToBytes(hexKey)
        require(key.size >= AES_KEY_BYTES) { "session key shorter than 16 bytes" }
        return Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, 0, AES_KEY_BYTES, "AES")) }
    }

    /** Wraps a PKCS#1 RSAPrivateKey into PKCS#8 PrivateKeyInfo, which the JDK key factory understands. */
    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val rsaAlgorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
            0x0d, 0x01, 0x01, 0x01, 0x05, 0x00) // SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }
        return der(0x30, version + rsaAlgorithm + der(0x04, pkcs1))
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }
}
