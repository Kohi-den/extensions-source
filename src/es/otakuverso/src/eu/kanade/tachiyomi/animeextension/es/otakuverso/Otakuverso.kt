package eu.kanade.tachiyomi.animeextension.es.otakuverso

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.otakuverso.extractors.UnpackerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Otakuverso : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Otakuverso"

    override val baseUrl = "https://otakuverso.net"

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
        private const val PREF_SERVER_DEFAULT = "YourUpload"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape",
            "Fastream", "Filemoon", "StreamWish", "Okru",
            "Amazon", "AmazonES", "Fireload", "FileLions",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("#back_data_perfil .inn-text h1")?.text().orEmpty()
            description = document.selectFirst("#back_data_perfil .inn-text p.font14")?.ownText()
            genre = document.select(".pre .text-deco-none.font-GDSherpa-Regular").joinToString { it.text() }
            status = with(document.select("#back_data_perfil .inn-text .btn-anime-info")) {
                when {
                    text().contains("finalizado", true) -> SAnime.COMPLETED
                    text().contains("emision", true) || text().contains("emitiéndose", true) -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            }
            document.select(".col-xl-12 .font-GDSherpa-Regular")
                .map { it.select(".text-white").text() to it.select(".lila-color").text() }
                .forEach { (title, content) ->
                    when {
                        title.contains("Creador(a)", true) -> author = content
                        title.contains("Director(a)", true) -> artist = content
                    }
                }
        }
    }

    private fun getToken(): Pair<String, String> {
        try {
            val request = client.newCall(GET("$baseUrl/animes")).execute()
            val document = request.asJsoup()
            val token = document.selectFirst("[name=\"_token\"]")?.attr("value").orEmpty()
            val xsrfToken = client.cookieJar.loadForRequest("$baseUrl/animes".toHttpUrl())
                .firstOrNull { it.name == "XSRF-TOKEN" }?.let { "${it.name}=${it.value}" }
                .orEmpty()
            return token to xsrfToken
        } catch (e: Exception) {
            Log.i("bruh err", e.toString())
            return "" to ""
        }
    }

    override fun popularAnimeRequest(page: Int): Request {
        val (token, xsrfToken) = getToken()
        val data = FormBody.Builder()
            .add("_token", token)
            .add("page", "$page")
            .add("search_genero", "0")
            .add("search_anno", "0")
            .add("search_tipo", "0")
            .add("search_orden", "0")
            .add("search_estado", "0")
            .add("Cookie", xsrfToken)
            .build()

        return POST("$baseUrl/animes", body = data, headers = headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".row [data-original-title]")
        val nextPage = document.select(".pagination a[rel=next]").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".font-GDSherpa-Bold")?.text().orEmpty()
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = OtakuversoFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscador?q=$query&page=$page", headers)
            params.isFiltered() -> searchRequest(params, page)
            else -> popularAnimeRequest(page)
        }
    }

    private fun searchRequest(params: OtakuversoFilters.FilterSearchParams, page: Int): Request {
        val formBody = params.body
        val (token, xsrfToken) = getToken()
        val data = FormBody.Builder().apply {
            for (i in 0 until formBody.size) {
                add(formBody.name(i), formBody.value(i))
            }
            add("page", "$page")
            add("_token", token)
            add("Cookie", xsrfToken)
        }.build()

        return POST("$baseUrl/animes", body = data, headers = headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    private fun parseEpisodeList(document: Document): List<SEpisode> {
        return document.select(".pl-lg-4 .container-fluid .row .col-6.text-center").map {
            val episode = it.select(".font-GDSherpa-Bold a")
            val episodeNumber = episode.text().substringAfter("Episodio").trim().toFloat()
            SEpisode.create().apply {
                name = episode.text()
                episode_number = episodeNumber
                scanlator = it.select(".font14 .bog").text().trim()
                setUrlWithoutDomain(episode.attr("abs:href"))
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val pageable = document.select(".dropdown-menu").any()
        if (pageable) {
            return document.select(".dropdown-menu a")
                .map { it.attr("abs:href") }
                .parallelCatchingFlatMapBlocking {
                    val page = client.newCall(GET(it)).execute().asJsoup()
                    parseEpisodeList(page)
                }
        }
        return parseEpisodeList(document)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).await()
        val document = response.asJsoup()
        return document.select("#ssel option")
            .map { it.attr("value") }
            .parallelCatchingFlatMap { id ->
                val url = getRealLink(id)
                serverVideoResolver(url)
            }
    }

    private fun getRealLink(id: String): String {
        val serverResponse = client.newCall(GET("$baseUrl/play-video?id=$id")).execute()
        val serverLink = """"url":"([^"]+)""".toRegex()
            .find(serverResponse.body.string())
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")
            .orEmpty()
        return when {
            serverLink.startsWith("http") -> serverLink
            serverLink.startsWith("//") -> "https:$serverLink"
            else -> ""
        }
    }

    override fun getFilterList(): AnimeFilterList = OtakuversoFilters.FILTER_LIST

    /*-------------------------------- Video extractors ------------------------------------*/
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
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
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val luluExtractor by lazy { UnpackerExtractor(client, headers) }

    private fun serverVideoResolver(url: String): List<Video> {
        return when {
            arrayOf("voe", "robertordercharacter", "donaldlineelse").any(url) -> voeExtractor.videosFromUrl(url)
            arrayOf("ok.ru", "okru").any(url) -> okruExtractor.videosFromUrl(url)
            arrayOf("moon").any(url) -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            arrayOf("uqload").any(url) -> uqloadExtractor.videosFromUrl(url)
            arrayOf("mp4upload").any(url) -> mp4uploadExtractor.videosFromUrl(url, headers)
            arrayOf("wish").any(url) -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            arrayOf("doodstream", "dood.").any(url) -> doodExtractor.videosFromUrl(url, "DoodStream")
            arrayOf("streamlare").any(url) -> streamlareExtractor.videosFromUrl(url)
            arrayOf("yourupload", "upload").any(url) -> yourUploadExtractor.videoFromUrl(url, headers = headers)
            arrayOf("burstcloud", "burst").any(url) -> burstcloudExtractor.videoFromUrl(url, headers = headers)
            arrayOf("upstream").any(url) -> upstreamExtractor.videosFromUrl(url)
            arrayOf("streamtape", "stp", "stape").any(url) -> streamTapeExtractor.videosFromUrl(url)
            arrayOf("ahvsh", "streamhide").any(url) -> streamHideVidExtractor.videosFromUrl(url)
            arrayOf("/stream/fl.php").any(url) -> {
                val video = url.substringAfter("/stream/fl.php?v=")
                if (client.newCall(GET(video)).execute().code == 200) {
                    listOf(Video(video, "FireLoad", video))
                } else {
                    emptyList()
                }
            }
            arrayOf("lion").any(url) -> filelionsExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            arrayOf("sendvid").any(url) -> sendvidExtractor.videosFromUrl(url)
            arrayOf("lulu").any(url) -> luluExtractor.videosFromUrl(url)
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
        return !attr(attrName).contains("data:image/")
    }

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
