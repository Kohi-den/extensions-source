package eu.kanade.tachiyomi.animeextension.en.animekai

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeKai : AnimeHttpSource(), ConfigurableAnimeSource {
    override val name = "AnimeKai"

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }

    override val lang = "en"
    override val supportsLatest = true

    // ============================== Popular ===============================
    // This source does not have a popular anime page,
    // so we use search page with sort by trending.
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?keyword=&sort=trending&page=$page")
    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page")
    override fun latestUpdatesParse(response: Response): AnimesPage {
        // Use the unified parsing function for both latest and search
        return parseAnimesPage(response)
    }

    // ============================== Search Anime ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = mutableListOf<String>()
        filters.forEach { filter ->
            if (filter is AnimeKaiFilters.TypeGroup) {
                filter.state.forEach { type ->
                    when (type.state) {
                        true -> searchParams.add("type[]=${type.id}")
                        else -> {
                            // Ignore false state
                        }
                    }
                }
                return@forEach
            }
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
            if (filter is AnimeKaiFilters.StatusGroup) {
                filter.state.forEach { status ->
                    when (status.state) {
                        true -> searchParams.add("status[]=${status.id}")
                        else -> {
                            // Ignore false state
                        }
                    }
                }
                return@forEach
            }
            if (filter is AnimeKaiFilters.SortSelector) {
                val sortOrdinal = filter.state
                val sortOption = AnimeKaiFilters.SortOption.values()[sortOrdinal]
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

    // ============================== Anime Details ===============================

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

    // ============================== Episodes ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        Log.v("AnimeKai", document.select(".episode-section").outerHtml())
        return document.select(".eplist ul.range li a").map { ep ->
            SEpisode.create().apply {
                setUrlWithoutDomain(ep.attr("href"))
                episode_number = ep.attr("num").toFloatOrNull() ?: ep.attr("data-mal-sync-episode").toFloatOrNull() ?: 0f
                name = ep.selectFirst("span[data-jp]")?.text()?.ifBlank { "Episode ${episode_number.toInt()}" } ?: "Episode ${episode_number.toInt()}"
            }
        }
    }

    // ============================== Extension Functions ===============================

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

    // ============================== Preferences ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY // String, like "pref_quality"
            title = PREF_DOMAIN_TITLE // String, like "Preferred quality:"
            entries = PREF_DOMAIN_ENTRIES // Array<String>, like arrayOf("240p", "720p"...)
            // Another Array<String>. Can be different from the property above, as long it have the same size
            // and equivalent values per index.
            entryValues = PREF_DOMAIN_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, "App Restart Required", Toast.LENGTH_SHORT).show()
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        val PREF_DOMAIN_KEY = "preffered_domain"
        val PREF_DOMAIN_TITLE = "Preferred Domain (requires app restart)"
        val PREF_DOMAIN_DEFAULT = "https://animekai.bz"
        val PREF_DOMAIN_ENTRIES = arrayOf("animekai.to", "animekai.bz", "animekai.cc", "animekai.ac")
        val PREF_DOMAIN_VALUES = arrayOf("https://animekai.to", "https://animekai.bz", "https://animekai.cc", "https://animekai.ac")
    }
}
