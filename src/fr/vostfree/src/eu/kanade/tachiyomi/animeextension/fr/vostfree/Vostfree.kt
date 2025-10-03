package eu.kanade.tachiyomi.animeextension.fr.vostfree

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Vostfree : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Vostfree"

    override val baseUrl = "https://vostfree.ws"

    override val lang = "fr"

    override val supportsLatest = false

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#dle-content div.movie-poster"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/films-vf-vostfr/page/$page/")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        with(element.selectFirst("a")!!) {
            setUrlWithoutDomain(attr("href"))
            title = text()
        }
        thumbnail_url = element.selectFirst("span.image img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector() = "span.next-page"

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter

        val formData = FormBody.Builder()
            .addEncoded("do", "search")
            .addEncoded("subaction", "search")
            .addEncoded("search_start", "0")
            .addEncoded("full_search", "0")
            .addEncoded("result_from", "1")
            .addEncoded("story", query)
            .build()

        return when {
            query.isNotBlank() -> POST("$baseUrl/index.php?do=search", headers, formData)
            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}/page/$page/")
            typeFilter.state != 0 -> GET("$baseUrl/${typeFilter.toUriPart()}/page/$page/")
            else -> GET("$baseUrl/animes-vostfr/page/$page/")
        }
    }

    override fun searchAnimeSelector() = "div#dle-content div.search-result, " + popularAnimeSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).map(::searchAnimeFromElement)

        return AnimesPage(animes, false)
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.select("div.slide-middle h1").text()
        description = document.selectFirst("div.slide-desc")?.ownText()
        thumbnail_url = document.selectFirst("div.slide-poster img")?.absUrl("src")
        genre = document.select("li.right b.fa-bookmark-o ~ a").joinToString { it.text() }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val epUrl = response.request.url.toString()
        return doc.select("select.new_player_selector option").map { it ->
            if (it.text() == "Film") {
                SEpisode.create().apply {
                    episode_number = 1F
                    name = "Film"
                    setUrlWithoutDomain("$epUrl?episode=1")
                }
            } else {
                SEpisode.create().apply {
                    val epNum = it.text().substringAfter(" ").toInt()
                    episode_number = epNum.toFloat()
                    name = "Épisode $epNum"
                    setUrlWithoutDomain("$epUrl?episode=$epNum")
                }
            }
        }.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }

    override fun videoListParse(response: Response) = throw UnsupportedOperationException()

    private val hostPrefixes = mapOf(
        "ok" to "https://ok.ru/videoembed/",
        "sibnet" to "https://video.sibnet.ru/shell.php?videoid=",
        "uqload" to "https://uqload.io/embed-",
    )

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epNum = episode.url.substringAfter("=")
        val document = client.newCall(GET(baseUrl + episode.url, headers)).await().asJsoup()

        val allPlayers = document.select("div#buttons_$epNum div")

        return allPlayers.parallelCatchingFlatMap {
            val server = it.text().lowercase()
            val id = it.attr("id")
            val content = document.selectFirst("div#content_$id")?.text() ?: return@parallelCatchingFlatMap emptyList()
            when (server) {
                "doodstream" -> doodExtractor.videosFromUrl(content, "DoodStream")
                "mixdrop" -> mixdropExtractor.videosFromUrl(content)
                "ok" -> okruExtractor.videosFromUrl(hostPrefixes[server] + content)
                "sibnet" -> sibnetExtractor.videosFromUrl(hostPrefixes[server] + content)
                "uqload" -> uqloadExtractor.videosFromUrl(hostPrefixes[server] + content + ".html")
                "voe" -> voeExtractor.videosFromUrl(content)
                "vudeo" -> vudeoExtractor.videosFromUrl(content)
                else -> emptyList()
            }
        }.sort()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "types",
        arrayOf(
            Pair("<pour sélectionner>", ""),
            Pair("Animes VF", "animes-vf"),
            Pair("Animes VOSTFR", "animes-vostfr"),
            Pair("FILMS", "films-vf-vostfr"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "genre",
        arrayOf(
            Pair("<pour sélectionner>", ""),
            Pair("Action", "Action"),
            Pair("Comédie", "Comédie"),
            Pair("Drame", "Drame"),
            Pair("Surnaturel", "Surnaturel"),
            Pair("Shonen", "Shonen"),
            Pair("Romance", "Romance"),
            Pair("Tranche de vie", "Tranche+de+vie"),
            Pair("Fantasy", "Fantasy"),
            Pair("Mystère", "Mystère"),
            Pair("Psychologique", "Psychologique"),
            Pair("Sci-Fi", "Sci-Fi"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "Vudeo"
        private val PREF_QUALITY_ENTRIES = arrayOf(
            "1080p",
            "360p",
            "480p",
            "720p",
            "Doodstream",
            "MixDrop",
            "Sibnet",
            "Uqload",
            "Voe",
            "Vudeo",
        )
    }
}
