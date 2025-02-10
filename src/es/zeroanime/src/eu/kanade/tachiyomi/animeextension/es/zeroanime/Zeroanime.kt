package eu.kanade.tachiyomi.animeextension.es.zeroanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamvidextractor.StreamVidExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Zeroanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "zeroanime"

    override val baseUrl = "https://www4.zeroanime.xyz"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "mp4upload"
        private val SERVER_LIST = arrayOf(
            "filemoon",
            "mp4upload",
            "streamtape",
            "streamvid",
        )
    }

    override fun popularAnimeSelector(): String = "ul.animes.list-unstyled.row li.col-6.col-sm-4.col-md-3.col-xl-2"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/search?q=&letra=&genero=ALL&years=ALL&estado=2&orden=desc&p=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.select("a").attr("href")
            title = element.select("div.title").text()
            thumbnail_url = element.select("div.thumb img").attr("src")
        }
    }

    override fun popularAnimeNextPageSelector(): String {
        return "ul.pagination li.page-item:not(.active) a"
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        document.select("li.hentai__chapter").forEach { element ->
            val episode = SEpisode.create().apply {
                name = element.select("div.chapter_info span.title").text()
                url = element.select("a").attr("href")
                episode_number = name.substringAfter("Episodio ").toFloatOrNull() ?: 0F
                setUrlWithoutDomain(url)
            }
            episodes.add(episode)
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    /*--------------------------------Video extractors------------------------------------*/
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val fileMoonExtractor by lazy { FilemoonExtractor(client) }
    private val mp4UploadExtractor by lazy { Mp4uploadExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body?.string() ?: "")
        val videoList = mutableListOf<Video>()

        // Elimina espacios innecesarios del contenido HTML
        val data = document.html().replace(Regex("""\n|\r|\t|\s{2}|&nbsp;"""), "")

        // Coincide con los botones que contienen los enlaces a los videos
        val matches = Regex("""<button id="embed-.*?data-url="(.*?)".*?->(.*?)</button>""").findAll(data)

        matches.forEach { match ->
            val (url, srv) = match.destructured
            var videoUrl = url.trim()

            // Convertir URL relativa en absoluta
            if (videoUrl.startsWith("../redirect.php?")) {
                videoUrl = "$baseUrl${videoUrl.replace("../redirect.php?", "/redirect.php?")}"
            }

            // Solicitud a la URL para obtener la redirección
            val redirectResponse = client.newCall(Request.Builder().url(videoUrl).build()).execute()
            val refreshHeader = redirectResponse.header("refresh")
            val finalUrl = refreshHeader?.let {
                Regex("""0;\s*URL=(.*?)$""").find(it)?.groupValues?.get(1)
            } ?: Regex("""url=(.*?)$""").find(videoUrl)?.groupValues?.get(1)

            finalUrl?.let { processedUrl ->
                var resolvedUrl = processedUrl

                // Procesar enlaces de video internos
                if (resolvedUrl.startsWith("../video/")) {
                    resolvedUrl = "$baseUrl${resolvedUrl.replace("../video/", "/video/")}"
                }

                val response = client.newCall(Request.Builder().url(resolvedUrl).build()).execute()
                val refreshUrl = response.header("refresh")?.let {
                    Regex("""0;\s*URL=(.*?)$""").find(it)?.groupValues?.get(1)
                }

                Log.d("Zeroanime", "URL extraída del refresh: $refreshUrl")

                // Resolver el servidor y agregar la lista de videos
                refreshUrl?.let { serverVideoResolver(it, srv) }?.let { videoList.addAll(it) }
            }
        }

        return videoList.sort()
    }

    private fun serverVideoResolver(url: String, server: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        Log.d("Zeroanime", "Server: $embedUrl")
        return try {
            when {
                embedUrl.contains("streamtape") -> {
                    streamtapeExtractor.videosFromUrl(url).also { videoList.addAll(it) }
                }
                embedUrl.contains("filemoon") -> {
                    fileMoonExtractor.videosFromUrl(url).also { videoList.addAll(it) }
                }
                embedUrl.contains("mp4upload") -> {
                    mp4UploadExtractor.videosFromUrl(url, headers).also { videoList.addAll(it) }
                }
                embedUrl.contains("streamvid") -> {
                    StreamVidExtractor(client).videosFromUrl(url).also { videoList.addAll(it) }
                }

                else -> {
                    universalExtractor.videosFromUrl(url, headers).also { videoList.addAll(it) }
                }
            }
        } catch (e: Exception) {
            Log.e("Zeroanime", "Error: Server not supported - ${e.message}")
            emptyList()
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = ZeroAnimeFilters.getSearchParameters(filters, ZeroAnimeFilters.ZeroAnimeFiltersData.ORIGEN.ANIME)

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/search${params.getQuery()}&p=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.select("h1.htitle").text()
            description = document.select("div.vraven_text.single").text()
            genre = document.select("div.single_data div.list a").joinToString { it.text() }
            thumbnail_url = document.select("div.hentai_cover img").attr("abs:src")
            status = parseStatus(document.select("div.data").text())
        }
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Emisión", ignoreCase = true) -> SAnime.ONGOING
            statusString.contains("Finalizado", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/search?q=&letra=ALL&genero=ALL&years=2024&estado=2&orden=asc&p=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun getFilterList(): AnimeFilterList = ZeroAnimeFilters.getFilterList(ZeroAnimeFilters.ZeroAnimeFiltersData.ORIGEN.ANIME)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
    }
}
