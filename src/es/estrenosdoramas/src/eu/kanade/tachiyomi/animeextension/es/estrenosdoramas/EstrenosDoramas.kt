package eu.kanade.tachiyomi.animeextension.es.estrenosdoramas

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
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EstrenosDoramas : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "EstrenosDoramas"

    override val baseUrl = "https://estrenosdoramas.es"

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
        return SAnime.create().apply {
            title = document.selectFirst(".entry-title")?.text()?.trim() ?: ""
            description = document.selectFirst(".mindesc")?.text()?.trim()
            genre = document.select(".genxed a").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".thumb img")?.attr("abs:src")
            document.select(".spe > span").map {
                val title = it.select("b").text()
                when {
                    title.contains("Estado") -> status = it.ownText().getStatus()
                    title.contains("Casts") -> artist = it.select("a").joinToString { it.text() }
                    title.contains("Network") -> author = it.select("a").joinToString { it.text() }
                }
            }
        }
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/temporadas/?page=$page&order=popular", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".listupd article a")
        val nextPage = document.select(".hpage .r, .pagination .next").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/temporadas/?page=$page&order=latest", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = EstrenosDoramasFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/temporadas/${params.getQuery()}&page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("#myList li a").mapIndexed { idx, it ->
            val title = it.select(".epl-title").text().trim()
            val epNumber = try {
                """(\d+(\.\d+)?)""".toRegex().find(title)?.groupValues?.get(1)?.toFloat() ?: (idx + 1f)
            } catch (_: Exception) { idx + 1f }

            SEpisode.create().apply {
                episode_number = epNumber
                name = title
                scanlator = it.select(".epl-sub span").joinToString { it.text() }
                setUrlWithoutDomain(it.attr("abs:href"))
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("[data-embed]").parallelCatchingFlatMapBlocking {
            val link = it.attr("data-embed")
            val realLink = fetchUrls(client.newCall(GET(link)).execute().networkResponse.toString()).firstOrNull()
            serverVideoResolver(realLink?.ifEmpty { link } ?: "")
        }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        return when {
            arrayOf("ok.ru", "okru").any(url) -> okruExtractor.videosFromUrl(url)
            arrayOf("filelions", "lion", "fviplions").any(url) -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            arrayOf("wishembed", "streamwish", "strwish", "wish").any(url) -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            arrayOf("vidhide", "streamhide", "guccihide", "streamvid").any(url) -> streamHideVidExtractor.videosFromUrl(url)
            arrayOf("voe", "robertordercharacter", "donaldlineelse").any(url) -> voeExtractor.videosFromUrl(url)
            arrayOf("yourupload", "upload").any(url) -> yourUploadExtractor.videoFromUrl(url, headers = headers)
            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url)
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

    override fun getFilterList(): AnimeFilterList = EstrenosDoramasFilters.FILTER_LIST

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    private fun String.getStatus(): Int {
        val status = this.trim()
        return when {
            status.contains("Ongoing") -> SAnime.ONGOING
            status.contains("Completed") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
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
