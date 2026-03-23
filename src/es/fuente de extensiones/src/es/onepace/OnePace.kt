package eu.kanade.tachiyomi.animeextension.es.onepace

import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.*
import okhttp3.Response

class OnePace : ParsedAnimeHttpSource() {

    override val name = "One Pace"
    override val baseUrl = "https://onepace.net"
    override val lang = "es"
    override val supportsLatest = false

    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/es/watch")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animeList = document.select("a[href*='/es/watch/']").map {
            SAnime.create().apply {
                title = it.text()
                url = it.attr("href")
            }
        }

        return AnimesPage(animeList.distinctBy { it.url }, false)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        document.select("a[href*='pixeldrain']").forEach {
            episodes.add(SEpisode.create().apply {
                name = it.text()
                url = it.attr("href")
            })
        }

        return episodes
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        return listOf(Video(url, "Pixeldrain", url))
    }

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()
}
