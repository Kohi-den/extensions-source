package eu.kanade.tachiyomi.lib.voeextractor

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class VoeExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    private val clientDdos by lazy { client.newBuilder().addInterceptor(DdosGuardInterceptor(client)).build() }

    private val playlistUtils by lazy { PlaylistUtils(clientDdos, headers) }

    private val redirectRegex = Regex("""window.location.href\s*=\s*'([^']+)';""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        var document = clientDdos.newCall(GET(url, headers)).execute().asJsoup()
        val scriptData = document.selectFirst("script")?.data()
        val redirectMatch = scriptData?.let { redirectRegex.find(it) }

        if (redirectMatch != null) {
            val originalUrl = redirectMatch.groupValues[1]
            document = clientDdos.newCall(GET(originalUrl, headers)).execute().asJsoup()
        }

        val encodedString = document.selectFirst("script[type=application/json]")?.data()
            ?.trim()?.substringAfter("[\"")?.substringBeforeLast("\"]") ?: return emptyList()

        val decryptedJson = decryptF7(encodedString) ?: return emptyList()
        val m3u8 = decryptedJson["source"]?.jsonPrimitive?.content
        val mp4 = decryptedJson["direct_access_url"]?.jsonPrimitive?.content

        if (m3u8 != null) {
            playlistUtils.extractFromHls(m3u8,
                videoNameGen = { quality -> "${prefix}Voe:$quality" }
            ).let { videoList.addAll(it) }
        }
        if (mp4 != null) {
            videoList.add(
                Video(mp4, "${prefix}Voe:MP4", mp4)
            )
        }

        return videoList
    }

    private fun decryptF7(p8: String): JsonObject? {
        return try {
            val vF = rot13(p8)
            val vF2 = replacePatterns(vF)
            val vF3 = removeUnderscores(vF2)
            val vF4 = base64Decode(vF3)
            val vF5 = charShift(vF4, 3)
            val vF6 = reverse(vF5)
            val vAtob = base64Decode(vF6)
            json.decodeFromString<JsonObject>(vAtob)
        } catch (e: Exception) {
            Log.e("VoeExtractor", "Decryption error: ${e.message}")
            null
        }
    }

    private fun rot13(input: String): String {
        return input.map { c ->
            when (c) {
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private val patternsRegex = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&").joinToString("|") { Regex.escape(it) }.toRegex()

    private fun replacePatterns(input: String): String {
        return input.replace(patternsRegex, "_")
    }

    private fun removeUnderscores(input: String): String = input.replace("_", "")

    private fun charShift(input: String, shift: Int): String {
        return input.map { (it.code - shift).toChar() }.joinToString("")
    }

    private fun reverse(input: String): String = input.reversed()

    private fun base64Decode(input: String): String {
        val decodedBytes = Base64.decode(input, Base64.DEFAULT)
        return String(decodedBytes, Charsets.ISO_8859_1)
    }
}
