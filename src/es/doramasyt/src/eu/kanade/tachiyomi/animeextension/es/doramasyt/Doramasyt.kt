package eu.kanade.tachiyomi.animeextension.es.doramasyt

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.ceil

class Doramasyt : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Doramasyt"

    override val baseUrl = "https://www.doramasyt.com"

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
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf(
            "Voe",
            "StreamWish",
            "Okru",
            "Upload",
            "FileLions",
            "Filemoon",
            "DoodStream",
            "MixDrop",
            "Streamtape",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".flex-column h1.text-capitalize")?.text() ?: ""
            description = document.selectFirst(".h-100 .mb-3 p")?.text()
            genre = document.select(".lh-lg span").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".gap-3 img")?.getImageUrl()
            status = document.select(".lh-sm .ms-2").eachText().let { items ->
                when {
                    items.any { it.contains("Finalizado") } -> SAnime.COMPLETED
                    items.any { it.contains("Estreno") } -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/doramas?p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".ficha_efecto a")
        val nextPage = document.select(".pagination [rel=\"next\"]").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".title_cap")!!.text()
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/emision?p=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DoramasytFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/doramas${params.getQuery()}&p=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val token = document.select("meta[name='csrf-token']").attr("content")
        val capListLink = document.select(".caplist").attr("data-ajax")
        val referer = document.location()

        val detail = getEpisodeDetails(capListLink, token, referer)
        val total = detail.eps.size
        val perPage = detail.perpage ?: return emptyList()
        val pages = (total / perPage).ceilPage()

        return (1..pages).parallelCatchingFlatMapBlocking {
            getEpisodePage(detail.paginateUrl ?: "", it, token, referer).caps.mapIndexed { idx, ep ->
                val episodeNumber = (ep.episodio ?: (idx + 1))
                SEpisode.create().apply {
                    name = "Capítulo $episodeNumber"
                    episode_number = episodeNumber.toFloat()
                    setUrlWithoutDomain(ep.url ?: "")
                }
            }
        }.reversed()
    }

    private fun getEpisodeDetails(capListLink: String, token: String, referer: String): EpisodesDto {
        val formBody = FormBody.Builder().add("_token", token).build()
        val request = Request.Builder()
            .url(capListLink)
            .post(formBody)
            .header("accept", "application/json, text/javascript, */*; q=0.01")
            .header("accept-language", "es-419,es;q=0.8")
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("origin", baseUrl)
            .header("referer", referer)
            .header("x-requested-with", "XMLHttpRequest")
            .build()

        return client.newCall(request).execute().parseAs<EpisodesDto>()
    }

    private fun getEpisodePage(paginateUrl: String, page: Int, token: String, referer: String): EpisodeInfoDto {
        val formBodyEp = FormBody.Builder()
            .add("_token", token)
            .add("p", "$page")
            .build()
        val requestEp = Request.Builder()
            .url(paginateUrl)
            .post(formBodyEp)
            .header("accept", "application/json, text/javascript, */*; q=0.01")
            .header("accept-language", "es-419,es;q=0.8")
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("origin", baseUrl)
            .header("referer", referer)
            .header("x-requested-with", "XMLHttpRequest")
            .build()

        return client.newCall(requestEp).execute().parseAs<EpisodeInfoDto>()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("[data-player]")
            .map { String(Base64.decode(it.attr("data-player"), Base64.DEFAULT)) }
            .parallelCatchingFlatMapBlocking { serverVideoResolver(it) }
    }

    override fun getFilterList(): AnimeFilterList = DoramasytFilters.FILTER_LIST

    private val voeExtractor by lazy { VoeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url)
            embedUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> okruExtractor.videosFromUrl(url)
            embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") || embedUrl.contains("wishfast") -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape") -> streamTapeExtractor.videosFromUrl(url)
            embedUrl.contains("doodstream") || embedUrl.contains("dood.") || embedUrl.contains("ds2play") || embedUrl.contains("doods.") -> doodExtractor.videosFromUrl(url)
            embedUrl.contains("filelions") || embedUrl.contains("lion") -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            embedUrl.contains("mix") -> mixdropExtractor.videosFromUrl(url)
            else -> universalExtractor.videosFromUrl(url, headers)
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
        return !attr(attrName).contains("anime.png")
    }

    private fun Double.ceilPage(): Int = if (this % 1 == 0.0) this.toInt() else ceil(this).toInt()

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
