package eu.kanade.tachiyomi.lib.streamwishextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun videosFromUrl(url: String, prefix: String) = videosFromUrl(url) { "$prefix - $it" }

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamWish - $quality" }): List<Video> {

        val id = getEmbedId(url)
        for (domain in DOMAINS) {
            val fullUrl = if (id.startsWith("https://")) id else "https://$domain/$id"
            try {

                val response = client.newCall(GET(fullUrl, headers)).execute()
                if (!response.isSuccessful) continue

                val body = response.body.string()
                if (body.isEmpty()) continue
                val doc = Jsoup.parse(body)

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

                if (masterUrl != null) {
                    val subtitleList = extractSubtitles(scriptBody)

                    return playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        referer = "https://${fullUrl.toHttpUrl().host}/",
                        videoNameGen = videoNameGen,
                        subtitleList = playlistUtils.fixSubtitles(subtitleList),
                    )
                }
            } catch (e: Exception) {
                if (id.startsWith("https://")) return emptyList()
            }
        }

        return emptyList()
    }

    private fun getEmbedId(url: String): String {
        val regex = Regex(""".*/(?:e|f|d)/([a-zA-Z0-9]+)""")
        val match = regex.find(url)

        val id = match?.groupValues?.get(1)
            ?: return url

        // Prevent redirect
        return id
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
    private val DOMAINS = listOf(
        "streamwish.com",
        "niramirus.com",
        "medixiru.com"
    )
}
