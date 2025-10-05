package eu.kanade.tachiyomi.animeextension.es.jkanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.jkanime.extractors.JkanimeExtractor
import eu.kanade.tachiyomi.animeextension.es.jkanime.models.EpisodeAnimeModel
import eu.kanade.tachiyomi.animeextension.es.jkanime.models.PopularAnimeModel
import eu.kanade.tachiyomi.animeextension.es.jkanime.models.ServerAnimeModel
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.text.lowercase

class Jkanime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val id = 6007374093317538838

    override val name = "Jkanime"

    override val baseUrl = "https://jkanime.net"

    override val lang = "es"

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private val noRedirectClient = network.client.newBuilder()
        .followRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.isRedirect) {
                val location = response.header("Location")
                    ?: return@addInterceptor response

                val originalParams = request.url.queryParameterNames
                    .associateWith { request.url.queryParameter(it) }

                val redirectUrl = location.toHttpUrl().newBuilder().apply {
                    originalParams.forEach { (key, value) ->
                        if (value != null) addQueryParameter(key, value)
                    }
                }.build()

                val newRequest = request.newBuilder()
                    .url(redirectUrl)
                    .build()

                response.close()

                return@addInterceptor chain.proceed(newRequest)
            }
            response
        }.build()

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.pathSegments.first() == "buscar") {
                return@addInterceptor noRedirectClient.newCall(request).execute()
            }
            chain.proceed(request)
        }.build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[JAP]"
        private val LANGUAGE_LIST = arrayOf("[JAP]", "[LAT]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "Okru",
            "Mixdrop",
            "StreamWish",
            "Filemoon",
            "Mp4Upload",
            "StreamTape",
            "Desuka",
            "Nozomi",
            "Desu",
        )

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            thumbnail_url = doc.selectFirst(".anime_info img")?.getImageUrl()
            title = doc.selectFirst(".anime_info h3")?.text()?.trim() ?: ""
            description = doc.selectFirst(".scroll")?.text()
            genre = doc.selectFirst("li:has(span:matchesOwn(^Generos:))")?.select("a")?.joinToString { it.text() }
            status = when {
                doc.select(".finished").isNotEmpty() -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }
        }
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/directorio?filtro=popularidad&p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script:containsData(var animes =)")?.data().orEmpty()
        val regex = Regex("""var animes\s*=\s*(\{.*\});""", RegexOption.DOT_MATCHES_ALL)
        val jsonString = regex.find(scriptData)?.groupValues?.get(1) ?: return AnimesPage(emptyList(), false)

        val json = json.decodeFromString<PopularAnimeModel>(jsonString)

        val hasNext = !json.nextPageUrl.isNullOrBlank()
        val animeList = json.data.map {
            SAnime.create().apply {
                title = it.title.orEmpty()
                thumbnail_url = it.image
                description = it.synopsis
                author = it.studios
                setUrlWithoutDomain(it.url.orEmpty())
            }
        }
        return AnimesPage(animeList, hasNext)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/directorio?estado=emision&p=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = JkanimeFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar/$query", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/directorio${params.getQuery().run { if (isNotBlank()) "$this&p=$page" else this }}", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        if (document.location().contains("directorio")) {
            val scriptData = document.selectFirst("script:containsData(var animes =)")?.data().orEmpty()
            val regex = Regex("""var animes\s*=\s*(\{.*\});""", RegexOption.DOT_MATCHES_ALL)
            val jsonString = regex.find(scriptData)?.groupValues?.get(1) ?: return AnimesPage(emptyList(), false)

            val json = json.decodeFromString<PopularAnimeModel>(jsonString)

            val hasNext = !json.nextPageUrl.isNullOrBlank()
            val animeList = json.data.map {
                SAnime.create().apply {
                    title = it.title.orEmpty()
                    thumbnail_url = it.image
                    description = it.synopsis
                    author = it.studios
                    setUrlWithoutDomain(it.url.orEmpty())
                }
            }
            return AnimesPage(animeList, hasNext)
        } else {
            val animeList = document.select(".anime__item").mapNotNull {
                SAnime.create().apply {
                    title = it.select("h5 a").text()
                    thumbnail_url = it.selectFirst(".set-bg")?.attr("abs:data-setbg")
                    setUrlWithoutDomain(it.select("a").attr("abs:href"))
                }
            }
            return AnimesPage(animeList, false)
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        try {
            val animeId = doc.selectFirst("[data-anime]")?.attr("data-anime").orEmpty()
            val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
            val episodePath = doc.selectFirst("[property=\"og:url\"]")?.attr("content")
            val xsrfToken = response.headers.filter { it.first == "set-cookie" }
            val referer = doc.location()
            var currentPage = 1
            var requestCount = 0
            var animePage = fetchAnimeEpisodes(referer, token, animeId, currentPage, xsrfToken)
            while (animePage != null) {
                animePage.data.forEach {
                    episodes.add(
                        SEpisode.create().apply {
                            episode_number = it.number?.toFloat() ?: 0F
                            name = "Episodio ${it.number}"
                            date_upload = it.timestamp?.toDate() ?: 0L
                            setUrlWithoutDomain("$episodePath${it.number}/")
                        },
                    )
                }

                // pause every 15 request
                requestCount++
                if (requestCount % 10 == 0) {
                    println("Esperando para evitar 429... ($requestCount requests realizadas)")
                    Thread.sleep(5000) // wait 5 seconds
                }

                currentPage++
                animePage = if (!animePage.nextPageUrl.isNullOrEmpty()) {
                    fetchAnimeEpisodes(referer, token, animeId, currentPage, xsrfToken)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.i("bruh getEpisodes", "Error: ${e.message}", e)
        }
        return episodes.reversed()
    }

    private fun fetchAnimeEpisodes(referer: String, token: String, animeId: String, page: Int, cookies: List<Pair<String, String>>): EpisodeAnimeModel? {
        return runCatching {
            val body = "_token=$token".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val newHeaders = mapOf("Cookie" to cookies.joinToString(" ") { "${it.second.substringBeforeLast(";")};" }) + mapOf("Referer" to referer)
            val response = client.newCall(POST("$baseUrl/ajax/episodes/$animeId/$page", body = body, headers = newHeaders.toHeaders())).execute()
            return json.decodeFromString<EpisodeAnimeModel>(response.body.string())
        }.getOrNull()
    }

    override fun getFilterList(): AnimeFilterList = JkanimeFilters.FILTER_LIST

    /*--------------------------------Video extractors------------------------------------*/
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val jkanimeExtractor by lazy { JkanimeExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val scriptData = doc.selectFirst("script:containsData(var servers)")?.data().orEmpty()
        val regex = Regex("""var servers\s*=\s*(\[.*\]);""", RegexOption.UNIX_LINES)
        val jsonString = regex.find(scriptData)?.groupValues?.get(1) ?: return emptyList()

        return json.decodeFromString<List<ServerAnimeModel>>(jsonString).parallelCatchingFlatMapBlocking {
            val url = String(Base64.decode(it.remote.orEmpty(), Base64.DEFAULT))
            val prefix = it.lang.getLang()
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "okru" -> okruExtractor.videosFromUrl(url, prefix)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
                "streamwish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                "mixdrop" -> mixDropExtractor.videoFromUrl(url, prefix = "$prefix ")
                "desuka" -> jkanimeExtractor.getDesukaFromUrl(url, "$prefix ")
                "nozomi" -> jkanimeExtractor.getNozomiFromUrl(url, "$prefix ")
                "desu" -> jkanimeExtractor.getDesuFromUrl(url, "$prefix ")
                else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
            }
        }
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "mp4upload" to listOf("mp4upload"),
        "mixdrop" to listOf("mixdrop", "mxdrop"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "desuka" to listOf("stream/jkmedia"),
        "nozomi" to listOf("um2.php", "nozomi"),
        "desu" to listOf("um.php"),
    )

    private val languages = arrayOf(
        Pair(1, "[JAP]"),
        Pair(3, "[LAT]"),
        Pair(4, "[CHIN]"),
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun Int?.getLang(): String = languages.firstOrNull { it.first == this }?.second ?: ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
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

    private fun Element.getImageUrl(): String? {
        return when {
            isValidUrl("data-src") -> attr("abs:data-src")
            isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
            isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
            isValidUrl("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("anime.png")
    }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
}
