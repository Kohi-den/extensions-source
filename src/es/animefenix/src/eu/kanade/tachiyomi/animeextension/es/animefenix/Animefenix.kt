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
import eu.kanade.tachiyomi.lib.amazonextractor.AmazonExtractor
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
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
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Animefenix : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeFenix"

    override val baseUrl = "https://animefenix2.tv"

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
        private const val PREF_SERVER_DEFAULT = "Mp4Upload"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape",
            "Fastream", "Filemoon", "StreamWish", "Okru",
            "Amazon", "AmazonES", "Fireload", "FileLions",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst("h1.text-4xl")?.ownText() ?: ""
            status = document.select(".relative .rounded").getStatus()
            description = document.selectFirst(".mb-6 p.text-gray-300")?.text()
            genre = document.select(".flex-wrap a").joinToString { it.text().trim() }
            thumbnail_url = document.selectFirst("#anime_image")?.getImageUrl()
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/directorio/anime?p=$page&estado=2", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".grid-animes li article a")
        val nextPage = document.select(".right:not(.disabledd)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst("p:not(.gray)")?.text() ?: ""
                thumbnail_url = element.selectFirst(".main-img img")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeFenixFilters.getSearchParameters(filters)

        return when {
            query.isNotBlank() -> GET("$baseUrl/directorio/anime?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/directorio/anime${params.getQuery()}&page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(".divide-y li > a").map {
            val title = it.select(".font-semibold").text()
            SEpisode.create().apply {
                name = title
                episode_number = title.substringAfter("Episodio").toFloatOrNull() ?: 0F
                setUrlWithoutDomain(it.attr("abs:href"))
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(var tabsArray)") ?: return emptyList()
        return script.data().substringAfter("<iframe").split("src='")
            .map { it.substringBefore("'").substringAfter("redirect.php?id=").trim() }
            .parallelCatchingFlatMapBlocking { url ->
                serverVideoResolver(url)
            }
    }

    /*-------------------------------- Video extractors ------------------------------------*/
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstcloudExtractor by lazy { BurstCloudExtractor(client) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val filelionsExtractor by lazy { StreamWishExtractor(client, headers) }
    private val amazonExtractor by lazy { AmazonExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        return runCatching {
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url)
                "amazon" -> amazonExtractor.videosFromUrl(url)
                "okru" -> okruExtractor.videosFromUrl(url)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
                "uqload" -> uqloadExtractor.videosFromUrl(url)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers)
                "streamwish" -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
                "doodstream" -> doodExtractor.videosFromUrl(url, "DoodStream")
                "streamlare" -> streamlareExtractor.videosFromUrl(url)
                "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers = headers)
                "burstcloud" -> burstcloudExtractor.videoFromUrl(url, headers = headers)
                "upstream" -> upstreamExtractor.videosFromUrl(url)
                "streamtape" -> streamTapeExtractor.videosFromUrl(url)
                "vidhide" -> streamHideVidExtractor.videosFromUrl(url)
                "filelions" -> filelionsExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
                "fireload" -> {
                    val video = url.substringAfter("/stream/fl.php?v=")
                    if (client.newCall(GET(video)).execute().code == 200) {
                        listOf(Video(video, "FireLoad", video))
                    } else {
                        emptyList()
                    }
                }
                else -> universalExtractor.videosFromUrl(url, headers)
            }
        }.getOrElse { emptyList() }
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "filelions" to listOf("lion"),
        "amazon" to listOf("amazon", "amz"),
        "uqload" to listOf("uqload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "streamlare" to listOf("streamlare", "slmaxed"),
        "yourupload" to listOf("yourupload", "upload"),
        "burstcloud" to listOf("burstcloud", "burst"),
        "upstream" to listOf("upstream"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
        "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "bembed"),
        "fireload" to listOf("/stream/fl.php"),
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

    private fun Elements.getStatus(): Int {
        return when {
            text().contains("finalizado", true) -> SAnime.COMPLETED
            text().contains("emision", true) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getImageUrl(): String? {
        return when {
            isValidUrl("data-src") -> attr("abs:data-src")
            isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
            isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
            isValidUrl("src") -> attr("abs:src")
            else -> ""
        }
    }

    private fun Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("data:image/")
    }

    override fun getFilterList(): AnimeFilterList = AnimeFenixFilters.FILTER_LIST

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
