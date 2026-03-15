package eu.kanade.tachiyomi.animeextension.de.aniworldsplit

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.aniworldtheme.AniWorldTheme
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

class AniWorldSplit : AniWorldTheme("AniWorld (Split Seasons)") {

    override val id: Long = 8286900189409315837

    private fun popularOrLatestAnime(element: Element): SAnime {
        val link = element.selectFirst("a")!!.attr("href")
        val page = client.newCall(GET("$baseUrl$link")).execute().asJsoup()
        val seasonElements = page
            .select("#stream > ul:nth-child(1) > li > a")
            .filter { season ->
                !season.attr("href").contains("/filme")
            }
        val anime = SAnime.create()

        val season = when (preferences.getString("season_sort", "last")) {
            "first" -> seasonElements.firstOrNull()
            "last" -> seasonElements.lastOrNull()
            else -> seasonElements.lastOrNull()
        }

        anime.title = element.selectFirst("h3")!!.text() + " - " + season!!.attr("title")
        anime.url = season.attr("href")
        anime.thumbnail_url = baseUrl + element
            .selectFirst("a")!!
            .selectFirst("img")!!
            .attr("data-src")
            .replace("200x300", "220x330")
        return anime
    }

    // ===== POPULAR ANIME =====
    override fun popularAnimeFromElement(element: Element): SAnime = popularOrLatestAnime(element)

    // ===== LATEST ANIME =====
    override fun latestUpdatesFromElement(element: Element): SAnime = popularOrLatestAnime(element)

    // ===== SEARCH =====
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val seasonRegex = Regex("(?i)(?:\\s*-\\s*)?(?:staffel|season)\\s*(\\d+)$")
        val match = seasonRegex.find(query)

        val cleanQuery = if (match != null) {
            query.replace(match.value, "").trim()
        } else {
            query
        }

        return super.searchAnimeRequest(page, cleanQuery, filters)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val results = json.decodeFromString<JsonArray>(body)
        val animes = results
            .mapNotNull { animeFromSearch(it.jsonObject) }
            .flatten()
        return AnimesPage(animes, false)
    }

    fun animeFromSearch(obj: JsonObject): List<SAnime>? {
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val link = obj["link"]?.jsonPrimitive?.content ?: return null
        val thumbnailUrl = obj["cover"]
            ?.jsonPrimitive
            ?.content
            ?.replace("150x225", "220x330")
            ?.let { cover -> "$baseUrl$cover" }
        val description = obj["description"]?.jsonPrimitive?.content

        val page = client.newCall(GET("$baseUrl/anime/stream/$link")).execute().asJsoup()
        val animes = mutableListOf<SAnime>()
        val seasonElements = page.select("#stream > ul:nth-child(1) > li > a")

        seasonElements.forEach { element ->
            val seasonUrl = element.attr("abs:href")

            if (seasonUrl.contains("/filme")) {
                val moviesHtml = client.newCall(GET(seasonUrl)).execute().asJsoup()
                val movieElements = moviesHtml.select("table.seasonEpisodesList tbody tr")
                movieElements.forEach { movieElement ->
                    val movieAnime =
                        SAnime.create().apply {
                            val movieTitle = movieElement.select("td.seasonEpisodeTitle a span").text()
                            title = "$name - $movieTitle"
                            url = movieElement.selectFirst("td.seasonEpisodeTitle a")!!.attr("href").substringAfter(baseUrl)
                            thumbnail_url = thumbnailUrl
                            this.description = description
                            status = SAnime.COMPLETED
                        }
                    animes.add(movieAnime)
                }
            } else {
                val seasonAnime =
                    SAnime.create().apply {
                        val seasonNum = element.attr("href").substringAfter("staffel-").substringBeforeLast("/")
                        title = "$name - Staffel $seasonNum"
                        url = seasonUrl.substringAfter(baseUrl)
                        thumbnail_url = thumbnailUrl
                        this.description = description
                        status = SAnime.UNKNOWN
                    }
                animes.add(seasonAnime)
            }
        }
        return animes
    }

    // ===== ANIME DETAILS =====
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = super.animeDetailsParse(document)
        val url = document.location()
        if (url.contains("/filme")) {
            val movieTitle = document.select(".hosterSiteTitle h2 small").text()
            anime.title += " - $movieTitle"
        } else {
            val seasonNum = url.substringAfter("staffel-").substringBeforeLast("/")
            anime.title += " - Staffel $seasonNum"
        }
        return anime
    }

    // ===== EPISODE =====
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val url = response.request.url.toString()

        if (url.contains("/filme")) {
            val episode = SEpisode.create().apply {
                name = document.select(".hosterSiteTitle h2 small").text()
                episode_number = 1f
                this.url = url
            }
            episodeList.add(episode)
        } else {
            val episodeElements = document.select("table.seasonEpisodesList tbody tr")
            episodeElements.forEach { element ->
                val episode = SEpisode.create().apply {
                    val num = element.attr("data-episode-season-id")
                    name = "$num. " + element.select("td.seasonEpisodeTitle a span").text()
                    episode_number = element.select("td meta").attr("content").toFloat()
                    this.url = element.selectFirst("td.seasonEpisodeTitle a")!!.attr("href")
                }
                episodeList.add(episode)
            }
        }
        return episodeList.reversed()
    }

    // ===== PREFERENCES ======
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        val seasonSortPref = androidx.preference.ListPreference(screen.context).apply {
            key = "season_sort"
            title = "Staffel-Anzeige (Beliebt/Neu)"
            entries = arrayOf("Erste Staffel", "Letzte Staffel")
            entryValues = arrayOf("first", "last")
            setDefaultValue("last")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }
        screen.addPreference(seasonSortPref)
    }
}
