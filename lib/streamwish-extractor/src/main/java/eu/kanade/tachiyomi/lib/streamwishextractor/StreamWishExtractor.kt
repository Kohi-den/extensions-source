package eu.kanade.tachiyomi.lib.streamwishextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun videosFromUrl(url: String, prefix: String) = videosFromUrl(url) { "$prefix - $it" }

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamWish - $quality" }): List<Video> {

        val doc = client.newCall(GET(getEmbedUrl(url), headers)).execute().asJsoup()

        val scriptBody = doc.selectFirst("script:containsData(m3u8)")?.data()
            ?.let { script ->
                if (script.contains("eval(function(p,a,c")) {
                    JsUnpacker.unpackAndCombine(script)
                } else {
                    script
                }
            }
        val masterUrl = scriptBody?.let {
            M3U8_REGEX.find(it)?.value
        }
            ?: return emptyList()

        val subtitleList = extractSubtitles(scriptBody)

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = "https://${url.toHttpUrl().host}/",
            videoNameGen = videoNameGen,
            subtitleList = playlistUtils.fixSubtitles(subtitleList),
        )
    }

    private fun getEmbedUrl(url: String): String {
        return if (url.contains("/f/")) {
            val videoId = url.substringAfter("/f/")
            "https://streamwish.com/$videoId"
        } else {
            url
        }
    }

    private fun extractSubtitles(script: String): List<Track> {
        return try {
            val subtitleStr = script
                .substringAfter("tracks")
                .substringAfter("[")
                .substringBefore("]")
            val fixedSubtitleStr = FIX_TRACKS_REGEX.replace(subtitleStr) { match ->
                "\"${match.value}\""
            }

            json.decodeFromString<List<TrackDto>>("[$fixedSubtitleStr]")
                .filter { it.kind.equals("captions", true) }
                .map { Track(it.file, it.label ?: "") }
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    @Serializable
    private data class TrackDto(val file: String, val kind: String, val label: String? = null)

    private val M3U8_REGEX = Regex("""https[^"]*m3u8[^"]*""")
    private val FIX_TRACKS_REGEX = Regex("""(?<!["])(file|kind|label)(?!["])""")
}
