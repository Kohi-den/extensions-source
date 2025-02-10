package eu.kanade.tachiyomi.animeextension.es.animefenix

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
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder

class Animefenix : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeFenix"

    override val baseUrl = "https://www3.animefenix.tv"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000) }

    companion object {
        private val SERVER_REGEX = """tabsArray\['?\d+'?]\s*=\s*['\"](https[^'\"]+)['\"]""".toRegex()
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Amazon"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape",
            "Fastream", "Filemoon", "StreamWish", "Okru",
            "Amazon", "AmazonES", "Fireload", "FileLions",
        )
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?order=likes&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.container .grid.gap-4 a[href]")
        val nextPage = document.select("nav[aria-label=Pagination] span:containsOwn(Next)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.selectFirst("div h3.text-primary")!!.ownText()
                thumbnail_url = element.selectFirst("img.object-cover")?.attr("abs:src")
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?order=added&page=$page")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeFenixFilters.getSearchParameters(filters)

        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?q=$query&page=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&page=$page", headers)
            else -> GET("$baseUrl/animes?order=likes&page=$page")
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("div.container > div > ul > li").map { element ->
            SEpisode.create().apply {
                name = element.selectFirst("span > span")!!.ownText()
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val serversData = document.selectFirst("script:containsData(var tabsArray)")?.data() ?: throw Exception("No se encontraron servidores")
        val servers = SERVER_REGEX.findAll(serversData).map { it.groupValues[1] }.toList()

        servers.parallelForEachBlocking { server ->
            val decodedUrl = URLDecoder.decode(server, "UTF-8")
            val realUrl = try {
                client.newCall(GET(decodedUrl)).execute().asJsoup().selectFirst("script")!!
                    .data().substringAfter("src=\"").substringBefore("\"")
            } catch (e: Exception) { "" }

            try {
                serverVideoResolver(realUrl).let { videoList.addAll(it) }
            } catch (_: Exception) { }
        }
        return videoList.filter { it.url.contains("https") || it.url.contains("http") }
    }

    private fun serverVideoResolver(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        try {
            when {
                embedUrl.contains("voe") -> {
                    VoeExtractor(client).videosFromUrl(url).also(videoList::addAll)
                }
                (embedUrl.contains("amazon") || embedUrl.contains("amz")) && !embedUrl.contains("disable") -> {
                    val video = amazonExtractor(baseUrl + url.substringAfter(".."))
                    if (video.isNotBlank()) {
                        if (url.contains("&ext=es")) {
                            videoList.add(Video(video, "AmazonES", video))
                        } else {
                            videoList.add(Video(video, "Amazon", video))
                        }
                    }
                }
                embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> {
                    OkruExtractor(client).videosFromUrl(url).also(videoList::addAll)
                }
                embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") -> {
                    val vidHeaders = headers.newBuilder()
                        .add("Origin", "https://${url.toHttpUrl().host}")
                        .add("Referer", "https://${url.toHttpUrl().host}/")
                        .build()
                    FilemoonExtractor(client).videosFromUrl(url, prefix = "Filemoon:", headers = vidHeaders).also(videoList::addAll)
                }
                embedUrl.contains("uqload") -> {
                    UqloadExtractor(client).videosFromUrl(url).also(videoList::addAll)
                }
                embedUrl.contains("mp4upload") -> {
                    Mp4uploadExtractor(client).videosFromUrl(url, headers).let { videoList.addAll(it) }
                }
                embedUrl.contains("wishembed") || embedUrl.contains("embedwish") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") -> {
                    val docHeaders = headers.newBuilder()
                        .add("Origin", "https://streamwish.to")
                        .add("Referer", "https://streamwish.to/")
                        .build()
                    StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" }).also(videoList::addAll)
                }
                embedUrl.contains("doodstream") || embedUrl.contains("dood.") -> {
                    DoodExtractor(client).videoFromUrl(url, "DoodStream")?.let { videoList.add(it) }
                }
                embedUrl.contains("streamlare") -> {
                    StreamlareExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
                }
                embedUrl.contains("yourupload") || embedUrl.contains("upload") -> {
                    YourUploadExtractor(client).videoFromUrl(url, headers = headers).let { videoList.addAll(it) }
                }
                embedUrl.contains("burstcloud") || embedUrl.contains("burst") -> {
                    BurstCloudExtractor(client).videoFromUrl(url, headers = headers).let { videoList.addAll(it) }
                }
                embedUrl.contains("fastream") -> {
                    FastreamExtractor(client, headers).videosFromUrl(url).also(videoList::addAll)
                }
                embedUrl.contains("upstream") -> {
                    UpstreamExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
                }
                embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape") -> {
                    StreamTapeExtractor(client).videoFromUrl(url)?.let { videoList.add(it) }
                }
                embedUrl.contains("ahvsh") || embedUrl.contains("streamhide") -> {
                    StreamHideVidExtractor(client, headers).videosFromUrl(url).let { videoList.addAll(it) }
                }
                embedUrl.contains("/stream/fl.php") -> {
                    val video = url.substringAfter("/stream/fl.php?v=")
                    if (client.newCall(GET(video)).execute().code == 200) {
                        videoList.add(Video(video, "FireLoad", video))
                    }
                }
                embedUrl.contains("filelions") || embedUrl.contains("lion") -> {
                    StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" }).also(videoList::addAll)
                }
                else ->
                    UniversalExtractor(client).videosFromUrl(url, headers).let { videoList.addAll(it) }
            }
        } catch (_: Exception) { }
        return videoList
    }

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

    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val document = response.asJsoup()
        with(document.selectFirst("main > div.relative > div.container > div.flex")!!) {
            title = selectFirst("h1.font-bold")!!.ownText()
            genre = select("div:has(h2:containsOwn(Géneros)) > div.flex > a").joinToString { it.text() }
            status = parseStatus(selectFirst("li:has(> span:containsOwn(Estado))")!!.ownText())
            description = select("div:has(h2:containsOwn(Sinopsis)) > p").text()
        }
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun amazonExtractor(url: String): String {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoURl = document.selectFirst("script:containsData(sources: [)")!!.data()
            .substringAfter("[{\"file\":\"")
            .substringBefore("\",").replace("\\", "")

        return try {
            if (client.newCall(GET(videoURl)).execute().code == 200) videoURl else ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFenixFilters.FILTER_LIST

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

    suspend inline fun <A> Iterable<A>.parallelForEach(crossinline f: suspend (A) -> Unit) {
        coroutineScope {
            for (item in this@parallelForEach) {
                launch(Dispatchers.IO) {
                    f(item)
                }
            }
        }
    }

    inline fun <A> Iterable<A>.parallelForEachBlocking(crossinline f: suspend (A) -> Unit) {
        runBlocking {
            this@parallelForEachBlocking.parallelForEach(f)
        }
    }
}
