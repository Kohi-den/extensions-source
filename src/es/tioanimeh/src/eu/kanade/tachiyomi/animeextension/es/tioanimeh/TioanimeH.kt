package eu.kanade.tachiyomi.animeextension.es.tioanimeh

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class TioanimeH(override val name: String, override val baseUrl: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload",
            "Voe",
            "VidGuard",
            "Okru",
        )
    }

    override fun popularAnimeSelector(): String = "ul.animes.list-unstyled.row li.col-6.col-sm-4.col-md-3.col-xl-2"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/directorio?p=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.select("article a").attr("href")
            title = element.select("article a h3").text()
            thumbnail_url = baseUrl + element.select("article a div figure img").attr("src")
        }
    }

    override fun popularAnimeNextPageSelector(): String = ".pagination .active ~ li:not(.disabled)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val epInfoScript = document.selectFirst("script:containsData(var episodes = )")!!.data()

        if (epInfoScript.substringAfter("episodes = [").substringBefore("];").isEmpty()) {
            return emptyList()
        }

        val epNumList = epInfoScript.substringAfter("episodes = [").substringBefore("];").split(",")
        val epSlug = epInfoScript.substringAfter("anime_info = [").substringBefore("];").replace("\"", "").split(",")[1]

        return epNumList.map {
            SEpisode.create().apply {
                name = "Episodio $it"
                url = "/ver/$epSlug-$it"
                episode_number = it.toFloat()
            }
        }
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val serverList = document.selectFirst("script:containsData(var videos =)")
            ?.data()?.substringAfter("var videos = [[")?.substringBefore("]];")
            ?.replace("\"", "")?.split("],[") ?: return emptyList()

        return serverList.parallelCatchingFlatMapBlocking {
            val servers = it.split(",")
            val serverName = servers[0]
            val serverUrl = servers[1].replace("\\/", "/")
            when (serverName.lowercase()) {
                "voe" -> voeExtractor.videosFromUrl(serverUrl)
                "vidguard" -> vidGuardExtractor.videosFromUrl(serverUrl)
                "okru" -> okruExtractor.videosFromUrl(serverUrl)
                "yourupload" -> yourUploadExtractor.videoFromUrl(serverUrl, headers = headers)
                "mixdrop" -> mixDropExtractor.videosFromUrl(serverUrl)
                else -> universalExtractor.videosFromUrl(serverUrl, headers)
            }
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
        val params = TioAnimeHFilters.getSearchParameters(filters, TioAnimeHFilters.TioAnimeHFiltersData.ORIGEN.HENTAI)

        return when {
            query.isNotBlank() -> GET("$baseUrl/directorio?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/directorio${params.getQuery()}&p=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.select("h1.title").text()
            description = document.selectFirst("p.sinopsis")!!.ownText()
            genre = document.select("p.genres span.btn.btn-sm.btn-primary.rounded-pill a").joinToString { it.text() }
            thumbnail_url = document.select(".thumb img").attr("abs:src")
            status = parseStatus(document.select("a.btn.btn-success.btn-block.status").text())
        }
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select("article a h3").text()
            thumbnail_url = baseUrl + element.select("article a div figure img").attr("src")
            val slug = if (baseUrl.contains("hentai")) "/hentai/" else "/anime/"
            val fixUrl = element.select("article a").attr("href").split("-").toTypedArray()
            val realUrl = fixUrl.copyOf(fixUrl.size - 1).joinToString("-").replace("/ver/", slug)
            setUrlWithoutDomain(realUrl)
        }
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl)

    override fun latestUpdatesSelector() = ".episodes li"

    override fun getFilterList(): AnimeFilterList = TioAnimeHFilters.getFilterList(TioAnimeHFilters.TioAnimeHFiltersData.ORIGEN.HENTAI)

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
