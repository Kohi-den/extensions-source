package eu.kanade.tachiyomi.animeextension.es.animeav1

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
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.pixeldrainextractor.PixelDrainExtractor
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

class AnimeAv1 : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeAv1"

    override val baseUrl = "https://animeav1.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "DUB")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "DUB")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "PixelDrain"
        private val SERVER_LIST = arrayOf(
            "PixelDrain",
            "StreamWish",
            "Voe",
            "YourUpload",
            "FileLions",
            "StreamHideVid",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = doc.selectFirst("h1.line-clamp-2")?.text()?.trim() ?: ""
            description = doc.selectFirst(".entry > p")?.text()
            genre = doc.select("header > .items-center > a").joinToString { it.text() }
            thumbnail_url = doc.selectFirst("img.object-cover")?.attr("src")
        }
        doc.select("header > .items-center.text-sm span").eachText().forEach {
            when {
                it.contains("Finalizado") -> animeDetails.status = SAnime.COMPLETED
                it.contains("En emisión") -> animeDetails.status = SAnime.ONGOING
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/catalogo?order=popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article[class*=\"group/item\"]")
        val nextPage = document.select(".pointer-events-none:not([class*=\"max-sm:hidden\"]) ~ a").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")?.attr("abs:href").orEmpty())
                title = element.select("header h3").text()
                thumbnail_url = element.selectFirst(".bg-current img")?.attr("abs:src")
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/catalogo?order=latest_released&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isNotBlank() -> GET("$baseUrl/catalogo?search=$query&page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val script = doc.selectFirst("script:containsData(node_ids)")?.data().orEmpty()
        val episodeListRegex = """episodes\s*:\s*\[([^\]]*)\]""".toRegex()
        val episodeRegex = """\{\s*id\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*,\s*number\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*\}""".toRegex()
        val episodes = episodeListRegex.find(script)?.let {
            episodeRegex.findAll(it.groupValues[1]).map { match ->
                val number = match.groupValues[2]
                SEpisode.create().apply {
                    name = "Episodio $number"
                    episode_number = number.toFloatOrNull() ?: 0F
                    setUrlWithoutDomain("${doc.location()}/$number")
                }
            }.toList()
        }.orEmpty()

        return episodes.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val script = doc.selectFirst("script:containsData(node_ids)")?.data() ?: return emptyList()

        val jsonRegex = Regex("""\{\s*server\s*:\s*"([^"]*)"\s*,\s*url\s*:\s*"([^"]*)"\s*\}""")
        val subRegex = Regex("""SUB\s*:\s*\[([^\]]*)\]""")
        val dubRegex = Regex("""DUB\s*:\s*\[([^\]]*)\]""")

        fun processMatches(regex: Regex, type: String): List<Video> {
            return regex.findAll(script)
                .flatMap { jsonRegex.findAll(it.groupValues[1]) }
                .map { it.groupValues[2].substringBefore("?embed") }
                .distinct().toList()
                .parallelCatchingFlatMapBlocking { url ->
                    serverVideoResolver(url, type)
                }
        }

        processMatches(dubRegex, "DUB").also(videoList::addAll)
        processMatches(subRegex, "SUB").also(videoList::addAll)

        return videoList
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    fun serverVideoResolver(url: String, prefix: String = "", serverName: String? = ""): List<Video> {
        return runCatching {
            val source = serverName?.ifEmpty { url } ?: url
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in source.lowercase() } }?.first
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "pixeldrain" -> pixelDrainExtractor.videosFromUrl(url, "$prefix ")
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")
                else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
            }
        }.getOrNull() ?: emptyList()
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "mp4upload" to listOf("mp4upload"),
        "pixeldrain" to listOf("pixeldrain"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "streamlare" to listOf("streamlare", "slmaxed"),
        "yourupload" to listOf("yourupload", "upload"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(langPref, true) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
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
            key = PREF_LANG_KEY
            title = "Preferred Language"
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
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
