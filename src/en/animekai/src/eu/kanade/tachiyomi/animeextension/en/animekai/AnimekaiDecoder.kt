package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimekaiDecoder {

    fun generateToken(id: String): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val base = "$id|$timestamp"
        val encrypted = encrypt(base)
        return URLEncoder.encode(encrypted, StandardCharsets.UTF_8.toString())
    }

    fun decodeIframeData(data: String): String {
        return decrypt(data)
    }

    fun decode(base64Data: String, steps: List<List<String>>): String {
        var result = base64Data
        for (step in steps) {
            when (step[0]) {
                "replace" -> {
                    result = result.replace(step[1], step[2])
                }
                "reverse" -> {
                    result = result.reversed()
                }
                "base64" -> {
                    result = decodeBase64(result).toString(StandardCharsets.UTF_8)
                }
                "xor" -> {
                    val key = step[1].toByteArray(StandardCharsets.UTF_8)
                    val bytes = decodeBase64(result)
                    val xored = bytes.mapIndexed { i, b -> b xor key[i % key.size] }.toByteArray()
                    result = xored.toString(StandardCharsets.UTF_8)
                }
            }
        }
        return result
    }

    private fun encrypt(text: String): String {
        val keyBytes = decodeBase64(SECRET)
        val ivSpec = IvParameterSpec(IV.toByteArray(StandardCharsets.UTF_8))
        val keySpec = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

        val encrypted = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return encodeBase64(encrypted)
    }

    private fun decrypt(base64Data: String): String {
        val keyBytes = decodeBase64(SECRET)
        val ivSpec = IvParameterSpec(IV.toByteArray(StandardCharsets.UTF_8))
        val keySpec = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val decrypted = cipher.doFinal(decodeBase64(base64Data))
        return String(decrypted, StandardCharsets.UTF_8)
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