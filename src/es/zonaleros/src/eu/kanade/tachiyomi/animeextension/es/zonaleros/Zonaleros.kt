package eu.kanade.tachiyomi.animeextension.es.zonaleros

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Zonaleros : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Zonaleros"

    override val baseUrl = "https://www.zona-leros.com"

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
        private const val PREF_SERVER_DEFAULT = "DoodStream"
        private val SERVER_LIST = arrayOf(
            "Voe",
            "VidHide",
            "StreamWish",
            "Okru",
            "Upload",
            "FileLions",
            "Filemoon",
            "DoodStream",
            "MixDrop",
            "Streamtape",
            "Mp4Upload",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst("h1.Title")?.text() ?: ""
            description = document.selectFirst(".Main section")
                ?.select(".Description p")?.drop(1)
                ?.dropLast(1)?.joinToString(" ") { it.text() }
            genre = document.select(".TxtMAY ul li").joinToString { it.text() }
            status = when {
                document.location().contains("peliculas") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            artist = document.selectFirst(".ListActors li a")?.text()
            document.select(".TxtMAY").map {
                when {
                    it.text().contains("PRODUCTORA") || it.text().contains("CREADOR") -> author = it.nextElementSibling()?.text()
                }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series-h?order=views&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".ListAnimes .Anime > a")
        val nextPage = document.select(".pagination [rel=\"next\"]").any()

        val animeList = elements.filter { element ->
            element.attr("href").contains("series", ignoreCase = true) ||
                element.attr("href").contains("peliculas", ignoreCase = true)
        }.map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".Title")!!.text()
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/peliculas-hd-online-lat?order=published&page=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = ZonalerosFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/search?q=$query&page=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/${params.getQuery().replaceFirst("&", "?")}&page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.location().contains("peliculas")) {
            listOf(
                SEpisode.create().apply {
                    name = "Película"
                    episode_number = 1f
                    setUrlWithoutDomain(document.location())
                },
            )
        } else {
            document.select("[id*=temp]").flatMap { season ->
                season.select(".ListEpisodios a").reversed().map {
                    val capText = it.select(".Capi").text()
                    val seasonNumber = capText.substringBefore("x").trim()
                    val episodeNumber = capText.substringAfter("x").trim()
                    SEpisode.create().apply {
                        name = "T$seasonNumber - Episodio $episodeNumber"
                        episode_number = episodeNumber.toFloat()
                        setUrlWithoutDomain(it.attr("abs:href"))
                    }
                }
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverDocument = if (document.location().contains("/episode/")) {
            document
        } else {
            val token = document.select("meta[name=\"csrf-token\"]").attr("content")
            val calidadId = document.selectFirst("span[data-value]")?.attr("data-value") ?: return emptyList()
            val referer = document.location()

            val formBody = FormBody.Builder()
                .add("calidad_id", calidadId)
                .add("_token", token)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/calidades")
                .post(formBody)
                .header("accept", "application/json, text/javascript, */*; q=0.01")
                .header("accept-language", "es-419,es;q=0.8")
                .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("origin", baseUrl)
                .header("referer", referer)
                .header("x-requested-with", "XMLHttpRequest")
                .build()

            client.newCall(request).execute().asJsoup()
        }

        val scriptServerList = serverDocument.selectFirst("script:containsData(var video)")?.data() ?: return emptyList()

        val videoList = mutableListOf<Video>()
        fetchUrls(scriptServerList).filter { it.contains("anomizador", true) }.forEach {
            try {
                val realUrl = client.newCall(GET(it)).execute()
                    .networkResponse.toString()
                    .substringAfter("url=")
                    .substringBefore("}")
                serverVideoResolver(realUrl).also(videoList::addAll)
            } catch (e: Exception) {
                Log.i("bruh e", e.toString())
            }
        }
        return videoList
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun getFilterList(): AnimeFilterList = ZonalerosFilters.FILTER_LIST

    private val voeExtractor by lazy { VoeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url)
            embedUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> okruExtractor.videosFromUrl(url)
            embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") || embedUrl.contains("wishfast") -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape") -> streamTapeExtractor.videosFromUrl(url)
            embedUrl.contains("doodstream") || embedUrl.contains("dood.") || embedUrl.contains("ds2play") || embedUrl.contains("doods.") -> doodExtractor.videosFromUrl(url, "DoodStream", false)
            embedUrl.contains("filelions") || embedUrl.contains("lion") -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            embedUrl.contains("mp4upload") || embedUrl.contains("mp4") -> mp4uploadExtractor.videosFromUrl(url, headers)
            embedUrl.contains("vidhide") || embedUrl.contains("vid.") || embedUrl.contains("nika") -> vidHideExtractor.videosFromUrl(url) { "VidHide:$it" }
            embedUrl.contains("mix") -> mixdropExtractor.videosFromUrl(url)
            else -> emptyList()
        }
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
