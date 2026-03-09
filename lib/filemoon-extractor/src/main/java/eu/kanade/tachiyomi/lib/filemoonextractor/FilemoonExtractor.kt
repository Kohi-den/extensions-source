package eu.kanade.tachiyomi.lib.filemoonextractor

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilemoonExtractor(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences? = null,
) {

    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String = "Filemoon - ", headers: Headers? = null): List<Video> {
        var httpUrl = url.toHttpUrl()
        val videoHeaders = (headers?.newBuilder() ?: Headers.Builder())
            .set("Referer", url)
            .set("Origin", "https://${httpUrl.host}")
            .build()

        val doc = client.newCall(GET(url, videoHeaders)).execute().asJsoup()

        // Try legacy eval/JsPacker method
        val jsEval = doc.selectFirst("script:containsData(eval):containsData(m3u8)")?.data()
            ?: doc.selectFirst("iframe[src]")?.attr("src")?.let { iframeUrl ->
                httpUrl = iframeUrl.toHttpUrl()
                client.newCall(GET(iframeUrl, videoHeaders)).execute().asJsoup()
                    .selectFirst("script:containsData(eval):containsData(m3u8)")?.data()
            }

        if (jsEval != null) {
            return legacyExtract(jsEval, httpUrl, prefix, videoHeaders)
        }

        // New Byse SPA API method
        return apiExtract(httpUrl, prefix, videoHeaders)
    }

    private fun legacyExtract(
        jsEval: String,
        httpUrl: HttpUrl,
        prefix: String,
        videoHeaders: Headers,
    ): List<Video> {
        val unpacked = JsUnpacker.unpackAndCombine(jsEval).orEmpty()
        val masterUrl = unpacked.takeIf(String::isNotBlank)
            ?.substringAfter("{file:\"", "")
            ?.substringBefore("\"}", "")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val subtitleTracks = buildList {
            val subUrl = httpUrl.queryParameter("sub.info")
                ?: unpacked.substringAfter("fetch('", "")
                    .substringBefore("').")
                    .takeIf(String::isNotBlank)
            if (subUrl != null) {
                runCatching {
                    client.newCall(GET(subUrl, videoHeaders)).execute()
                        .body.string()
                        .let { json.decodeFromString<List<SubtitleDto>>(it) }
                        .forEach { add(Track(it.file, it.label)) }
                }
            }
        }

        val videoList = playlistUtils.extractFromHls(
            masterUrl,
            subtitleList = subtitleTracks,
            referer = "https://${httpUrl.host}/",
            videoNameGen = { "$prefix$it" },
        )

        return filterSubtitles(videoList)
    }

    private fun apiExtract(
        httpUrl: HttpUrl,
        prefix: String,
        videoHeaders: Headers,
    ): List<Video> {
        val code = httpUrl.pathSegments.lastOrNull { it.isNotBlank() }
            ?: return emptyList()
        val apiUrl = "https://${httpUrl.host}/api/videos/$code/embed/playback"
        val apiHeaders = videoHeaders.newBuilder()
            .set("Accept", "application/json")
            .build()

        val responseBody = client.newCall(GET(apiUrl, apiHeaders)).execute().body.string()
        val playbackResponse = json.decodeFromString<PlaybackResponse>(responseBody)
        val playback = playbackResponse.playback ?: return emptyList()

        val decrypted = decryptPlayback(playback)
        val playbackData = json.decodeFromString<PlaybackData>(decrypted)

        val subtitleTracks = playbackData.tracks.mapNotNull { track ->
            track.url.takeIf { it.isNotBlank() }?.let { Track(it, track.label) }
        }

        val videoList = playbackData.sources.flatMap { source ->
            if (source.mimeType.contains("mpegurl", ignoreCase = true)) {
                playlistUtils.extractFromHls(
                    source.url,
                    subtitleList = subtitleTracks,
                    referer = "https://${httpUrl.host}/",
                    videoNameGen = { "$prefix$it" },
                )
            } else {
                listOf(
                    Video(
                        videoUrl = source.url,
                        videoTitle = "${prefix}${source.label}",
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        }

        return filterSubtitles(videoList)
    }

    private fun decryptPlayback(playback: PlaybackEncrypted): String {
        val key = playback.keyParts.fold(ByteArray(0)) { acc, part ->
            acc + Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING)
        }
        val iv = Base64.decode(playback.iv, Base64.URL_SAFE or Base64.NO_PADDING)
        val payload = Base64.decode(playback.payload, Base64.URL_SAFE or Base64.NO_PADDING)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv),
        )
        return String(cipher.doFinal(payload))
    }

    private fun filterSubtitles(videoList: List<Video>): List<Video> {
        val subPref = preferences?.getString(PREF_SUBTITLE_KEY, PREF_SUBTITLE_DEFAULT).orEmpty()
        return videoList.map {
            Video(
                videoTitle = it.videoTitle,
                videoUrl = it.videoUrl,
                subtitleTracks = it.subtitleTracks.filter { tracks -> tracks.lang.contains(subPref, true) },
                audioTracks = it.audioTracks,
            )
        }
    }

    @Serializable
    data class SubtitleDto(val file: String, val label: String)

    @Serializable
    data class PlaybackResponse(
        val playback: PlaybackEncrypted? = null,
    )

    @Serializable
    data class PlaybackEncrypted(
        @SerialName("key_parts") val keyParts: List<String>,
        val iv: String,
        val payload: String,
    )

    @Serializable
    data class PlaybackData(
        val sources: List<PlaybackSource> = emptyList(),
        val tracks: List<PlaybackTrack> = emptyList(),
    )

    @Serializable
    data class PlaybackSource(
        val url: String,
        val label: String = "",
        @SerialName("mime_type") val mimeType: String = "",
    )

    @Serializable
    data class PlaybackTrack(
        val url: String = "",
        val label: String = "",
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
