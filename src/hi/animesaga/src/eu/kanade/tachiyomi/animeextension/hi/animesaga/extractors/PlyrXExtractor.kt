package eu.kanade.tachiyomi.animeextension.hi.anisaga.extractors

import eu.kanade.tachiyomi.animesource.model.SubtitleFile
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLDecoder

class PlyrXExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<Video> {
        val response = client.newCall(GET(url, headers = mapOf("Referer" to referer))).execute()
        val doc = Jsoup.parse(response.body.string())

        val script = doc.select("script").firstOrNull { it.data().contains("sources:") }?.data()
            ?: return emptyList()

        // Extract video sources
        val sources = Regex("""file:\s*["'](.*?)["']""").findAll(script).map { it.groupValues[1] }.toList()
        val videos = sources.mapIndexed { i, source ->
            val decoded = URLDecoder.decode(source, "UTF-8")
            Video(url, "PlyrXCDN - ${i + 1}", decoded, headers = mapOf("Referer" to referer))
        }

        // Extract subtitles (if any)
        Regex("""tracks:\s*(.*?)""", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)?.let { tracksBlock ->
            Regex("""file:\s*["'](.*?)["'],\s*label:\s*["'](.*?)["']""").findAll(tracksBlock).forEach {
                val subUrl = URLDecoder.decode(it.groupValues[1], "UTF-8")
                val label = it.groupValues[2]
                subtitleCallback(SubtitleFile(label, subUrl))
            }
        }

        return videos
    }
}