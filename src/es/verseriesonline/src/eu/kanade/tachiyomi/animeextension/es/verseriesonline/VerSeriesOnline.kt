package eu.kanade.tachiyomi.animeextension.es.verseriesonline

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VerSeriesOnline : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "VerSeriesOnline"

    override val baseUrl = "https://www.verseriesonline.net"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/series-online/page/$page", headers)
    }

    override fun popularAnimeSelector(): String {
        return "div.short.gridder-list"
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a.short_img")!!.attr("href"))
        anime.title = element.selectFirst("div.short_title a")!!.text()
        val image = element.selectFirst("a.short_img img")!!.attr("data-src")
        anime.thumbnail_url = "$baseUrl/$image"
        return anime
    }

    override fun popularAnimeNextPageSelector(): String {
        return ".navigation a:last-of-type"
    }

    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/recherche?q=$id", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            val url = buildSearchUrl(query, page, filters)
            client.newCall(GET(url, headers)).awaitSuccess().use { response ->
                val document = response.asJsoup()
                val animeList = document.select(searchAnimeSelector()).map { element ->
                    searchAnimeFromElement(element)
                }
                val hasNextPage = searchAnimeNextPageSelector().let {
                    document.select(it).isNotEmpty()
                }

                AnimesPage(animeList, hasNextPage)
            }
        }
    }

    private fun buildSearchUrl(query: String, page: Int, filters: AnimeFilterList): String {
        val genreFilter = filters.find { it is GenreFilter } as? GenreFilter
        val yearFilter = filters.find { it is YearFilter } as? YearFilter
        val genre = genreFilter?.toUriPart() ?: ""
        val year = yearFilter?.toUriPart() ?: ""

        return if (query.isNotEmpty()) {
            "$baseUrl/recherche?q=$query&page=$page"
        } else if (year != "" && genre == "") {
            "$baseUrl/series-online/ano/$year/page/$page"
        } else {
            "$baseUrl/series-online/genero/$genre/page/$page"
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
            .apply {
                setUrlWithoutDomain(response.request.url.toString())
                initialized = true
            }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/recherche?q=$query&page=$page", headers)
    }

    override fun searchAnimeSelector(): String {
        return popularAnimeSelector()
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String {
        return popularAnimeNextPageSelector()
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.thumbnail_url = document.selectFirst("img.lazy-loaded")?.attr("data-src")
        anime.description = document.selectFirst("div.full_content-desc p span")?.text() ?: "Descripción no encontrada"
        anime.genre = document.select("ul#full_info li.vis span:contains(Genre:) + a")
            .joinToString(", ") { it.text() }
        anime.author = document.select("ul#full_info li.vis span:contains(Director:) + a").text()
        anime.status = SAnime.UNKNOWN

        return anime
    }

    override fun episodeListSelector(): String {
        return "div.seasontab div.floats a.th-hover"
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException()
    }

    private fun seasonListSelector(): String {
        return "div.floats a"
    }

    private fun seasonEpisodesSelector(): String {
        return "#dle-content > article > div > div:nth-child(3) > div > div > a"
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select(seasonListSelector()).forEach { seasonElement ->
            val seasonUrl = seasonElement.attr("href")
            val seasonNumber = Regex("temporada-(\\d+)").find(seasonUrl)?.groups?.get(1)?.value?.toIntOrNull() ?: 1
            val seasonDocument = client.newCall(GET(seasonUrl)).execute().asJsoup()

            seasonDocument.select(seasonEpisodesSelector()).forEach { episodeElement ->
                val episode = SEpisode.create()
                val episodeUrl = episodeElement.attr("href")
                episode.setUrlWithoutDomain(episodeUrl)
                val episodeName = episodeElement.selectFirst("span.name")?.text()?.trim() ?: "Episodio desconocido"
                episode.name = "Temporada $seasonNumber - $episodeName"
                val episodeNumber = Regex("Capítulo (\\d+)").find(episodeName)?.groups?.get(1)?.value?.toFloatOrNull() ?: 0F
                episode.episode_number = episodeNumber
                episodeList.add(episode)
            }
        }

        return episodeList
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val csrfToken = document.select("meta[name=csrf-token]")
            .firstOrNull()
            ?.attr("content")
            ?: return videoList

        val cookies = response.headers("Set-Cookie")
            .mapNotNull { Cookie.parse(response.request.url, it) }

        val cookieBuilder = StringBuilder()
        cookies.forEach { cookie ->
            cookieBuilder.append("${cookie.name}=${cookie.value}; ")
        }
        val cookieString = cookieBuilder.toString().trimEnd(' ', ';')

        document.select(".undervideo .player-list li").forEach { liElement ->
            try {
                liElement.selectFirst("div.lien")?.let { div ->
                    val dataHash = div.attr("data-hash")
                    val serverText = div.selectFirst(".serv")?.text() ?: "Unknown Server"

                    val language = when {
                        div.select("img[src*=lat]").isNotEmpty() ||
                            serverText.contains("latino", ignoreCase = true) -> "Latino"

                        div.select("img[src*=esp]").isNotEmpty() ||
                            serverText.contains("castellano", ignoreCase = true) ||
                            serverText.contains("español", ignoreCase = true) -> "Castellano"

                        div.select("img[src*=subesp]").isNotEmpty() ||
                            serverText.contains("subtitulado", ignoreCase = true) ||
                            serverText.contains("sub", ignoreCase = true) ||
                            serverText.contains("vose", ignoreCase = true) -> "VOSE"

                        else -> "Unknown"
                    }

                    if (dataHash.isNotEmpty()) {
                        val request = Request.Builder()
                            .url("https://www.verseriesonline.net/hashembedlink")
                            .post(
                                FormBody.Builder()
                                    .add("hash", dataHash)
                                    .add("_token", csrfToken)
                                    .build(),
                            )
                            .addHeader("X-Requested-With", "XMLHttpRequest")
                            .addHeader("X-CSRF-TOKEN", csrfToken)
                            .addHeader("Cookie", cookieString)
                            .addHeader("Referer", response.request.url.toString())
                            .addHeader("Accept", "application/json, text/plain, */*")
                            .addHeader("Accept-Language", "es-ES,es;q=0.9")
                            .addHeader("Origin", "https://www.verseriesonline.net")
                            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                            .addHeader("sec-ch-ua", "\"Google Chrome\";v=\"119\", \"Chromium\";v=\"119\", \"Not?A_Brand\";v=\"24\"")
                            .addHeader("sec-ch-ua-mobile", "?0")
                            .addHeader("sec-ch-ua-platform", "\"Windows\"")
                            .addHeader("Sec-Fetch-Dest", "empty")
                            .addHeader("Sec-Fetch-Mode", "cors")
                            .addHeader("Sec-Fetch-Site", "same-origin")
                            .build()

                        Thread.sleep(1000)

                        client.newCall(request).execute().use { videoResponse ->
                            val responseBody = videoResponse.body.string()

                            if (!videoResponse.isSuccessful) {
                                return@let
                            }

                            val jsonResponse = JSONObject(responseBody)
                            val videoUrl = jsonResponse.optString("link")
                            if (videoUrl.isNotEmpty()) {
                                val extractedVideos = when {
                                    videoUrl.contains("dood") -> doodExtractor.videosFromUrl(videoUrl, quality = "$language - ")
                                    videoUrl.contains("dwish") -> streamwishExtractor.videosFromUrl(videoUrl, prefix = "$language - ")
                                    videoUrl.contains("streamtape") -> streamtapeExtractor.videosFromUrl(videoUrl, quality = "$language - StreamTape")
                                    videoUrl.contains("voe") -> voeExtractor.videosFromUrl(videoUrl, prefix = "$language - ")
                                    videoUrl.contains("uqload") -> uqloadExtractor.videosFromUrl(videoUrl, prefix = "$language - ")
                                    videoUrl.contains("vudeo") -> vudeoExtractor.videosFromUrl(videoUrl, prefix = "$language - ")
                                    else -> emptyList()
                                }

                                videoList.addAll(extractedVideos)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return videoList
    }

    override fun videoListSelector() = ".undervideo .player-list li"

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
        YearFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Drama", "drama"),
            Pair("Comedia", "comedia"),
            Pair("Animación", "animacion"),
            Pair("Sci-Fi", "scifi"),
            Pair("Fantasy", "fantasy"),
            Pair("Action", "accion"),
            Pair("Adventure", "aventura"),
            Pair("Crimen", "crimen"),
            Pair("Misterio", "misterio"),
            Pair("Documental", "documental"),
            Pair("Familia", "familia"),
            Pair("Kids", "kids"),
            Pair("Reality", "reality"),
            Pair("War", "war"),
            Pair("Politics", "politics"),
        ),
    )
    private class YearFilter : UriPartFilter(
        "Año",
        arrayOf(Pair("<Seleccionar>", "")) + (2024 downTo 1950).map { Pair(it.toString(), it.toString()) }.toTypedArray(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

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

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "Latino"
        private val LANGUAGE_LIST = arrayOf("Latino", "Castellano", "VOSE")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf("Voe", "StreamTape", "Uqload", "Vudeo", "StreamWish", "Dood")
    }
}
