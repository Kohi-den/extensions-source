package eu.kanade.tachiyomi.animeextension.fr.nekosama

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
import eu.kanade.tachiyomi.lib.fusevideoextractor.FusevideoExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NekoSama : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Neko-Sama"

    override val baseUrl by lazy { "https://" + preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "fr"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.anime"

    override fun popularAnimeRequest(page: Int): Request {
        return if (page > 1) {
            GET("$baseUrl/anime/$page")
        } else {
            GET("$baseUrl/anime/")
        }
    }

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        with(element.selectFirst("div.info a")!!) {
            setUrlWithoutDomain(attr("href"))
            title = text()
        }

        element.selectFirst("div.cover a div img:not(.placeholder)")?.run {
            thumbnail_url = attr("data-src").ifBlank { attr("src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.nekosama.pagination a.active ~ a"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val typeSearch = when (typeFilter.toUriPart()) {
            "anime" -> "vostfr"
            "anime-vf" -> "vf"
            else -> "vostfr"
        }

        return when {
            query.isNotBlank() -> GET("$baseUrl/animes-search-$typeSearch.json?$query")
            typeFilter.state != 0 || query.isNotBlank() -> when (page) {
                1 -> GET("$baseUrl/${typeFilter.toUriPart()}")
                else -> GET("$baseUrl/${typeFilter.toUriPart()}/$page")
            }
            else -> when (page) {
                1 -> GET("$baseUrl/anime/")
                else -> GET("$baseUrl/anime/page/$page")
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val pageUrl = response.request.url.toString()
        val query = pageUrl.substringAfter("?").lowercase().replace("%20", " ")

        return when {
            pageUrl.contains("animes-search") -> {
                val jsonSearch = response.parseAs<List<SearchJson>>()
                val animes = jsonSearch
                    .filter { it.title.orEmpty().lowercase().contains(query) }
                    .mapNotNull {
                        SAnime.create().apply {
                            url = it.url ?: return@mapNotNull null
                            title = it.title ?: return@mapNotNull null
                            thumbnail_url = it.url_image ?: "$baseUrl/images/default_poster.png"
                        }
                    }
                AnimesPage(animes, false)
            }
            else -> {
                val page = response.asJsoup()
                val animes = page.select(popularAnimeSelector()).map(::popularAnimeFromElement)
                AnimesPage(animes, true)
            }
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val jsonLatest = response.parseAs<List<SearchJson>> { it.substringAfter("var lastEpisodes = ").substringBefore(";\n") }

        val animeList = jsonLatest.mapNotNull { item ->
            SAnime.create().apply {
                val itemUrl = item.url ?: return@mapNotNull null
                title = item.title ?: return@mapNotNull null
                val type = itemUrl.substringAfterLast("-")
                url = itemUrl.replace("episode", "info").substringBeforeLast("-").substringBeforeLast("-") + "-$type"
                thumbnail_url = item.url_image ?: "$baseUrl/images/default_poster.png"
            }
        }

        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("div.row > div.col > h1")!!.ownText()
        genre = document.select("div.col > div.list span").joinToString { it.text() }

        with(document.selectFirst("div.row > div#details")!!) {
            thumbnail_url = selectFirst("div.cover img")?.absUrl("src") ?: "$baseUrl/images/default_poster.png"
            description = buildString {
                document.selectFirst("div.synopsis p")?.also { append(it.text(), "\n\n") }
                getInfo("Score")?.also { append("Score moyen: *", it, "\n") }
                getInfo("Status")?.also {
                    append("Status: ", it, "\n")
                    status = parseStatus(it)
                }
                getInfo("Format")?.also { append("Format: ", it, "\n") }
                getInfo("Diffusion")?.also { append("Diffusion: ", it, "\n") }
            }
        }
    }

    private fun Element.getInfo(item: String) =
        selectFirst("div#anime-info-list div.item:contains($item)")?.ownText()?.trim()

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "En cours" -> SAnime.ONGOING
            "Terminé" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.episodes div > a.button"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val text = element.text()
        name = text.substringBeforeLast(" - ")
        episode_number = text.substringAfterLast("- ").toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    private val fusevideoExtractor by lazy { FusevideoExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(var video = [];)")!!.data()
        return PLAYERS_REGEX.findAll(script).flatMap {
            val url = it.groupValues[1]
            with(url) {
                when {
                    contains("fusevideo") -> fusevideoExtractor.videosFromUrl(this)
                    contains("streamtape") -> streamTapeExtractor.videosFromUrl(this)
                    else -> emptyList()
                }
            }
        }.toList()
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
        AnimeFilter.Header("Utilisez ce filtre pour affiner votre recherche"),
        TypeFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "VOSTFR or VF",
        arrayOf(
            Pair("<sélectionner>", "none"),
            Pair("VOSTFR", "anime"),
            Pair("VF", "anime-vf"),
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
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_ENTRIES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    @Serializable
    data class EpisodesJson(
        var time: String? = null,
        var episode: String? = null,
        var title: String? = null,
        var url: String? = null,
        var url_image: String? = null,

    )

    @Serializable
    data class SearchJson(
        var id: Int? = null,
        var title: String? = null,
        var titleEnglish: String? = null,
        var titleRomanji: String? = null,
        var titleFrench: String? = null,
        var others: String? = null,
        var type: String? = null,
        var status: String? = null,
        var popularity: Double? = null,
        var url: String? = null,
        var genres: ArrayList<String> = arrayListOf(),
        var url_image: String? = null,
        var score: String? = null,
        var startDateYear: String? = null,
        var nbEps: String? = null,

    )

    companion object {
        private val PLAYERS_REGEX = Regex("video\\s*\\[\\d*]\\s*=\\s*'(.*?)'")
        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private const val PREF_DOMAIN_TITLE = "Preferred domain"
        private const val PREF_DOMAIN_DEFAULT = "animecat.net"
        private val PREF_DOMAIN_ENTRIES = arrayOf("animecat.net", "neko-sama.fr")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
