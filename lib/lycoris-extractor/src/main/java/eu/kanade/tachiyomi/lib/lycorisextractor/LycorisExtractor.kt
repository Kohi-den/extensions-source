package eu.kanade.tachiyomi.lib.lycorisextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import android.util.Base64
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.nio.charset.Charset

class LycorisCafeExtractor(private val client: OkHttpClient) {

    private val GETSECONDARYURL = "https://www.lycoris.cafe/api/watch/getSecondaryLink"

    private val GETLNKURL = "https://www.lycoris.cafe/api/watch/getLink"

    private val json: Json by injectLazy()

    // Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/lycoris.py
    fun getVideosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {

        val videos = mutableListOf<Video>()
        val embedHeaders = headers.newBuilder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
            .build()

        val document = client.newCall(
            GET(url, headers = embedHeaders),
        ).execute().asJsoup()

        val scripts = document.select("script")

        val episodeDataPattern = Regex("episodeInfo\\s*:\\s*(\\{.*?\\}),", RegexOption.DOT_MATCHES_ALL)
        var episodeData: String? = null

        for (script in scripts) {
            val content = script.data()
            val match = episodeDataPattern.find(content)

            if (match != null) {
                episodeData = match.groupValues[1]
                break
            }
        }

        val result = mutableMapOf<String, String?>()

        val patterns = listOf(
            "id" to Regex("id\\s*:\\s*(\\d+)"),
            "FHD" to Regex("FHD\\s*:\\s*\"([^\"]+)\""),
            "HD" to Regex("HD\\s*:\\s*\"([^\"]+)\""),
            "SD" to Regex("SD\\s*:\\s*\"([^\"]+)\"")
        )

        patterns.forEach { (key, pattern) ->
            result[key] = episodeData?.let { pattern.find(it)?.groups?.get(1)?.value }
        }

        var linkList: String? = fetchAndDecodeVideo(client, result["id"].toString(), isSecondary = false).toString()

        val fhdLink = fetchAndDecodeVideo(client, result["FHD"].toString(), isSecondary = true).toString()
        val sdLink = fetchAndDecodeVideo(client, result["SD"].toString(), isSecondary = true).toString()
        val hdLink = fetchAndDecodeVideo(client, result["HD"].toString(), isSecondary = true).toString()


        if (linkList.isNullOrBlank() || linkList == "{}") {
            if (fhdLink.isNotEmpty()) {
                videos.add(Video(fhdLink, "${prefix}lycoris.cafe - 1080p", fhdLink))
            }
            if (hdLink.isNotEmpty()) {
                videos.add(Video(hdLink, "${prefix}lycoris.cafe - 720p", hdLink))
            }
            if (sdLink.isNotEmpty()) {
                videos.add(Video(sdLink, "${prefix}lycoris.cafe - 480p", sdLink))
            }
        }else {
            val videoLinks = Json.decodeFromString<VideoLinks>(linkList)

            videoLinks.FHD?.takeIf { checkLinks(client, it) }?.let {
                videos.add(Video(it, "${prefix}lycoris.cafe - 1080p", it))
            }?: videos.add(Video(fhdLink, "${prefix}lycoris.cafe - 1080p", fhdLink))

            videoLinks.HD?.takeIf { checkLinks(client, it) }?.let {
                videos.add(Video(it, "${prefix}lycoris.cafe - 720p", it))
            }?: videos.add(Video(hdLink, "${prefix}lycoris.cafe - 720p", hdLink))

            videoLinks.SD?.takeIf { checkLinks(client, it) }?.let {
                videos.add(Video(it, "${prefix}lycoris.cafe - 480p", it))
            }?: videos.add(Video(sdLink, "${prefix}lycoris.cafe - 480p", sdLink))
        }
        return videos

    }

    private fun decodeVideoLinks(encodedUrl: String?): Any? {
        if (encodedUrl.isNullOrEmpty()) {
            return null
        }

        if (!encodedUrl.endsWith("LC")) {
            return encodedUrl
        }

        val encodedUrlWithoutSignature = encodedUrl.dropLast(2)

        val decoded = encodedUrlWithoutSignature
            .reversed()
            .map { (it.code - 7).toChar() }
            .joinToString("")

        return try {
            val base64Decoded = Base64.decode(decoded, Base64.DEFAULT)
            base64Decoded.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchAndDecodeVideo(client: OkHttpClient, episodeId: String, isSecondary: Boolean = false): Any? {
            val url: HttpUrl

            if (isSecondary) {
                val convertedText = episodeId.toByteArray(Charset.forName("UTF-8")).toString(Charset.forName("ISO-8859-1"))
                val unicodeEscape = decodePythonEscape(convertedText)
                val finalText = unicodeEscape.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)

                url = GETLNKURL.toHttpUrl().newBuilder()
                    ?.addQueryParameter("link", finalText)
                    ?.build() ?: throw IllegalStateException("Invalid URL")
            } else {
                url = GETSECONDARYURL.toHttpUrl().newBuilder()
                    ?.addQueryParameter("id", episodeId)
                    ?.build() ?: throw IllegalStateException("Invalid URL")
            }
            client.newCall(GET(url))
                .execute()
                .use { response ->
                    val data = response.body.string() ?: ""
                    return decodeVideoLinks(data)
                }
    }

    private fun checkLinks(client: OkHttpClient, link: String): Boolean {
        client.newCall(GET(link)).execute().use { response ->
            return response.code.toString() == "200"
        }
    }
    //thx deepseek
    private fun decodePythonEscape(text: String): String {
        // 1. Obsługa kontynuacji linii (backslash + newline)
        val withoutLineContinuation = text.replace("\\\n", "")

        // 2. Regex do wykrywania wszystkich sekwencji escape
        val regex = Regex(
            """\\U([0-9a-fA-F]{8})|""" +     // \UXXXXXXXX
                """\\u([0-9a-fA-F]{4})|""" +     // \uXXXX
                """\\x([0-9a-fA-F]{2})|""" +     // \xHH
                """\\([0-7]{1,3})|""" +          // \OOO (octal)
                """\\([btnfr"'$\\\\])"""         // \n, \t, itd.
        )

        return regex.replace(withoutLineContinuation) { match ->
            val (u8, u4, x2, octal, simple) = match.destructured
            when {
                u8.isNotEmpty() -> handleUnicode8(u8)
                u4.isNotEmpty() -> handleUnicode4(u4)
                x2.isNotEmpty() -> handleHex(x2)
                octal.isNotEmpty() -> handleOctal(octal)
                simple.isNotEmpty() -> handleSimple(simple)
                else -> match.value
            }
        }
    }

    private fun handleUnicode8(hex: String): String {
        val codePoint = hex.toInt(16)
        return if (codePoint in 0..0x10FFFF) {
            String(intArrayOf(codePoint), 0, 1)
        } else {
            "\\U$hex"
        }
    }

    private fun handleUnicode4(hex: String) = hex.toInt(16).toChar().toString()
    private fun handleHex(hex: String) = hex.toInt(16).toChar().toString()

    private fun handleOctal(octal: String): String {
        val value = octal.toInt(8)
        return (value and 0xFF).toChar().toString()
    }

    private fun handleSimple(c: String): String = when (c) {
        "b" -> "\u0008"
        "t" -> "\t"
        "n" -> "\n"
        "f" -> "\u000C"
        "r" -> "\r"
        "\"" -> "\""
        "'" -> "'"
        "$" -> "$"
        "\\" -> "\\"
        else -> "\\$c"
    }

    @Serializable
    data class VideoLinks(
        val HD: String? = null,
        val SD: String? = null,
        val FHD: String? = null,
        val Source: String? = null,
        val preview: String? = null,
        val SourceMKV: String? = null
    )


}





