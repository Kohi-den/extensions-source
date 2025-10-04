package eu.kanade.tachiyomi.animeextension.es.hentaila

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
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Hentaila : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Hentaila"

    override val baseUrl = "https://hentaila.com"

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
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "Voe",
            "Arc",
            "YourUpload",
            "Mp4Upload",
            "BurstCloud",
            "StreamHideVid",
            "Sendvid",
        )

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMMMM dd, yyyy", Locale.ENGLISH)
        }
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/catalogo?order=popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("[class*=\"group/item\"]")
        val hasNext = document.select(".flex span.pointer-events-none:not([class*=\"max-sm:hidden\"]) ~ a").any()
        val animes = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("a").attr("abs:href"))
                title = element.selectFirst("header > h3")!!.text()
                thumbnail_url = element.selectFirst(".bg-current img")!!.attr("abs:src")
                description = element.selectFirst(".line-clamp-6")?.text()
            }
        }
        return AnimesPage(animes, hasNext)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/catalogo?order=latest_released&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = HentailaFilters.getSearchParameters(filters)
        return when {
            query.isNotEmpty() -> GET("$baseUrl/catalogo?search=$query&page=$page")
            params.filter.isNotBlank() -> GET("$baseUrl/catalogo${params.getQuery()}&page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("img.aspect-poster")!!.attr("abs:src")
            title = document.selectFirst("header h1.text-lead")!!.text()
            description = document.select(".items-start > .entry > p").text()
            genre = document.select("header .items-center a.rounded-full").joinToString { it.text() }
            status = if (document.select("header .text-sm span").any { it.text().contains("En emisión") }) SAnime.ONGOING else SAnime.COMPLETED
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        return jsoup.select(".from-mute article[class*=\"group/item\"]").reversed().map { it ->
            val href = it.select("a").attr("href")
            val epNum = href.split("/").last()
            SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                setUrlWithoutDomain(href)
            }
        }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoServers = document.selectFirst("script:containsData(embeds:{)")!!.data()
            .substringAfter("embeds:{").substringBefore("uses:")

        val videoServerList = fetchUrls(videoServers)
        return videoServerList.parallelCatchingFlatMapBlocking { urlServer ->
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in urlServer.lowercase() } }?.first
            when (matched) {
                "streamwish" -> streamWishExtractor.videosFromUrl(urlServer, videoNameGen = { "StreamWish:$it" })
                "voe" -> voeExtractor.videosFromUrl(urlServer)
                "arc" -> listOf(Video(urlServer.substringAfter("#"), "Arc", urlServer.substringAfter("#")))
                "yourupload" -> yourUploadExtractor.videoFromUrl(urlServer, headers = headers)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(urlServer, headers = headers)
                "burstcloud" -> burstCloudExtractor.videoFromUrl(urlServer, headers = headers)
                "vidhide" -> streamHideVidExtractor.videosFromUrl(urlServer)
                "sendvid" -> sendvidExtractor.videosFromUrl(urlServer)
                else -> universalExtractor.videosFromUrl(urlServer, headers)
            }
        }
    }

    private val conventions = listOf(
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "arc" to listOf("arc"),
        "mp4upload" to listOf("mp4upload"),
        "yourupload" to listOf("yourupload", "yupi"),
        "burstcloud" to listOf("burstcloud", "burst"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
        "sendvid" to listOf("sendvid"),
        "mediafire" to listOf("mediafire"),
    )

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

    override fun getFilterList(): AnimeFilterList = HentailaFilters.FILTER_LIST

    fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) {
            return listOf()
        }
        val linkRegex =
            Regex("""(https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))""")
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
    }
}
