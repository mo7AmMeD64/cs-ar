package com.animeday

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin replacement for `libnative-lib.so` (`NativeLib.buildSecure` +
 * `NativeLib.decryptResponse`), so the plugin no longer extracts and loads a
 * writable native library or spoofs `com.anime.day`.
 *
 * Token envelope: `B64(RSA(aesKey)).B64(iv).B64(AES-GCM(payload) || tag)`.
 */
internal object AnimeDayCrypto {
    private const val CERT_SHA256 = "021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7"
    private const val APK_PATH = "/data/app/com.anime.day/base.apk"
    private const val HMAC_SECRET = "97a54e5dec15b4f1754abc5939643bbe57a0d8ae8db88529e268289457728002"
    private const val DECRYPT_SALT = "Gm4il@G00Gl3.Com"
    private const val HEX_CHARS = "0123456789abcdef"

    private const val RSA_PUB_SPKI_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+6+BtHI/" +
            "Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3QCh65Nbi4mKRFFJyDk" +
            "JVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9FtgJkuz17n265rLB6ruUUrHQVeEDl8" +
            "ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvsq4Lhuthcts6gtVq3uPExSKdLBpbqbwQNvhRgB3IE" +
            "YV47OyddG32qbriJbxdvW+n5OzowiYQrsC8hG65GPBGCuj3zO7wIDAQAB"

    private val rng = SecureRandom()

    private val rsaPublicKey: PublicKey by lazy {
        val spkiBytes = Base64.decode(RSA_PUB_SPKI_B64, Base64.DEFAULT)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spkiBytes))
    }

    private val staticAttestationMac: String by lazy {
        hmacSha256Hex(HMAC_SECRET, "err|$CERT_SHA256|000|err_open")
    }

    fun buildSecureToken(): String {
        val nowSec = System.currentTimeMillis() / 1000L
        val nonce = randomHex(8)
        val payloadJson =
            """{"a":"err","b":"$CERT_SHA256|$APK_PATH","c":"false",""" +
                """"d":"empty","e":"empty","f":"false","g":"false","h":"false","i":"false",""" +
                """"j":"false","r":"false","s":"err_open","m":"$staticAttestationMac","t":$nowSec,"n":"$nonce"}"""

        val aesKey = ByteArray(32).also { rng.nextBytes(it) }
        val iv = ByteArray(12).also { rng.nextBytes(it) }

        val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
        gcmCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val sealedBytes = gcmCipher.doFinal(payloadJson.toByteArray(Charsets.UTF_8))

        val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey)
        val encKeyBytes = rsaCipher.doFinal(aesKey)

        return "${b64NoWrap(encKeyBytes)}.${b64NoWrap(iv)}.${b64NoWrap(sealedBytes)}"
    }

    fun decryptResponse(encryptedB64: String, key: String): String? {
        if (encryptedB64.isBlank() || key.isEmpty()) return null
        return try {
            val aesKeyBytes = md5Hex(DECRYPT_SALT + key).toByteArray(Charsets.UTF_8)
            val ivBytes = md5Hex(key + DECRYPT_SALT).substring(0, 16).toByteArray(Charsets.UTF_8)
            val cipherBytes = Base64.decode(encryptedB64.trim(), Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKeyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun b64NoWrap(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun randomHex(byteCount: Int): String {
        val buf = ByteArray(byteCount).also { rng.nextBytes(it) }
        return buf.toHexLower()
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .toHexLower()

    private fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHexLower()
    }

    private fun ByteArray.toHexLower(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }
}
