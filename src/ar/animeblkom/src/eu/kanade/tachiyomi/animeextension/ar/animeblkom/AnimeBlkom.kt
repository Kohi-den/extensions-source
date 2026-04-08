package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anime3rb : ParsedAnimeHttpSource() {

    override val name = "Anime3rb"

    override val baseUrl = "https://anime3rb.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
            .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
    }

    // Popular Anime
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/titles?page=$page")

    override fun popularAnimeSelector(): String = "div.grid div.relative.group"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.select("a").first()
        anime.setUrlWithoutDomain(link?.attr("href") ?: "")
        anime.title = element.select("h3").text().trim()
        anime.thumbnail_url = element.select("img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a[rel=next]"

    // Latest Anime
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodes?page=$page")

    override fun latestUpdatesSelector(): String = "div.grid div.relative.group"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val url = element.select("a").attr("href") ?: ""
        val animeUrl = if (url.contains("/titles/")) {
            url.substringBeforeLast("/")
        } else {
            url
        }
        anime.setUrlWithoutDomain(animeUrl)
        anime.title = element.select("h3").text().trim()
        anime.thumbnail_url = element.select("img").attr("src")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "a[rel=next]"

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/titles?query=$query&page=$page")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // Anime Details
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1").text().trim()
        anime.description = document.select("p.text-sm.leading-relaxed").text().trim()
        anime.genre = document.select("div.flex.flex-wrap.gap-2 a").joinToString { it.text() }
        val statusText = document.select("div:contains(الحالة)").text()
        anime.status = when {
            statusText.contains("مكتمل") -> SAnime.COMPLETED
            statusText.contains("قيد البث") -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        return anime
    }

    // Episodes
    override fun episodeListSelector(): String = "div#episodes-list a, div.grid.grid-cols-1.gap-4 a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.text().trim()
        val epNum = element.text().filter { it.isDigit() }.toFloatOrNull() ?: 1f
        episode.episode_number = epNum
        return episode
    }

    // Video Links
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        
        document.select("ul#servers-list li, div.servers-list button").forEach { server ->
            val name = server.text().trim()
            val url = server.attr("data-url").ifBlank { server.attr("value") }
            if (url.isNotEmpty()) {
                videoList.add(Video(url, name, url))
            }
        }
        
        if (videoList.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    videoList.add(Video(src, "Player", src))
                }
            }
        }
        
        return videoList
    }

    override fun videoListSelector(): String = throw Exception("Not used")
    override fun videoFromElement(element: Element): Video = throw Exception("Not used")
    override fun videoUrlParse(document: Document): String = throw Exception("Not used")

    override fun List<Video>.sort(): List<Video> {
        return this.sortedByDescending { it.quality }
    }
}
