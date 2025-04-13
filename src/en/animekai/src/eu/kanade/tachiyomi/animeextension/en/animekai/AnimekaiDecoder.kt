package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimekaiDecoder {

    fun generateToken(id: String): String {
        val time = (System.currentTimeMillis() / 1000).toString()
        val base = "$id|$time"
        val encrypted = encrypt(base)
        return URLEncoder.encode(encrypted, StandardCharsets.UTF_8.toString())
    }

    fun decodeIframeData(data: String): String {
        return decrypt(data)
    }

    private fun encrypt(text: String): String {
        return try {
            val keyBytes = decodeBase64(SECRET)
            val ivSpec = IvParameterSpec(IV.toByteArray(StandardCharsets.UTF_8))
            val keySpec = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

            val encrypted = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
            encodeBase64(encrypted)
        } catch (e: Exception) {
            e.printStackTrace()
            "" // Return an empty string on failure
        }
    }

    private fun decrypt(base64Data: String): String {
        return try {
            val keyBytes = decodeBase64(SECRET)
            val ivSpec = IvParameterSpec(IV.toByteArray(StandardCharsets.UTF_8))
            val keySpec = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decrypted = cipher.doFinal(decodeBase64(base64Data))
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            "" // Return an empty string on failure
        }
    }

    companion object {
        private const val SECRET = "T3xKHVZBiNwbzVBDLTlaTw=="
        private const val IV = "3rR5dURR27mN"

        private fun decodeBase64(data: String): ByteArray {
            return Base64.decode(data, Base64.NO_WRAP)
        }

        private fun encodeBase64(data: ByteArray): String {
            return Base64.encodeToString(data, Base64.NO_WRAP)
        }
    }
}
