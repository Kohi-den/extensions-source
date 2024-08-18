package eu.kanade.tachiyomi.lib.vidhideextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidHideExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val sourceRegex = Regex("""sources:\[\{file:"(.*?)"""")

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "VidHide - $quality" }): List<Video> {
        val script = fetchAndExtractScript(url) ?: return emptyList()
        val videoUrl = extractVideoUrl(script) ?: return emptyList()
        val subtitleList = extractSubtitles(script)

        return playlistUtils.extractFromHls(
            videoUrl,
            referer = url,
            videoNameGen = videoNameGen,
            subtitleList = subtitleList
        )
    }

    private fun fetchAndExtractScript(url: String): String? {
        return client.newCall(GET(url, headers)).execute()
            .asJsoup()
            .select("script")
            .find { it.html().contains("eval(function(p,a,c,k,e,d)") }
            ?.html()
            ?.let { JsUnpacker(it).unpack() }
    }

    private fun extractVideoUrl(script: String): String? {
        return sourceRegex.find(script)?.groupValues?.get(1)
    }

    private fun extractSubtitles(script: String): List<Track> {
        return try {
            val subtitleStr = script
                .substringAfter("tracks")
                .substringAfter("[")
                .substringBefore("]")
            json.decodeFromString<List<TrackDto>>("[$subtitleStr]")
                .filter { it.kind.equals("captions", true) }
                .map { Track(it.file, it.label ?: "") }
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    @Serializable
    private data class TrackDto(
        val file: String,
        val kind: String,
        val label: String? = null,
    )
}
