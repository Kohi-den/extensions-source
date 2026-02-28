package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.ArrayList

class Anime3rb : ParsedAnimeHttpSource() {

    override val name = "أنمي عرب"

    override val baseUrl = "https://anime3rb.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // Popular Anime
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/titles/list/tv?page=$page&sort=newest", headers)

    override fun popularAnimeSelector(): String = "div.titles-list div.card, div.anime-card"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        element.select("a").first()?.let {
            title = it.text().trim()
            setUrlWithoutDomain(it.attr("href"))
        }
        thumbnail_url = element.select("img").attr("abs:src")
    }

    override fun popularAnimeNextPageSelector(): String = "a[rel=next]"

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?query=$query", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = null

    // Details
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.select("h1").text().trim()
        description = document.select("div.description, p.story").text().trim()
        genre = document.select("div.genres a").joinToString { it.text() }
        status = SAnime.UNKNOWN
    }

    // Episodes
    override fun episodeListSelector(): String = "div.episodes-list a, a[href*=/episode/]"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        name = element.text().trim()
        setUrlWithoutDomain(element.attr("href"))
        val numberMatch = Regex("""/(\d+)$""").find(url)
        episode_number = numberMatch?.groupValues?.get(1)?.toFloat() ?: 1f
    }

    // Video URL Extraction
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        
        // 1. Extract from scripts (HLS/m3u8)
        document.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("m3u8")) {
                val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                m3u8Regex.findAll(content).forEach { match ->
                    videoList.add(Video(match.value, "HLS Stream", match.value))
                }
            }
        }
        
        // 2. Extract from data-url attributes
        document.select("button[data-url], a[data-url]").forEach { element ->
            val url = element.attr("data-url")
            if (url.isNotEmpty() && !url.startsWith("#")) {
                videoList.add(Video(url, "Server: " + element.text(), url))
            }
        }

        // 3. Fallback to iframe if available
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotEmpty() && !src.contains("google")) {
                videoList.add(Video(src, "Iframe Player", src))
            }
        }

        return videoList
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}
