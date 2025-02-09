package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.CloseloadExtractor
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.RapidrameExtractor
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.VidmolyExtractor
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.XBetExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class HDFilmCehennemi : ParsedAnimeHttpSource(), ConfigurableAnimeSource {
    override val name = "HDFilmCehennemi"

    override val baseUrl = "https://www.hdfilmcehennemi.nl"

    override val lang = "tr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val apiHeaders = headersBuilder().add("X-Requested-With", "fetch").build()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/load/page/$page/mostLiked/", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<ApiResponse>()
        val doc = Jsoup.parse(data.html)
        val itens = doc.select(popularAnimeSelector()).map(::popularAnimeFromElement)

        return AnimesPage(itens, itens.size >= 28)
    }

    override fun popularAnimeSelector() = "a.poster"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("strong.poster-title, h4.title")!!.text()
        thumbnail_url = element.selectFirst("img")?.run {
            absUrl("data-src").ifBlank { absUrl("src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li > a[rel=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/load/page/$page/home/", apiHeaders)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun getFilterList() = HDFilmCehennemiFilters.FILTER_LIST

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
        val id = query.removePrefix(PREFIX_SEARCH)
        client.newCall(GET("$baseUrl/$id"))
            .awaitSuccess()
            .use(::searchAnimeByIdParse)
    } else {
        super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        when {
            query.isNotBlank() -> {
                val body = FormBody.Builder().add("query", query).build()

                POST("$baseUrl/search", apiHeaders, body)
            }

            else -> {
                val params = HDFilmCehennemiFilters.getSearchParameters(filters)

                val form = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("kesfet[type]", params.type)
                    .addFormDataPart("kesfet[genres]", params.genres)
                    .addFormDataPart("kesfet[years]", params.years)
                    .addFormDataPart("kesfet[imdb]", params.imdbScore)
                    .addFormDataPart("kesfet[orderBy]", params.order)
                    .addFormDataPart("page", page.toString())
                    .build()

                POST("$baseUrl/movies/load/", headers, form)
            }
        }

    @Serializable
    data class SearchResponse(
        val results: List<String>,
    )

    @Serializable
    data class FilterSearchResponse(
        val html: String,
        val showMore: Boolean,
        val status: Int,
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        return when {
            response.request.url.toString().contains("/search") -> { // Text search
                val data = response.parseAs<SearchResponse>()
                val items = data.results.mapNotNull {
                    Jsoup.parse(it).selectFirst("a[href]")
                }.map(::popularAnimeFromElement)

                AnimesPage(items, false)
            }

            else -> { // Filter search
                val data = response.parseAs<FilterSearchResponse>()
                if (data.status != 1) return AnimesPage(emptyList(), false)

                val doc = response.asJsoup(data.html)
                val items = doc.select(searchAnimeSelector()).map(::searchAnimeFromElement)

                AnimesPage(items, data.showMore)
            }
        }
    }

    override fun searchAnimeSelector() = "div.poster > a"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        status = when {
            document.location().contains("/dizi/") -> SAnime.UNKNOWN // serie
            else -> SAnime.COMPLETED // movie
        }

        title = document.selectFirst(".section-title")!!
            .ownText()
            .substringBefore(" Filminin Bilgileri")
            .substringBefore(" izle")
        val div = document.selectFirst("div.section-content div.post-info")!!
        thumbnail_url = div.selectFirst("img")!!.absUrl("data-src")

        genre = div.select("div.post-info-genres > a")
            .eachText()
            .joinToString()
            .takeIf(String::isNotEmpty)
        artist = div.select("div.post-info-cast > a")
            .eachText()
            .joinToString()
            .takeIf(String::isNotEmpty)

        description = div.selectFirst("div.post-info-content > p")?.text()
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        // Series
        if (anime.url.contains("/dizi/")) return super.getEpisodeList(anime)

        // Movies
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Movie"
                episode_number = 1F
            },
        )
    }

    override fun episodeListParse(response: Response) =
        super.episodeListParse(response).sortedByDescending { it.episode_number }

    override fun episodeListSelector() = "div.seasons-tabs-wrapper > div.seasons-tab-content > a"

    private val numberRegex by lazy { Regex("(\\d+)\\.") }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))

        name = element.selectFirst("h3, h4")!!.text()

        date_upload = element.selectFirst("date")?.attr("datetime")?.toDate() ?: 0L

        val (seasonNum, epNum) = numberRegex.findAll(name).map { it.groupValues.last() }.toList()
        // good luck trying to track this xD
        episode_number = "$seasonNum.${epNum.padStart(3, '0')}".toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    private val vidmolyExtractor by lazy { VidmolyExtractor(client, headers) }
    private val closeloadExtractor by lazy { CloseloadExtractor(client, headers) }
    private val rapidrameExtractor by lazy { RapidrameExtractor(client, headers) }
    private val xbetExtractor by lazy { XBetExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        val langTabs = doc.select(videoListSelector())

        return langTabs
            .flatMap {
                it.select("button.alternative-link[data-video]")
                    .map { btn -> Triple(it.attr("data-lang"), btn.text(), btn.attr("data-video")) }
            }.parallelCatchingFlatMapBlocking(::extractVideos)
    }

    private suspend fun extractVideos(info: Triple<String, String, String>): List<Video> {
        val name = "[${info.first}] ${info.second}"
        val url = client.newCall(GET("$baseUrl/video/${info.third}/", apiHeaders)).await()
            .body.string()
            .substringAfter("src=")
            .substringBefore(' ')
            .trim('\\', '"', '\'', ' ')
            .replace("\\/", "/")
        println(url)
        return when {
            url.contains("/rplayer") -> rapidrameExtractor.videosFromUrl(url, name)
            name.contains("close") || url.contains("rapidrame") -> closeloadExtractor.videosFromUrl(
                url,
                name,
            )
            name.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(url, name)
            url.contains("trstx.org") -> xbetExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    override fun videoListSelector() = "div.alternative-tab div.alternative-links[data-lang]"

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }
        .getOrNull() ?: 0L

    @Serializable
    private class ApiResponse(
        val html: String,
    )

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "480p", "720p", "1080p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
