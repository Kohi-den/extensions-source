package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response

class AnimeKai : AnimeHttpSource() {
    override val name = "AnimeKai"

    override val baseUrl = "https://animekai.to"

    override val lang = "en"

    override val supportsLatest = true

    // ============================== Popular ===============================
    // This source doesnt have a popular animes page,
    // so we use search page with sort by trending.
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?keyword=&sort=trending")
    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // --------- Latest Anime ---------

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page")
    override fun latestUpdatesParse(response: Response): AnimesPage {
        // Use the unified parsing function for both latest and search
        return parseAnimesPage(response)
    }

    // --------- Search Anime ---------

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = mutableListOf<String>()
        filters.forEach { filter ->
            if (filter is AnimeKaiFilters.GenreGroup) {
                filter.state.forEach { genre ->
                    when (genre.state) {
                        AnimeFilter.TriState.STATE_INCLUDE -> searchParams.add("genre[]=${genre.id}")
                        AnimeFilter.TriState.STATE_EXCLUDE -> searchParams.add("genre[]=-${genre.id}")
                        else -> {
                            // Ignore STATE_IGNORE
                        }
                    }
                }
                return@forEach
            }
            if (filter is AnimeKaiFilters.SortSelector) {
                val sortOrdinal = filter.state
                val sortOption = AnimeKaiFilters.Companion.SortOption.values()[sortOrdinal]
                searchParams.add("sort=${sortOption.id}")
                return@forEach
            }
        }
        val genreQuery = if (searchParams.isNotEmpty()) "&" + searchParams.joinToString("&") else ""
        return GET("$baseUrl/browser?keyword=$query&page=$page$genreQuery")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnimesPage(response)
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeKaiFilters.getFilterList()
    }

    // --------- Anime Details ---------

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            // Title
            title = document.selectFirst("h1.title")?.text() ?: ""
            // Poster/Thumbnail
            thumbnail_url = document.selectFirst(".poster img")?.attr("src")
            // Description (main)
            description = document.selectFirst("div.desc.text-expand")?.text()
            // Genres
            genre = document.select("div.detail div:contains(Genres) span a").map { it.text() }.joinToString(", ")
            // Status
            status = when (document.selectFirst("div.detail div:contains(Status) span")?.text()?.trim()?.lowercase()) {
                "releasing" -> SAnime.ONGOING
                "finished" -> SAnime.COMPLETED
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            // Studios as author
            author = document.select("div.detail div:contains(Studios) span a span").map { it.text() }.joinToString(", ")
            // Producers as artist
            artist = document.select("div.detail div:contains(Producers) span a span").map { it.text() }.joinToString(", ")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        Log.v("AnimeKai", document.select(".episode-section").outerHtml())
        return document.select(".eplist ul.range li a").map { ep ->
            SEpisode.create().apply {
                // URL
                setUrlWithoutDomain(ep.attr("href"))
                // Episode number
                episode_number = ep.attr("num").toFloatOrNull() ?: ep.attr("data-mal-sync-episode").toFloatOrNull() ?: 0f
                // Name (title)
                name = ep.selectFirst("span[data-jp]")?.text()?.ifBlank { "Episode ${episode_number.toInt()}" } ?: "Episode ${episode_number.toInt()}"
            }
        }
    }

    // Resused Function
    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeElements = document.select("div.aitem")

        val animes = animeElements.map { element ->
            SAnime.create().apply {
                val url = element.selectFirst("a")!!.attr("href")
                setUrlWithoutDomain(url)

                val titleElement = element.selectFirst("a.title")
                this.title = titleElement?.attr("title") ?: ""

                val thumbnailUrl = element.select(".poster img").attr("data-src")
                this.thumbnail_url = thumbnailUrl

                // Log.v("AnimeKai", "Parsed Anime: $title, URL: $url, Thumbnail: $thumbnail_url")
            }
        }

        return AnimesPage(
            animes,
            hasNextPage = document.selectFirst("ul.pagination a[rel=next]") != null,
        )
    }
}
