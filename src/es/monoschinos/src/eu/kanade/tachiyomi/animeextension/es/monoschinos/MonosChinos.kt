package eu.kanade.tachiyomi.animeextension.es.monoschinos

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
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.ceil

class MonosChinos : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "MonosChinos"

    override val baseUrl = "https://monoschinos2.net"

    override val id = 6957694006954649296

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
            "Mp4Upload",
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
                    items.any { it.contains("En emision") || it.contains("Estreno") } -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes?pag=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".ficha_efecto a")
        val nextPage = document.select(".pagination [title=\"Siguiente página\"]").any()
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

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?estado=en+emision&pag=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = MonosChinosFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?buscar=$query&pag=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&pag=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val referer = document.location()
        val dt = document.select("#dt")

        val total = dt.attr("data-e").toInt()
        val perPage = 50.0
        val pages = (total / perPage).ceilPage()
        val i = dt.attr("data-i")
        val u = dt.attr("data-u")

        var pageIdx = 1
        return (1..pages).parallelCatchingFlatMapBlocking {
            val formBody = FormBody.Builder()
                .add("acc", "episodes")
                .add("i", i)
                .add("u", u)
                .add("p", pageIdx.toString())
                .build()

            val request = Request.Builder()
                .url("$baseUrl/ajax_pagination")
                .post(formBody)
                .header("accept", "application/json, text/javascript, */*; q=0.01")
                .header("accept-language", "es-419,es;q=0.8")
                .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("origin", baseUrl)
                .header("referer", referer)
                .header("x-requested-with", "XMLHttpRequest")
                .build()
            pageIdx++

            client.newCall(request).execute().getEpisodes()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val i = document.select(".opt").attr("data-encrypt")
        val referer = document.location()
        val formBody = FormBody.Builder()
            .add("acc", "opt")
            .add("i", i)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/ajax_pagination")
            .post(formBody)
            .header("accept", "application/json, text/javascript, */*; q=0.01")
            .header("accept-language", "es-419,es;q=0.8")
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("origin", baseUrl)
            .header("referer", referer)
            .header("x-requested-with", "XMLHttpRequest")
            .build()

        val serverDocument = client.newCall(request).execute().asJsoup()

        return serverDocument.select("[data-player]")
            .map { String(Base64.decode(it.attr("data-player"), Base64.DEFAULT)) }
            .parallelCatchingFlatMapBlocking { serverVideoResolver(it) }.sort()
    }

    override fun getFilterList(): AnimeFilterList = MonosChinosFilters.FILTER_LIST

    private val voeExtractor by lazy { VoeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
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
            embedUrl.contains("mp4upload") || embedUrl.contains("mp4") -> mp4uploadExtractor.videosFromUrl(url, headers)
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

    private fun Response.getEpisodes(): List<SEpisode> {
        val document = this.asJsoup()
        return document.select(".ko").mapIndexed { idx, it ->
            val episodeNumber = try {
                it.select("h2").text().substringAfter("Capítulo").trim().toFloat()
            } catch (e: Exception) { idx + 1f }

            SEpisode.create().apply {
                name = it.select(".fs-6").text()
                episode_number = episodeNumber
                setUrlWithoutDomain(it.attr("abs:href"))
            }
        }
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
