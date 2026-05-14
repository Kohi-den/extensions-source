package eu.kanade.tachiyomi.animeextension.en.sakuhentai

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

class SakuhentaiExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val redirectRegex = Regex("""window\.location\.href\s*=\s*'([^']+)';""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()

        val document = try {
            client.newCall(GET(url, headers)).execute().asJsoup()
        } catch (e: Exception) {
            Log.w("Sakuhentai", "Failed to fetch embed page $url: ${e.message}")
            return videoList
        }

        val redirectUrl = document.select("script").mapNotNull { script ->
            redirectRegex.find(script.data())?.groupValues?.get(1)
        }.firstOrNull()

        val finalUrl = redirectUrl ?: url

        val voeDoc = try {
            client.newCall(GET(finalUrl, headers)).execute().asJsoup()
        } catch (e: Exception) {
            Log.w("Sakuhentai", "Failed to fetch Voe page $finalUrl: ${e.message}")
            return videoList
        }

        val encodedString = voeDoc.selectFirst("script[type=application/json]")?.data()
            ?.trim()
            ?.substringAfter("[\"")
            ?.substringBeforeLast("\"]")
            ?: return videoList

        val decryptedJson = decryptF7(encodedString) ?: return videoList

        val m3u8 = decryptedJson["source"]?.jsonPrimitive?.content
        if (m3u8 != null) {
            try {
                playlistUtils.extractFromHls(m3u8, videoNameGen = { quality ->
                    "${prefix}Voe:$quality"
                }).forEach { videoList.add(it) }
            } catch (e: Exception) {
                Log.w("Sakuhentai", "Failed to extract HLS from $m3u8: ${e.message}")
            }
        }

        val mp4 = decryptedJson["direct_access_url"]?.jsonPrimitive?.content
        if (mp4 != null) {
            videoList.add(Video(mp4, "${prefix}Voe:MP4", mp4, headers))
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
            Log.w("Sakuhentai", "Decryption failed: ${e.message}")
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

    private val patternsRegex = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        .joinToString("|") { Regex.escape(it) }.toRegex()

    private fun replacePatterns(input: String): String {
        return input.replace(patternsRegex, "_")
    }

    private fun removeUnderscores(input: String): String = input.replace("_", "")

    private fun charShift(input: String, shift: Int): String {
        return input.map { (it.code - shift).toChar() }.joinToString("")
    }

    private fun reverse(input: String): String = input.reversed()

    // ISO_8859_1 is intentionally used here, not UTF-8.
    // The decryption pipeline (decryptF7) performs two Base64 decodes with
    // intermediate string transformations (charShift, reverse) between them.
    // The first Base64 decode produces raw bytes that are NOT valid UTF-8 —
    // they are an intermediate binary blob. ISO_8859_1 provides a bijective
    // 1:1 mapping between each byte (0x00-0xFF) and a Unicode code point
    // (U+0000-U+00FF), preserving the raw bytes through the intermediate
    // string operations intact. Using UTF-8 would corrupt bytes 0x80-0xFF
    // as multi-byte sequence starters/continuation bytes, breaking the
    // second Base64 decode.
    private fun base64Decode(input: String): String {
        val decodedBytes = Base64.decode(input, Base64.DEFAULT)
        return String(decodedBytes, Charsets.ISO_8859_1)
    }
}
