package eu.kanade.tachiyomi.lib.filemoonextractor

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilemoonExtractor(private val client: OkHttpClient, private val preferences: SharedPreferences? = null) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json: Json by injectLazy()

    //Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/filemoon.py
    fun videosFromUrl(
        url: String,
        prefix: String = "Filemoon - ",
        headers: Headers? = null,
    ): List<Video> {
        return try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val mediaId = if (httpUrl.pathSegments[0] == "e") {
                httpUrl.pathSegments[1]
            } else {
                httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: return emptyList()
            }

            val userAgent = headers?.get("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

            val embedUrl =
                client.newCall(GET("https://$host/api/videos/$mediaId/embed/details"))
                    .execute().body.string()
                    .substringAfter("embed_frame_url", "")
                    .substringAfter(":")
                    .substringAfter('"')
                    .substringBefore('"')

            if (embedUrl.isBlank()) {
                return emptyList()
            }

            val embedHost = embedUrl.toHttpUrl().host

            val playbackHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("Referer", embedUrl)
                set("X-Embed-Origin", host)
                set("X-Embed-Parent", url.encodeUrlPath())
                set("X-Embed-Referer", url)
                set("Accept", "*/*")
                set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                set("Cache-Control", "no-cache")
                set("Pragma", "no-cache")
                set("Priority", "u=1, i")
                set("Sec-Fetch-Dest", "empty")
                set("Sec-Fetch-Mode", "cors")
                set("Sec-Fetch-Site", "same-origin")
                set("Sec-Fetch-Storage-Access", "active")
                set("User-Agent", userAgent)

            }.build()

            val apiUrl = "https://$embedHost/api/videos/$mediaId/embed/playback"
            val response = client.newCall(GET(apiUrl, playbackHeaders)).execute()
            val responseData = response.body.string()
            val playbackJson = json.decodeFromString<PlaybackResponse>(responseData)

            var finalSources: List<VideoSource>? = null

            if (!playbackJson.sources.isNullOrEmpty()) {
                finalSources = playbackJson.sources
            } else if (playbackJson.playback != null) {
                val pb = playbackJson.playback
                val decryptedData = decrypt(pb)
                val decryptedJson = json.decodeFromString<PlaybackResponse>(decryptedData)
                finalSources = decryptedJson.sources
            }

            if (finalSources.isNullOrEmpty()) return emptyList()

            val videoHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("Referer", "https://$host/")
                set("User-Agent", userAgent)
                removeAll("Origin")
            }.build()

            finalSources.flatMap { source ->
                val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<Video>()
                val quality = source.label ?: "Unknown"

                playlistUtils.extractFromHls(
                    streamUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$prefix${it.replace("Video", quality)}p" },
                )
            }

        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Failed to extract video from $url", e)
            emptyList()
        }
    }

    private fun decrypt(input: PlaybackData): String {
        val keyBytes = input.key_parts
            .map { decodeBase64Url(it) }
            .fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val ivBytes = decodeBase64Url(input.iv)
        val payloadBytes = decodeBase64Url(input.payload)

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(payloadBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input
            .replace('-', '+')
            .replace('_', '/')

        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }

        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        val key_parts: List<String>,
        val payload: String,
    )

    @Serializable
    data class VideoSource(
        val file: String? = null,
        val url: String? = null,
        val label: String? = "Default",
    )

    companion object {
        fun addSubtitlePref(screen: PreferenceScreen) {
            EditTextPreference(screen.context).apply {
                key = PREF_SUBTITLE_KEY
                title = "Filemoon subtitle preference"
                summary = "Leave blank to use all subs"
                setDefaultValue(PREF_SUBTITLE_DEFAULT)
            }.also(screen::addPreference)
        }

        private const val PREF_SUBTITLE_KEY = "pref_filemoon_sub_lang_key"
        private const val PREF_SUBTITLE_DEFAULT = "eng"
    }
}

fun String.encodeUrlPath(): String {
    val uri = URI(this)

    val encodedPath = uri.rawPath
        .split("/")
        .joinToString("/") { segment ->
            if (segment.isEmpty()) {
                ""
            } else URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                .replace("+", "%20")
        }

    return URI(
        uri.scheme,
        uri.rawAuthority,
        encodedPath,
        uri.rawQuery,
        uri.rawFragment,
    ).toString()
}
