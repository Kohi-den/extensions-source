package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.util.Base64
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AniwaveUtils {

    fun vrfEncrypt(input: String): String {
        var vrf = input
        ORDER.sortedBy {
            it.first
        }.forEach { item ->
            when (item.second) {
                "exchange" -> vrf = exchange(vrf, item.third)
                "rc4" -> vrf = rc4Encrypt(item.third.get(0), vrf)
                "reverse" -> vrf = vrf.reversed()
                "base64" -> vrf = Base64.encode(vrf.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
                else -> {}
            }
        }

        return java.net.URLEncoder.encode(vrf, "utf-8")
    }

    fun vrfDecrypt(input: String): String {
        var vrf = input
        ORDER.sortedByDescending {
            it.first
        }.forEach { item ->
            when (item.second) {
                "exchange" -> vrf = exchange(vrf, item.third.reversed())
                "rc4" -> vrf = rc4Decrypt(item.third.get(0), vrf)
                "reverse" -> vrf = vrf.reversed()
                "base64" -> vrf = Base64.decode(vrf, Base64.URL_SAFE).toString(Charsets.UTF_8)
                else -> {}
            }
        }

        return URLDecoder.decode(vrf, "utf-8")
    }

    private fun rc4Encrypt(key: String, input: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)

        var output = cipher.doFinal(input.toByteArray())
        output = Base64.encode(output, Base64.URL_SAFE or Base64.NO_WRAP)
        return output.toString(Charsets.UTF_8)
    }

    private fun rc4Decrypt(key: String, input: String): String {
        var vrf = input.toByteArray()
        vrf = Base64.decode(vrf, Base64.URL_SAFE)

        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        vrf = cipher.doFinal(vrf)
        return vrf.toString(Charsets.UTF_8)
    }

    private fun exchange(input: String, keys: List<String>): String {
        val key1 = keys.get(0)
        val key2 = keys.get(1)
        return input.map { i ->
            val index = key1.indexOf(i)
            if (index != -1) {
                key2[index]
            } else {
                i
            }
        }.joinToString("")
    }

    private fun rot13(vrf: ByteArray): ByteArray {
        for (i in vrf.indices) {
            val byte = vrf[i]
            if (byte in 'A'.code..'Z'.code) {
                vrf[i] = ((byte - 'A'.code + 13) % 26 + 'A'.code).toByte()
            } else if (byte in 'a'.code..'z'.code) {
                vrf[i] = ((byte - 'a'.code + 13) % 26 + 'a'.code).toByte()
            }
        }
        return vrf
    }

    private fun vrfShift(vrf: ByteArray): ByteArray {
        for (i in vrf.indices) {
            val shift = arrayOf(-2, -4, -5, 6, 2, -3, 3, 6)[i % 8]
            vrf[i] = vrf[i].plus(shift).toByte()
        }
        return vrf
    }

    companion object {
        private val EXCHANGE_KEY_1 = listOf("AP6GeR8H0lwUz1", "UAz8Gwl10P6ReH")
        private val KEY_1 = "ItFKjuWokn4ZpB"
        private val KEY_2 = "fOyt97QWFB3"
        private val EXCHANGE_KEY_2 = listOf("1majSlPQd2M5", "da1l2jSmP5QM")
        private val EXCHANGE_KEY_3 = listOf("CPYvHj09Au3", "0jHA9CPYu3v")
        private val KEY_3 = "736y1uTJpBLUX"

        private val ORDER = listOf(
            Triple(1, "exchange", EXCHANGE_KEY_1),
            Triple(2, "rc4", listOf(KEY_1)),
            Triple(3, "rc4", listOf(KEY_2)),
            Triple(4, "exchange", EXCHANGE_KEY_2),
            Triple(5, "exchange", EXCHANGE_KEY_3),
            Triple(5, "reverse", emptyList()),
            Triple(6, "rc4", listOf(KEY_3)),
            Triple(7, "base64", emptyList()),
        )
    }
}
