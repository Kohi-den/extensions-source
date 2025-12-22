package eu.kanade.tachiyomi.animeextension.pt.goyabu

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

private val DATE_FORMATTER by lazy {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)
}

private fun String.toDate(): Long {
    return runCatching { DATE_FORMATTER.parse(trim())?.time }
        .getOrNull() ?: 0L
}

class Goyabu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Goyabu"

    override val baseUrl = "https://goyabu.io"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl?s=", headers)

    override fun popularAnimeSelector() = "article.boxAN a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.title")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/lancamentos"
        } else {
            "$baseUrl/lancamentos/page/$page"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = "article.boxEP a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.title")!!.text()
        thumbnail_url = element.selectFirst("figure")?.attr("data-thumb")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(latestUpdatesSelector())
            .map(::latestUpdatesFromElement)

        val pagination = document.selectFirst("div.pagination")
        val hasNextPage = pagination?.let {
            val currentPage = it.attr("data-current-page").toIntOrNull() ?: 1
            val totalPages = it.attr("data-total-pages").toIntOrNull() ?: 1
            currentPage < totalPages
        } ?: false

        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()

        return GET(url, headers = headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = "div.pagination a:contains(›)"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = doc.selectFirst("div.streamer-info h1")!!.text()
            thumbnail_url = doc.selectFirst("div.streamer-poster img")?.attr("src")
            description = doc.selectFirst(".sinopse-full")?.text()
            genre = doc.select("div.filter-items a.filter-btn").eachText().joinToString(", ")
            status = when (doc.selectFirst(".streamer-info-list li.status")?.text()?.lowercase()) {
                "completo" -> SAnime.COMPLETED
                "lançamento" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = getRealDoc(response.asJsoup())
        val script = document.selectFirst("script:containsData(const allEpisodes)")
            ?: return emptyList()

        val scriptText = script.data()
        val jsonString = scriptText
            .substringAfter("const allEpisodes =")
            .substringBefore(";")
            .trim()

        val episodes = json.decodeFromString<List<EpisodeDto>>(jsonString)
        return episodes.reversed().map { it.toSEpisode() }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        return document.select("[data-blogger-url-encrypted]")
            .parallelCatchingFlatMapBlocking {
                val encrypted = it.attr("data-blogger-url-encrypted")
                val decoded = String(Base64.decode(encrypted, Base64.DEFAULT))
                val reversed = decoded.reversed()
                getVideosFromURL(reversed)
            }
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private fun getVideosFromURL(url: String): List<Video> {
        return when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            else -> emptyList()
        }
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_VALUES
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

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst(".episode-navigation span.lista")
        if (menu != null) {
            val originalUrl = menu.parent()!!.attr("href")
            val response = client.newCall(GET(originalUrl, headers)).execute()
            return response.asJsoup()
        }

        return document
    }

    companion object {
        const val PREFIX_SEARCH = "path:"
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("360p", "720p", "1080p")
    }

    @Serializable
    data class EpisodeDto(
        val episodio: String,
        val link: String,
        @SerialName("episode_name")
        val episodeName: String,
        val audio: String?,
        val update: String,
    ) {
        fun toSEpisode(): SEpisode = SEpisode.create().apply {
            url = link
            name = "Episódio $episodio" + if (episodeName.isNotEmpty()) " - $episodeName" else ""
            episode_number = episodio.toFloatOrNull() ?: 1F
            date_upload = update.toDate()
            scanlator = "Áudio: $audio"
        }
    }
}
