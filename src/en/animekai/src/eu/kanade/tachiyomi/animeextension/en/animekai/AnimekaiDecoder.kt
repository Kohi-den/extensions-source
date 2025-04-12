package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimekaiDecoder {
    companion object {
        private const val secret = "T3xKHVZBiNwbzVBDLTlaTw=="
        private const val iv = "3rR5dURR27mN"

        private fun md5(text: String): ByteArray {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(text.toByteArray())
        }

        private fun decodeBase64(data: String): ByteArray {
            return Base64.decode(data, Base64.NO_WRAP)
        }

        private fun encodeBase64(data: ByteArray): String {
            return Base64.encodeToString(data, Base64.NO_WRAP)
        }
    }

    fun generateToken(id: String): String {
        val time = (System.currentTimeMillis() / 1000).toString()
        val base = "$id|$time"
        val encrypted = encrypt(base)
        return URLEncoder.encode(encrypted, "UTF-8")
    }

    fun decodeIframeData(data: String): String {
        return decrypt(data)
    }

    private fun encrypt(text: String): String {
        val keyBytes = decodeBase64(secret)
        val ivSpec = IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8))
        val keySpec = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

        val encrypted = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return encodeBase64(encrypted)
    }

    private fun decrypt(base64Data: String): String {
        val keyBytes = decodeBase64(secret)
        val ivSpec = IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8))
        val keySpec = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val decrypted = cipher.doFinal(decodeBase64(base64Data))
        return String(decrypted, StandardCharsets.UTF_8)
    }
}
