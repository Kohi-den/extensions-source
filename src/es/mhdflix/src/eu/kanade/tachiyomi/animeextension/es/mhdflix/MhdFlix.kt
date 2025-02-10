package eu.kanade.tachiyomi.animeextension.es.mhdflix

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
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class MhdFlix : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "MhdFlix"

    override val baseUrl = "https://ww2.mhdflix.com"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]", "[VOSE]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "VidHide",
            "Voe",
            "Uqload",
            "StreamTape",
            "Doodstream",
            "MixDrop",
            "filelions",
        )
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/movies/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("#movies-a li[id*=post-]")
        val nextPage = document.select(".pagination .nav-links .current ~ a:not(.page-link)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("article > a")?.attr("abs:href") ?: "")
                title = element.selectFirst("article .entry-header .entry-title")?.text() ?: ""
                thumbnail_url = element.selectFirst("article .post-thumbnail figure img")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    protected open fun Element.getImageUrl(): String? {
        return if (hasAttr("srcset")) {
            try {
                fetchUrls(attr("abs:srcset")).maxOrNull()
            } catch (_: Exception) {
                attr("abs:src")
            }
        } else {
            attr("abs:src")
        }
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = MhdFlixFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query&page=$page")
            params.path.isNotEmpty() -> GET("$baseUrl${params.getFullUrl()}/page/$page/${params.query()}")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".alg-cr .entry-header .entry-title")?.text() ?: ""
            description = document.select(".alg-cr .description").text()
            thumbnail_url = document.selectFirst(".alg-cr .post-thumbnail img")?.getImageUrl()
            genre = document.select(".genres a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }

        document.select(".cast-lst li").map {
            if (it.select("span").text().contains("Director", true)) {
                animeDetails.author = it.selectFirst("p > a")?.text()
            }
            if (it.select("span").text().contains("Actores", true)) {
                animeDetails.artist = it.selectFirst("p > a")?.text()
            }
        }
        return animeDetails
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string())
        val referer = response.request.url.toString()
        val allEpisodes = mutableListOf<SEpisode>()

        val episodeElements = document.select("section.episodes ul#episode_by_temp li")
        if (episodeElements.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString())
                    name = "Película"
                    episode_number = 1F
                },
            )
        }

        val seasonElements = document.select("ul.aa-cnt.sub-menu li.sel-temp a")
            .sortedBy { it.attr("data-season") }

        for (season in seasonElements) {
            val post = season.attr("data-post")
            val seasonNumber = season.attr("data-season")

            val formBody = FormBody.Builder()
                .add("action", "action_select_season")
                .add("season", seasonNumber)
                .add("post", post)
                .build()

            val request = Request.Builder()
                .url("https://ww2.mhdflix.com/wp-admin/admin-ajax.php")
                .post(formBody)
                .header("Origin", baseUrl)
                .header("Referer", referer)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val detailResponse = client.newCall(request).execute()

            if (detailResponse.isSuccessful) {
                val detailDocument = Jsoup.parse(detailResponse.body.string())
                val episodeElements = detailDocument.select("article.post.episodes")

                episodeElements.forEach { element ->
                    println(element) // Imprime el HTML del elemento

                    val episode = SEpisode.create().apply {
                        val url = element.selectFirst("a.lnk-blk")?.attr("href") ?: ""
                        val headerElement = element.selectFirst("header.entry-header")
                        val title = headerElement?.selectFirst("h2.entry-title")?.text() ?: "Episodio Desconocido"
                        val episodeNumber = headerElement?.selectFirst("span.num-epi")?.text()?.substringAfter("x")?.toFloatOrNull() ?: 0F
                        Log.d("MhdFlix", "Número de episodio: $episodeNumber")
                        setUrlWithoutDomain(url)
                        name = "- $title"
                        episode_number = episodeNumber
                    }
                    allEpisodes.add(episode)
                }
            } else {
                Log.e("MhdFlix", "Error en la solicitud de detalles de la temporada: ${detailResponse.code}")
            }
        }

        return allEpisodes.sortedByDescending { it.name }
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val title = document.title()
        val seasonEpisodeRegex = Regex("""(\d+)x(\d+)""")
        val seasonEpisodeMatch = seasonEpisodeRegex.find(title)

        val season = seasonEpisodeMatch?.groups?.get(1)?.value?.toIntOrNull()
        val episode = seasonEpisodeMatch?.groups?.get(2)?.value?.toIntOrNull()

        document.select(".video-player iframe").forEach { iframe ->
            try {
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                val idRegex = Regex("""\/e\/(\d+)""")
                val matchResult = idRegex.find(src)
                val videoId = matchResult?.groupValues?.get(1) ?: return@forEach

                val videoUrl = if (season != null && episode != null) {
                    "$baseUrl/wp-json/enlace/v1/e?id=$videoId&season=$season&episode=$episode"
                } else {
                    "$baseUrl/wp-json/enlace/v1/e?id=$videoId"
                }

                val videoResponse = client.newCall(GET(videoUrl)).execute()
                val jsonResponse = videoResponse.body.string()
                val jsonArray = JSONArray(jsonResponse)
                for (i in 0 until jsonArray.length()) {
                    val videoObj = jsonArray.getJSONObject(i)
                    val videoLink = videoObj.getString("url")
                    val type = when (videoObj.getString("tipo")) {
                        "Sub lat" -> "SUB"
                        "Castellano" -> "CAST"
                        "Latino" -> "LAT"
                        else -> videoObj.getString("tipo")
                    }

                    serverVideoResolver(videoLink, type).forEach { videoList.add(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return videoList.sort()
    }

    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val embedUrl = url.lowercase()
        Log.d("MhdFlix", "URL: $url")
        return when {
            embedUrl.contains("vidhide") -> {
                vidHideExtractor.videosFromUrl(url, videoNameGen = { "[$prefix] - VidHide: $it " })
            }
            embedUrl.contains("voe") -> {
                voeExtractor.videosFromUrl(url, prefix = "[$prefix] - ")
            }
            embedUrl.contains("uqload") -> {
                uqloadExtractor.videosFromUrl(url, prefix = "[$prefix] - ")
            }
            embedUrl.contains("streamtape") -> {
                streamTapeExtractor.videosFromUrl(url, quality = "[$prefix] - StreamTape")
            }
            embedUrl.contains("dood") -> {
                doodExtractor.videosFromUrl(url, quality = "[$prefix] - Doodstream")
            }
            embedUrl.contains("streamwish") -> {
                streamWishExtractor.videosFromUrl(url, prefix = "[$prefix] - StreamWish")
            }
            embedUrl.contains("mixdrop") -> {
                mixDropExtractor.videosFromUrl(url, lang = "[$prefix] - ")
            }
            embedUrl.contains("filelions") -> {
                universalExtractor.videosFromUrl(url, headers, prefix = "[$prefix] - ")
            }
            else -> emptyList()
        }
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

    override fun getFilterList(): AnimeFilterList = MhdFlixFilters.FILTER_LIST

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
}
