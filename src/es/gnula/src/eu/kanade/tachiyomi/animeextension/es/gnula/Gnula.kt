package eu.kanade.tachiyomi.animeextension.es.gnula

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
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamsilkextractor.StreamSilkExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

class Gnula : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Gnula"

    override val baseUrl = "https://gnula.life"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "StreamHideVid",
        )

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[CAST]", "[SUB]")

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        }
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/archives/movies/page/$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data()
            ?: return AnimesPage(emptyList(), false)
        val jsonData = jsonString.parseTo<PopularModel>().props.pageProps
        val hasNextPage = document.selectFirst("ul.pagination > li.page-item.active ~ li > a > span.visually-hidden")?.text()?.contains("Next") ?: false
        var type = jsonData.results.typename ?: ""

        val animeList = jsonData.results.data.map {
            if (!it.url.slug.isNullOrEmpty()) {
                type = when {
                    "series" in it.url.slug -> "PaginatedSerie"
                    "movies" in it.url.slug -> "PaginatedMovie"
                    else -> ""
                }
            }

            SAnime.create().apply {
                title = it.titles.name ?: ""
                thumbnail_url = it.images.poster?.replace("/original/", "/w200/")
                setUrlWithoutDomain(urlSolverByType(type, it.slug.name ?: ""))
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/archives/movies/releases/page/$page")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (response.request.url.toString().contains("/movies/")) {
            listOf(
                SEpisode.create().apply {
                    name = "Película"
                    episode_number = 1F
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        } else {
            val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return emptyList()
            val jsonData = jsonString.parseTo<SeasonModel>().props.pageProps
            var episodeCounter = 1F
            jsonData.post.seasons
                .flatMap { season ->
                    season.episodes.map { ep ->
                        val episode = SEpisode.create().apply {
                            episode_number = episodeCounter++
                            name = "T${season.number} - E${ep.number} - ${ep.title}"
                            date_upload = ep.releaseDate?.toDate() ?: 0L
                            setUrlWithoutDomain("$baseUrl/series/${ep.slug.name}/seasons/${ep.slug.season}/episodes/${ep.slug.episode}")
                        }
                        episode
                    }
                }
        }.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return emptyList()

        if (response.request.url.toString().contains("/movies/")) {
            val pageProps = jsonString.parseTo<SeasonModel>().props.pageProps
            pageProps.post.players.latino.toVideoList("[LAT]").also(videoList::addAll)
            pageProps.post.players.spanish.toVideoList("[CAST]").also(videoList::addAll)
            pageProps.post.players.english.toVideoList("[SUB]").also(videoList::addAll)
        } else {
            val pageProps = jsonString.parseTo<EpisodeModel>().props.pageProps
            pageProps.episode.players.latino.toVideoList("[LAT]").also(videoList::addAll)
            pageProps.episode.players.spanish.toVideoList("[CAST]").also(videoList::addAll)
            pageProps.episode.players.english.toVideoList("[SUB]").also(videoList::addAll)
        }
        return videoList
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    fun serverVideoResolver(url: String, prefix: String = "", serverName: String? = ""): List<Video> {
        return runCatching {
            val source = serverName?.ifEmpty { url } ?: url
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in source.lowercase() } }?.first
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "okru" -> okruExtractor.videosFromUrl(url, prefix)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
                "amazon" -> {
                    val body = client.newCall(GET(url)).execute().asJsoup()
                    return if (body.select("script:containsData(var shareId)").toString().isNotBlank()) {
                        val shareId = body.selectFirst("script:containsData(var shareId)")!!.data()
                            .substringAfter("shareId = \"").substringBefore("\"")
                        val amazonApiJson = client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
                            .execute().asJsoup()
                        val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
                        val amazonApi =
                            client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
                                .execute().asJsoup()
                        val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
                        listOf(Video(videoUrl, "$prefix Amazon", videoUrl))
                    } else {
                        emptyList()
                    }
                }
                "uqload" -> uqloadExtractor.videosFromUrl(url, prefix)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                "doodstream" -> doodExtractor.videosFromUrl(url, "$prefix DoodStream")
                "streamlare" -> streamlareExtractor.videosFromUrl(url, prefix)
                "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")
                "burstcloud" -> burstCloudExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")
                "fastream" -> fastreamExtractor.videosFromUrl(url, prefix = "$prefix Fastream:")
                "upstream" -> upstreamExtractor.videosFromUrl(url, prefix = "$prefix ")
                "streamsilk" -> streamSilkExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamSilk:$it" })
                "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")
                "vidhide" -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$prefix VidHide:$it" })
                "vidguard" -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")
                else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
            }
        }.getOrNull() ?: emptyList()
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "amazon" to listOf("amazon", "amz"),
        "uqload" to listOf("uqload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "streamlare" to listOf("streamlare", "slmaxed"),
        "yourupload" to listOf("yourupload", "upload"),
        "burstcloud" to listOf("burstcloud", "burst"),
        "fastream" to listOf("fastream"),
        "upstream" to listOf("upstream"),
        "streamsilk" to listOf("streamsilk"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
        "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "bembed"),
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?q=$query&p=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return SAnime.create()
        val json = jsonString.parseTo<SeasonModel>()
        val post = json.props.pageProps.post
        return SAnime.create().apply {
            title = post.titles.name ?: ""
            thumbnail_url = post.images.poster
            description = post.overview
            genre = post.genres.joinToString { it.name ?: "" }
            artist = post.cast.acting.firstOrNull()?.name ?: ""
            status = if (json.page.contains("movie", true)) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    private fun List<Region>.toVideoList(lang: String): List<Video> {
        return this.parallelCatchingFlatMapBlocking {
            var url = ""
            client.newCall(GET(it.result)).execute().asJsoup().select("script").map { sc ->
                if (sc.data().contains("var url = '")) {
                    url = sc.data().substringAfter("var url = '").substringBefore("';")
                }
            }
            serverVideoResolver(url, lang)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Películas", "archives/movies/releases"),
            Pair("Series", "archives/series/releases"),
            Pair("Acción", "genres/accion"),
            Pair("Animación", "genres/animacion"),
            Pair("Crimen", "genres/crimen"),
            Pair("Fámilia", "genres/familia"),
            Pair("Misterio", "genres/misterio"),
            Pair("Suspenso", "genres/suspenso"),
            Pair("Aventura", "genres/aventura"),
            Pair("Ciencia Ficción", "genres/ciencia-ficcion"),
            Pair("Drama", "genres/drama"),
            Pair("Fantasía", "genres/fantasia"),
            Pair("Romance", "genres/romance"),
            Pair("Terror", "genres/terror"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> String.parseTo(): T {
        return json.decodeFromString<T>(this)
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
    }

    private fun urlSolverByType(type: String, slug: String): String {
        return when (type) {
            "PaginatedMovie", "PaginatedGenre" -> "$baseUrl/movies/$slug"
            "PaginatedSerie" -> "$baseUrl/series/$slug"
            else -> ""
        }
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

    // Not Used
    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()
    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()
    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    // Not Used
}
