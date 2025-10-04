package eu.kanade.tachiyomi.animeextension.es.cuevana

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.cuevana.models.AnimeEpisodesList
import eu.kanade.tachiyomi.animeextension.es.cuevana.models.PopularAnimeList
import eu.kanade.tachiyomi.animeextension.es.cuevana.models.Server
import eu.kanade.tachiyomi.animeextension.es.cuevana.models.Videos
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.text.SimpleDateFormat
import kotlin.text.lowercase

class CuevanaCh(override val name: String, override val baseUrl: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val lang = "es"

    override val id: Long = 8064568253800947423

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

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
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "Tomatomatela",
        )
    }

    override fun popularAnimeSelector(): String = ".MovieList .TPostMv .TPost"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        anime.title = element.select("a .Title").text()
        anime.thumbnail_url = extractImageUrlFromSrc(element.selectFirst("a .Image img")?.attr("src"))?.replace("/original/", "/w200/")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "nav.navigation > div.nav-links > a.next.page-numbers"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        if (response.request.url.toString().contains("/serie/")) {
            val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
            val responseJson = json.decodeFromString<PopularAnimeList>(script)
            responseJson.props?.pageProps?.thisSerie?.seasons?.forEach { s ->
                s.episodes.forEach { e ->
                    val epDate = runCatching {
                        e.releaseDate?.substringBefore("T")?.let { date -> SimpleDateFormat("yyyy-MM-dd").parse(date) }?.time
                    }.getOrDefault(0)
                    val realUrl = e.url?.slug?.replaceFirst("series", "/serie")
                        ?.replace("seasons", "temporada")
                        ?.replace("episodes", "episodio")
                        .orEmpty()
                    val episode = SEpisode.create().apply {
                        episode_number = e.number?.toFloat() ?: 0F
                        name = "T${s.number} - Episodio ${e.number}"
                        date_upload = epDate ?: 0L
                        setUrlWithoutDomain(realUrl)
                    }
                    episodes.add(episode)
                }
            }
        } else {
            val episode = SEpisode.create().apply {
                episode_number = 1f
                name = "PELÍCULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
        val responseJson = json.decodeFromString<AnimeEpisodesList>(script)
        if (response.request.url.toString().contains("/episodio/")) {
            serverIterator(responseJson.props?.pageProps?.episode?.videos).also(videoList::addAll)
        } else {
            serverIterator(responseJson.props?.pageProps?.thisMovie?.videos).also(videoList::addAll)
        }
        return videoList
    }

    private fun serverIterator(videos: Videos?): MutableList<Video> {
        val videoList = mutableListOf<Video>()
        videos?.latino?.getVideos("[LAT]").takeIf { it?.isNotEmpty() == true }?.also(videoList::addAll)
        videos?.spanish?.getVideos("[CAST]").takeIf { it?.isNotEmpty() == true }?.also(videoList::addAll)
        videos?.english?.getVideos("[ENG]").takeIf { it?.isNotEmpty() == true }?.also(videoList::addAll)
        videos?.japanese?.getVideos("[JAP]").takeIf { it?.isNotEmpty() == true }?.also(videoList::addAll)
        return videoList
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun loadExtractor(url: String, prefix: String = "", serverName: String? = ""): List<Video> {
        return runCatching {
            val source = serverName?.ifEmpty { url } ?: url
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in source.lowercase() } }?.first
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "okru" -> okruExtractor.videosFromUrl(url, prefix)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
                "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                "doodstream" -> doodExtractor.videosFromUrl(url, "$prefix DoodStream")
                "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")
                "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")
                "vidhide" -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$prefix VidHide:$it" })
                else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
            }
        }.getOrDefault(emptyList())
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "yourupload" to listOf("yourupload", "upload"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
    )

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?q=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst(".TPost header .Title")!!.text()
        anime.thumbnail_url = extractImageUrlFromSrc(document.selectFirst(".backdrop article div.Image img")?.attr("src"))?.replace("/original/", "/w500/")
        anime.description = document.selectFirst(".backdrop article.TPost div.Description")?.text()?.trim()
        anime.genre = document.select("ul.InfoList li:nth-child(1) > a").joinToString { it.text() }
        anime.status = if (document.location().contains("/pelicula/")) SAnime.COMPLETED else SAnime.UNKNOWN
        anime.artist = document.selectFirst("ul.InfoList .loadactor")?.ownText()?.substringAfter("Actores:")?.split(", ")?.firstOrNull()
        return anime
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/peliculas/estrenos/page/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Tipos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Series", "series/estrenos"),
            Pair("Acción", "genero/accion"),
            Pair("Aventura", "genero/aventura"),
            Pair("Animación", "genero/animacion"),
            Pair("Ciencia Ficción", "genero/ciencia-ficcion"),
            Pair("Comedia", "genero/comedia"),
            Pair("Crimen", "genero/crimen"),
            Pair("Documentales", "genero/documental"),
            Pair("Drama", "genero/drama"),
            Pair("Familia", "genero/familia"),
            Pair("Fantasía", "genero/fantasia"),
            Pair("Misterio", "genero/misterio"),
            Pair("Romance", "genero/romance"),
            Pair("Suspenso", "genero/suspense"),
            Pair("Terror", "genero/terror"),
        ),
    )

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

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun ArrayList<Server>.getVideos(prefix: String): List<Video> {
        val videoList = mutableListOf<Video>()
        for (server in this) {
            try {
                conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in server.result.orEmpty() || it.lowercase() in server.cyberlocker.orEmpty() } }?.first ?: continue
                val body = client.newCall(GET(server.result!!)).execute().asJsoup()
                val url = body.selectFirst("script:containsData(var message)")?.data()?.substringAfter("var url = '")?.substringBefore("'") ?: ""
                loadExtractor(url, prefix).also(videoList::addAll)
            } catch (_: Exception) {}
        }
        return videoList
    }

    private fun extractImageUrlFromSrc(src: String?): String? {
        if (src.isNullOrEmpty()) return null

        val urlParamRegex = Regex("url=([^&]+)")
        val match = urlParamRegex.find(src)
        val encodedUrl = match?.groupValues?.get(1)

        return encodedUrl?.let { URLDecoder.decode(it, "UTF-8") }
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
