package eu.kanade.tachiyomi.animeextension.es.veranimes

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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VerAnimes : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "VerAnimes"

    override val baseUrl = "https://wwv.veranimes.net"

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
            "StreamWish",
            "Voe",
            "Okru",
            "YourUpload",
            "FileLions",
            "StreamHideVid",
            "VidGuard",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".ti h1")?.text()?.trim() ?: ""
            description = document.selectFirst(".r .tx p")?.text()
            genre = document.select(".gn li a").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".info figure img")?.attr("abs:data-src")
            status = when {
                document.select(".em").any() -> SAnime.ONGOING
                document.select(".fi").any() -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            document.select(".info .u:not(.sp) > li").map { it.text() }.map { textContent ->
                when {
                    "Estudio" in textContent -> author = textContent.substringAfter("Estudio(s):").trim()
                    "Producido" in textContent -> artist = textContent.substringAfter("Producido por:").trim()
                }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes?orden=desc&pag=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article.li figure a")
        val nextPage = document.select(".pag li a[title*=Siguiente]").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img")!!.attr("abs:data-src")
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?estado=en-emision&orden=desc&pag=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = VerAnimesFilters.getSearchParameters(filters)

        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?buscar=$query&pag=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&pag=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val scriptEps = document.selectFirst("script:containsData(var eps =)")?.data() ?: return emptyList()
        val slug = document.select("*[data-sl]").attr("data-sl")
        return scriptEps.substringAfter("var eps = ").substringBefore(";").trim().parseAs<List<String>>().map {
            SEpisode.create().apply {
                episode_number = it.toFloat()
                name = "Episodio $it"
                setUrlWithoutDomain("/ver/$slug-$it")
            }
        }
    }

    private fun hex2a(hex: String): String {
        return StringBuilder(hex.length / 2).apply {
            for (i in hex.indices step 2) {
                val charCode = hex.substring(i, i + 2).toInt(16)
                append(charCode.toChar())
            }
        }.toString()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val opt = document.select(".opt").attr("data-encrypt")

        val mediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        val body = "acc=opt&i=$opt".toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://wwv.veranimes.net/process")
            .post(body)
            .addHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .addHeader("referer", document.location())
            .addHeader("x-requested-with", "XMLHttpRequest")
            .build()

        val serversDocument = client.newCall(request).execute().asJsoup()

        return serversDocument.select("li").parallelCatchingFlatMapBlocking {
            val link = hex2a(it.attr("encrypt"))
            serverVideoResolver(link)
        }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        return when {
            arrayOf("ok.ru", "okru").any(url) -> okruExtractor.videosFromUrl(url)
            arrayOf("filelions", "lion", "fviplions").any(url) -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            arrayOf("wishembed", "streamwish", "strwish", "wish").any(url) -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            arrayOf("vidhide", "streamhide", "guccihide", "streamvid").any(url) -> streamHideVidExtractor.videosFromUrl(url)
            arrayOf("voe").any(url) -> voeExtractor.videosFromUrl(url)
            arrayOf("yourupload", "upload").any(url) -> yourUploadExtractor.videoFromUrl(url, headers = headers)
            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url)
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

    override fun getFilterList(): AnimeFilterList = VerAnimesFilters.FILTER_LIST

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

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
