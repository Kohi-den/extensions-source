package eu.kanade.tachiyomi.lib.filemoonextractor

import android.content.SharedPreferences
import android.util.Base64
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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilemoonExtractor(private val client: OkHttpClient, private val preferences: SharedPreferences? = null) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json: Json by injectLazy()

    //Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/filemoon.py
    fun videosFromUrl(url: String, prefix: String = "Filemoon - ", headers: Headers? = null): List<Video> {
        return try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val mediaId = httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: return emptyList()

            val videoHeaders = (headers?.newBuilder() ?: Headers.Builder())
                .set("Referer", "https://$host/")
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            val apiUrl = "https://$host/api/videos/$mediaId/embed/playback"
            val response = client.newCall(GET(apiUrl, videoHeaders)).execute()
            val responseData = response.body.string()
            val playbackJson = json.decodeFromString<PlaybackResponse>(responseData)

            var finalSources: List<VideoSource>? = null

            if (!playbackJson.sources.isNullOrEmpty()) {
                finalSources = playbackJson.sources
            } else if (playbackJson.playback != null) {
                val pb = playbackJson.playback
                val iv = decodeBase64(pb.iv)
                val key = pb.key_parts.map { decodeBase64(it) }.reduce { acc, bytes -> acc + bytes }
                val payload = decodeBase64(pb.payload)

                val secretKey = SecretKeySpec(key, "AES")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

                val decryptedData = cipher.doFinal(payload).toString(Charsets.UTF_8)
                val decryptedJson = json.decodeFromString<PlaybackResponse>(decryptedData)
                finalSources = decryptedJson.sources
            }

            if (finalSources.isNullOrEmpty()) return emptyList()

            finalSources.flatMap { source ->
                val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<Video>()
                val quality = source.label ?: "Unknown"

                playlistUtils.extractFromHls(
                    streamUrl,
                    referer = "https://$host/",
                    videoNameGen = { "$prefix$it" },
                )
            }

        } catch (e: Exception) {
            emptyList()
        }
    }
    private fun decodeBase64(input: String): ByteArray {
        return Base64.decode(input, Base64.URL_SAFE or Base64.NO_WRAP)
    }
    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        val key_parts: List<String>,
        val payload: String
    )

    @Serializable
    data class VideoSource(
        val file: String? = null,
        val url: String? = null,
        val label: String? = "Default"
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
