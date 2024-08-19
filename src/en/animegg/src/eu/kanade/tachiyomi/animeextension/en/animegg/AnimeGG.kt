package eu.kanade.tachiyomi.animeextension.en.animegg

import android.annotation.SuppressLint
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class AnimeGG : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeGG"

    override val baseUrl = "https://www.animegg.org"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[SUBBED]"
        private val LANGUAGE_LIST = arrayOf("[SUBBED]", "[DUBBED]", "[RAW]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "AnimeGG"
        private val SERVER_LIST = arrayOf("AnimeGG")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".media-body h1")?.text()?.trim() ?: ""
            status = if (document.location().contains("/series/")) SAnime.UNKNOWN else SAnime.COMPLETED
            description = document.selectFirst(".ptext")?.text()
            genre = document.select(".tagscat a").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".media .media-object")?.attr("abs:src")
            document.select(".infoami span").map { it.text() }.map { textContent ->
                when {
                    "Status" in textContent -> status = parseStatus(textContent)
                }
            }
        }
        return animeDetails
    }

    private fun parseStatus(span: String): Int {
        val status = span.substringAfter("Status:").trim()
        return when {
            "Completed" in status -> SAnime.COMPLETED
            "Ongoing" in status -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/popular-series?sortBy=hits&sortDirection=DESC&ongoing&limit=50&start=0", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".fea")
        val animeList = elements.map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".rightpop a")?.text()?.trim() ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                setUrlWithoutDomain(element.select(".rightpop a").attr("abs:href"))
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/popular-series?sortBy=createdAt&sortDirection=DESC&ongoing&limit=50&start=0", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search/?q=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".mse")
        val animeList = elements.map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".first h2")?.text()?.trim() ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(".newmanga li div").mapIndexed { idx, episode ->
            val episodeNumber = episode.selectFirst(".anm_det_pop strong")?.getEpNumber() ?: (idx + 1F)
            val title = episode.select(".anititle").text()
            SEpisode.create().apply {
                episode_number = episodeNumber
                name = when {
                    episodeNumber.formatEp() in title -> episode.select(".anititle").text()
                    else -> "Episode ${episodeNumber.formatEp()} - ${episode.select(".anititle").text()}"
                }
                scanlator = episode.select(".btn-xs").joinToString { it.text() }
                setUrlWithoutDomain(episode.select(".anm_det_pop").attr("abs:href"))
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("iframe").parallelCatchingFlatMapBlocking {
            val mode = when (it.closest(".tab-pane")?.attr("id")) {
                "subbed-Animegg" -> "[SUBBED]"
                "dubbed-Animegg" -> "[DUBBED]"
                "raw-Animegg" -> "[RAW]"
                else -> ""
            }

            val link = it.attr("abs:src")
            val embedPlayer = client.newCall(GET(link)).execute().asJsoup()
            val scriptData = embedPlayer.selectFirst("script:containsData(var videoSources =)")?.data() ?: return@parallelCatchingFlatMapBlocking emptyList()
            val host = link.toHttpUrl().host
            val videoHeaders = headers.newBuilder().add("referer", "https://$host").build()
            val jsonString = fixJsonString(scriptData.substringAfter("var videoSources = ").substringBefore(";"))
            json.decodeFromString<Array<GgVideo>>(jsonString).map { video ->
                val videoUrl = "https://$host${video.file}"
                Video(videoUrl, "$mode AnimeGG:${video.label}", videoUrl, headers = videoHeaders)
            }
        }
    }

    private fun fixJsonString(jsonString: String): String {
        return jsonString.replace(Regex("""(\w+):"""), """"$1":""")
            .replace(Regex("""(:\s)([^{\[}\]":\s,]+)"""), """$1"$2"""")
            .replace(Regex("""(:\s)("[^"]*")"""), """$1$2""")
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
        "Genre",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Películas", "peliculas"),
            Pair("Series", "series"),
        ),
    )

    private fun Element?.getEpNumber(): Float? {
        val input = this?.text() ?: return null
        val regex = Regex("""(\d+(\.\d+)?)(?:-\d+(\.\d+)?)?$""")
        val matchResult = regex.find(input)
        return matchResult?.groupValues?.get(1)?.toFloatOrNull()
    }

    @SuppressLint("DefaultLocale")
    private fun Float.formatEp(): String {
        return if (this % 1 == 0.0f) {
            String.format(Locale.US, "%.0f", this)
        } else {
            String.format(Locale.US, "%.1f", this)
        }
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    @Serializable
    data class GgVideo(
        val file: String,
        val label: String,
    )

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
