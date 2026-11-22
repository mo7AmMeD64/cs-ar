package com.animeday

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Response decryption for the /servers endpoints. The token itself is produced
 * by the original libnative-lib.so (see NativeToken).
 */
internal object AnimeDayCrypto {
    private const val DECRYPT_SALT = "Gm4il@G00Gl3.Com"
    private const val HEX_CHARS = "0123456789abcdef"

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

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .toHexLower()

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
