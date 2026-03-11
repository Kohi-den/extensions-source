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
import eu.kanade.tachiyomi.animesource.model.AnimesPage
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
import java.text.SimpleDateFormat

class CuevanaEu(override val name: String, override val baseUrl: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val lang = "es"

    override val supportsLatest = false

    override val id: Long = 1487248605713977697

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[ENG]", "[CAST]", "[JAP]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "Tomatomatela", "YourUpload", "Doodstream", "Okru",
            "Voe", "StreamTape", "StreamWish", "Filemoon",
            "FileLions",
        )
    }

    override fun popularAnimeSelector(): String = ".MovieList .TPostMv .TPost"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas/estrenos/page/$page")

    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val hasNextPage = document.select("nav.navigation > div.nav-links > a.next.page-numbers").any()
        val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()

        val responseJson = json.decodeFromString<PopularAnimeList>(script)
        responseJson.props?.pageProps?.movies?.map { animeItem ->
            val anime = SAnime.create()
            val preSlug = animeItem.url?.slug ?: ""
            val type = if (preSlug.startsWith("series")) "ver-serie" else "ver-pelicula"

            anime.title = animeItem.titles?.name ?: ""
            anime.thumbnail_url = animeItem.images?.poster?.replace("/original/", "/w200/") ?: ""
            anime.description = animeItem.overview
            anime.setUrlWithoutDomain("/$type/${animeItem.slug?.name}")
            animeList.add(anime)
        }

        return AnimesPage(animeList, hasNextPage)
    }

    override fun popularAnimeNextPageSelector(): String = "uwu"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        if (response.request.url.toString().contains("/ver-serie/")) {
            val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
            val responseJson = json.decodeFromString<AnimeEpisodesList>(script)
            responseJson.props?.pageProps?.thisSerie?.seasons?.map {
                it.episodes.map { ep ->
                    val episode = SEpisode.create()
                    val epDate = runCatching {
                        ep.releaseDate.orEmpty().substringBefore("T").let { date -> SimpleDateFormat("yyyy-MM-dd").parse(date) }?.time
                    }.getOrDefault(0)
                    episode.date_upload = epDate ?: 0L
                    episode.name = "T${ep.slug?.season} - Episodio ${ep.slug?.episode}"
                    episode.episode_number = ep.number?.toFloat()!!
                    episode.setUrlWithoutDomain("/episodio/${ep.slug?.name}-temporada-${ep.slug?.season}-episodio-${ep.slug?.episode}")
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?q=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document) = throw UnsupportedOperationException()

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val newAnime = SAnime.create()
        val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
        val responseJson = json.decodeFromString<AnimeEpisodesList>(script)
        if (response.request.url.toString().contains("/ver-serie/")) {
            val data = responseJson.props?.pageProps?.thisSerie
            newAnime.status = SAnime.UNKNOWN
            newAnime.title = data?.titles?.name ?: ""
            newAnime.description = data?.overview ?: ""
            newAnime.thumbnail_url = data?.images?.poster?.replace("/original/", "/w500/")
            newAnime.genre = data?.genres?.joinToString { it.name ?: "" }
            newAnime.artist = data?.cast?.acting?.firstOrNull()?.name ?: ""
            newAnime.setUrlWithoutDomain(response.request.url.toString())
        } else {
            val data = responseJson.props?.pageProps?.thisMovie
            newAnime.status = SAnime.UNKNOWN
            newAnime.title = data?.titles?.name ?: ""
            newAnime.description = data?.overview ?: ""
            newAnime.thumbnail_url = data?.images?.poster?.replace("/original/", "/w500/")
            newAnime.genre = data?.genres?.joinToString { it.name ?: "" }
            newAnime.artist = data?.cast?.acting?.firstOrNull()?.name ?: ""
            newAnime.setUrlWithoutDomain(response.request.url.toString())
        }

        return newAnime
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

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
