package eu.kanade.tachiyomi.animeextension.es.homecine

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HomeCine : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "HomeCine"

    override val baseUrl = "https://www3.homecine.tv"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Fastream"
        private val SERVER_LIST = arrayOf("Fastream")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".mvic-desc h1")?.text()?.trim() ?: ""
            status = if (document.location().contains("/series/")) SAnime.UNKNOWN else SAnime.COMPLETED
            description = document.selectFirst(".mvic-desc .f-desc")?.ownText()
            genre = document.select(".mvic-info [rel='category tag']").joinToString { it.text() }
            thumbnail_url = document.selectFirst("[itemprop=image]")?.attr("abs:src")?.replace("/w185/", "/w500/")
            document.select(".mvici-left p").map { it.text() }.map { textContent ->
                when {
                    "Director" in textContent -> author = textContent.substringAfter("Director:").trim().split(", ").firstOrNull()
                    "Actors" in textContent -> artist = textContent.substringAfter("Actors:").trim().split(", ").firstOrNull()
                }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/peliculas/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("[data-movie-id] > a")
        val nextPage = document.select(".pagination li a:contains(Last)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".mli-info")?.text()?.trim() ?: ""
                thumbnail_url = element.selectFirst("img")!!.attr("abs:data-original")
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.location().contains("/series/")) {
            var episodeCounter = 1F
            document.select(".tvseason").flatMap { season ->
                val noSeason = season.select(".les-title strong").text().substringAfter("Temporada").trim()
                season.select(".les-content a").map { ep ->
                    SEpisode.create().apply {
                        episode_number = episodeCounter++
                        name = "T$noSeason - ${ep.text().trim()}"
                        setUrlWithoutDomain(ep.attr("abs:href"))
                    }
                }
            }.reversed()
        } else {
            listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "PELÍCULA"
                    setUrlWithoutDomain(document.location())
                },
            )
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        return document.select(".movieplay iframe").parallelCatchingFlatMapBlocking {
            val link = it.attr("abs:src")
            val prefix = runCatching {
                val tabElement = it.closest("[id*=tab]") ?: return@runCatching ""
                val tabName = tabElement.attr("id")
                val lang = document.selectFirst("[href=\"#$tabName\"]")?.ownText()?.trim() ?: ""
                when {
                    lang.lowercase().contains("latino") -> "[LAT]"
                    lang.lowercase().contains("castellano") -> "[CAST]"
                    lang.lowercase().contains("subtitulado") -> "[SUB]"
                    else -> ""
                }
            }.getOrDefault("")

            serverVideoResolver(link, prefix)
        }
    }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("fastream") -> {
                val link = if (url.contains("emb.html")) "https://fastream.to/embed-${url.split("/").last()}.html" else url
                FastreamExtractor(client, headers).videosFromUrl(link, prefix = "$prefix Fastream:")
            }
            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Películas", "peliculas"),
            Pair("Series", "series"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
