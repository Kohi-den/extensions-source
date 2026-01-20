package eu.kanade.tachiyomi.lib.streamupextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class StreamupExtractor(private val client: OkHttpClient) {
    // Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/streamup.py
    fun getVideosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {
        val videos = mutableListOf<Video>()

        val response = client.newCall(GET(url, headers = headers)).execute()
        val body = response.body.string()

        val requestUrl = response.request.url
        val baseUrl = "${requestUrl.scheme}://${requestUrl.host}"

        var streamUrl: String? = null

        if (body.contains("decodePrintable95")) {
            val encodedMatch = Regex("""decodePrintable95\("([a-f0-9]+)"""").find(body)
            val shiftMatch = Regex("""__enc_shift\s*=\s*(\d+)""").find(body)

            if (encodedMatch != null && shiftMatch != null) {
                streamUrl = decodePrintable95(
                    encodedMatch.groupValues[1],
                    shiftMatch.groupValues[1].toInt()
                )
            }
        }

        if (streamUrl.isNullOrEmpty()) {
            val mediaId = url.substringAfterLast("/")
            val sessionIdMatch = Regex("'([a-f0-9]{32})'").find(body)
            val encryptedDataMatch = Regex("'([A-Za-z0-9+/=]{200,})'").find(body)

            if (sessionIdMatch != null && encryptedDataMatch != null) {
                val sessionId = sessionIdMatch.groupValues[1]
                val encryptedDataB64 = encryptedDataMatch.groupValues[1]

                val keyUrl = "$baseUrl/ajax/stream?session=$sessionId"
                val keyHeaders = Headers.Builder()
                    .add("Referer", url)
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()

                val keyResponse = client.newCall(GET(keyUrl, keyHeaders)).execute()
                val keyB64 = keyResponse.body.string().trim()

                streamUrl = decryptAES(encryptedDataB64, keyB64)
            } else {
                val sUrl = "$baseUrl/ajax/stream?filecode=$mediaId"
                val sResponse = client.newCall(GET(sUrl, Headers.Builder().add("Referer", url).build())).execute()

                streamUrl = sResponse.body.string().parseAs<StreamupResponse>().streaming_url
            }
        }

        if (!streamUrl.isNullOrEmpty()) {
            val headers = Headers.Builder()
                .add("Referer", "$baseUrl/")
                .add("Origin", baseUrl)
                .build()

            videos.add(Video(streamUrl, "${prefix}Streamup", streamUrl, headers = headers))
        }

        return videos
    }

    private fun decodePrintable95(encoded: String, shift: Int): String {
        return try {
            val bytes = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val intermediate = String(bytes, Charsets.ISO_8859_1)
            val decoded = StringBuilder()

            intermediate.forEachIndexed { index, char ->
                val s = char.code - 32
                var i = (s - shift - index) % 95
                if (i < 0) i += 95
                decoded.append((i + 32).toChar())
            }
            decoded.toString()
        } catch (e: Exception) { "" }
    }

    private fun decryptAES(encryptedDataB64: String, keyB64: String): String? {

            val key = Base64.decode(keyB64, Base64.DEFAULT)
            val encryptedData = Base64.decode(encryptedDataB64, Base64.DEFAULT)

            val iv = encryptedData.copyOfRange(0, 16)
            val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

            val decryptedStr = String(cipher.doFinal(ciphertext), Charsets.UTF_8)

            return decryptedStr.parseAs<StreamupResponse>().streaming_url

    }
}

    @Serializable
    data class StreamupResponse(
        val streaming_url: String? = null
    )
