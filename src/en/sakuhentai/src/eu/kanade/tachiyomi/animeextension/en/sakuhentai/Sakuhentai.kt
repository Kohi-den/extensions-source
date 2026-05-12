package eu.kanade.tachiyomi.animeextension.en.sakuhentai

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Sakuhentai : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Sakuhentai"
    override val baseUrl = "https://www.sakuhentai.net"
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val extractor by lazy { SakuhentaiExtractor(client, headers) }

    private val authorRegex = Regex("""\s+by\s+(\S.+)$""", RegexOption.IGNORE_CASE)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/hentai-animation-online/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        document.select("article.category-hentai-animation-online").forEach { article ->
            animeList.add(article.toSAnime())
        }
        val hasNextPage = document.selectFirst(".page-numbers a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val recposts = document.select(".recpost")
        if (recposts.isNotEmpty()) {
            recposts.forEach { recpost ->
                val title = recpost.selectFirst(".recpost-title a")?.text() ?: ""
                if (!title.contains("Gallery", ignoreCase = true)) {
                    animeList.add(recpost.recpostToSAnime())
                }
            }
        } else {
            document.select("article.category-hentai-animation-online").forEach { article ->
                animeList.add(article.toSAnime())
            }
        }
        return AnimesPage(animeList, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = SakuhentaiFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                if (page == 1) {
                    GET("$baseUrl/?s=$encoded", headers)
                } else {
                    GET("$baseUrl/page/$page/?s=$encoded", headers)
                }
            }
            params.series.isNotBlank() -> GET("$baseUrl/${params.series}/page/$page/", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()

        // Primary: animation category articles
        document.select("article.category-hentai-animation-online").forEach { article ->
            animeList.add(article.toSAnime())
        }

        // Secondary: gallery articles with "Animation" in title (common on series pages)
        document.select("article.category-hentai-gallery").forEach { article ->
            val title = article.selectFirst(".entry-title a")?.text() ?: ""
            if (title.contains("Animation", ignoreCase = true)) {
                animeList.add(article.toSAnime())
            }
        }

        // Tertiary: uncategorized articles with "Animation" in title
        document.select("article").forEach { article ->
            if (
                !article.hasClass("category-hentai-animation-online") &&
                !article.hasClass("category-hentai-gallery")
            ) {
                val title = article.selectFirst(".entry-title a")?.text() ?: ""
                if (title.contains("Animation", ignoreCase = true)) {
                    animeList.add(article.toSAnime())
                }
            }
        }

        val hasNextPage = document.selectFirst(".page-numbers a.next") != null
        return AnimesPage(animeList.distinctBy { it.url }, hasNextPage)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id", headers))
                .awaitSuccess()
                .use { searchAnimeByIdParse(it) }
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val anime = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString().substringAfter(baseUrl).ifEmpty { "/" })
            initialized = true
        }
        return AnimesPage(listOf(anime), false)
    }

    override fun getFilterList(): AnimeFilterList = SakuhentaiFilters.FILTER_LIST

    // =========================== Anime Details ===========================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val thumbnailUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { document.selectFirst("img.wp-post-image")?.absUrl("src") }
            ?: ""
        val descriptionText = document.selectFirst("p.saku")?.text()?.trim() ?: ""

        val genreList = mutableListOf<String>()
        document.select(".breadcrumb a").forEach { breadcrumb ->
            val text = breadcrumb.text().trim()
            if (text.isNotBlank() && text != "Sakuhentai") {
                genreList.add(text)
            }
        }

        val rawTitle = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        val authorMatch = authorRegex.find(rawTitle)
        val cleanTitle = authorMatch?.groupValues?.get(1)?.let { rawTitle.removeSuffix(" by $it") }
            ?: rawTitle
        val authorName = authorMatch?.groupValues?.get(1) ?: ""

        return SAnime.create().apply {
            title = cleanTitle
            author = authorName
            artist = authorName
            thumbnail_url = thumbnailUrl
            description = descriptionText
            genre = genreList.joinToString(", ")
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episode = SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString().substringAfter(baseUrl).ifEmpty { "/" })
            name = "Episode 1"
            episode_number = 1f
            date_upload = parseDate(
                document.selectFirst("time.entry-date")?.attr("datetime")
                    ?: document.selectFirst(".entry-meta time")?.attr("datetime")
                    ?: document.selectFirst("[datetime]")?.attr("datetime"),
            )
        }
        return listOf(episode)
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
            } catch (_: Exception) { 0L }
        }
    }

    // =============================== Videos ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        val embedElements = document.select(".change-video[data-embed]")
        if (embedElements.isEmpty()) return emptyList()

        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val sortedEmbeds = if (preferredServer != "all") {
            embedElements.sortedByDescending {
                it.attr("data-embed").contains(preferredServer, ignoreCase = true)
            }
        } else {
            embedElements.toList()
        }

        for (embedElement in sortedEmbeds) {
            val embedUrl = embedElement.attr("data-embed")
            if (embedUrl.isNotBlank()) {
                val prefix = when {
                    embedUrl.contains("natsumi", ignoreCase = true) -> "Natsumi - "
                    embedUrl.contains("hglink", ignoreCase = true) -> "HgLink - "
                    else -> "Server - "
                }
                videos.addAll(extractor.videosFromUrl(embedUrl, prefix))
            }
        }
        return videos.distinctBy { it.url }
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareByDescending<Video> { video ->
                when {
                    preferredServer != "all" && video.quality.contains(preferredServer, ignoreCase = true) -> 2
                    video.quality.contains(preferredQuality, ignoreCase = true) -> 1
                    else -> 0
                }
            }.thenByDescending { video ->
                val qualityNum = Regex("""(\d+)p""").find(video.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                qualityNum
            },
        )
    }

    // ============================== Helpers ==============================

    private fun Element.toSAnime(): SAnime = SAnime.create().apply {
        val rawTitle = selectFirst(".entry-title a")?.text()?.trim() ?: "Unknown"
        val authorMatch = authorRegex.find(rawTitle)
        title = authorMatch?.groupValues?.get(1)?.let { rawTitle.removeSuffix(" by $it") }
            ?: rawTitle
        setUrlWithoutDomain(selectFirst(".entry-title a")?.attr("href")?.substringAfter(baseUrl) ?: "")
        thumbnail_url = selectFirst("img.wp-post-image")?.absUrl("src") ?: ""
    }

    private fun Element.recpostToSAnime(): SAnime = SAnime.create().apply {
        val rawTitle = selectFirst(".recpost-title a")?.text()?.trim() ?: "Unknown"
        val authorMatch = authorRegex.find(rawTitle)
        title = authorMatch?.groupValues?.get(1)?.let { rawTitle.removeSuffix(" by $it") }
            ?: rawTitle
        setUrlWithoutDomain(selectFirst(".recpost-title a")?.attr("href")?.substringAfter(baseUrl) ?: "")
        thumbnail_url = selectFirst(".recpost-thumb img")?.absUrl("src") ?: ""
    }

    // ============================ Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_ENTRIES
            entryValues = QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_DISPLAY_ENTRIES
            entryValues = SERVER_VALUE_ENTRIES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "natsumi"
        private val SERVER_DISPLAY_ENTRIES = arrayOf("All", "Natsumi", "HgLink")
        private val SERVER_VALUE_ENTRIES = arrayOf("all", "natsumi", "hglink")

        const val PREFIX_SEARCH = "id:"
    }
}
