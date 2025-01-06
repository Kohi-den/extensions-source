package eu.kanade.tachiyomi.animeextension.all.javgg

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
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Javgg : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "JavGG"

    override val baseUrl = "https://javgg.net"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "Voe",
            "Okru",
            "YourUpload",
            "FileLions",
            "StreamHideVid",
            "TurboPlay",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            status = SAnime.COMPLETED
            description = document.selectFirst("#cover")?.text()
        }

        document.select(".data .boxye2").forEach { element ->
            val category = element.select("[id*=owye2]").text().trim()
            val tags = element.select(".sgeneros3 a").joinToString { it.text() }
            when {
                category.contains("Genres:") -> animeDetails.genre = tags
                category.contains("Cast:") -> animeDetails.artist = tags
                category.contains("Maker:") -> animeDetails.author = tags
            }
        }

        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article[id*=post-]")
        val nextPage = document.select("#nextpagination").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.selectFirst(".data h3")!!.text()
                thumbnail_url = element.selectFirst(".poster")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/new-post/page/$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isNotBlank() -> GET("$baseUrl/jav/page/$page?s=$query", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".result-item article")
        val nextPage = document.select("#nextpagination").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.selectFirst(".details .title")!!.text()
                thumbnail_url = element.selectFirst(".image")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.select(".dooplay_player_option").any()) {
            listOf(
                SEpisode.create().apply {
                    name = "Episode 1"
                    episode_number = 1F
                    setUrlWithoutDomain(document.location())
                },
            )
        } else {
            emptyList()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("[id*=source-player] iframe").parallelCatchingFlatMapBlocking {
            val numOpt = it.closest(".source-box")?.attr("id")?.replace("source-player-", "")
            val serverName = document.select("[data-nume=\"$numOpt\"] .server").text()
            serverVideoResolver(serverName, it.attr("src"))
        }
    }

    private fun serverVideoResolver(server: String, url: String): List<Video> {
        val embedUrl = server.lowercase()
        return when {
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> OkruExtractor(client).videosFromUrl(url)
            embedUrl.contains("filelions") || embedUrl.contains("lion") -> StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") -> {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            }
            embedUrl.contains("vidhide") || embedUrl.contains("streamhide") ||
                embedUrl.contains("guccihide") || embedUrl.contains("streamvid") -> StreamHideVidExtractor(client).videosFromUrl(url)
            embedUrl.contains("voe") -> VoeExtractor(client).videosFromUrl(url)
            embedUrl.contains("yourupload") || embedUrl.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            embedUrl.contains("turboplay") -> {
                val turboDocument = client.newCall(GET(url)).execute().asJsoup()
                val masterUrl = turboDocument.select("#video_player").attr("data-hash")
                val customHeaders = headers.newBuilder().apply {
                    add("Accept", "*/*")
                    add("Origin", "https://${turboDocument.location().toHttpUrl().host}")
                    add("Referer", "https://${turboDocument.location().toHttpUrl().host}/")
                }.build()

                listOf(Video(masterUrl, "TurboPlay", masterUrl, customHeaders))
            }
            else -> emptyList()
        }
    }

    private fun org.jsoup.nodes.Element.getImageUrl(): String? {
        val imageLinkRegex = """https?://[^\s]+\.(jpg|png)""".toRegex()

        for (link in this.select("[href], [src]")) {
            val href = link.attr("href")
            val src = link.attr("src")
            if (imageLinkRegex.matches(href)) {
                return href
            }
            if (imageLinkRegex.matches(src)) {
                return src
            }
        }

        val textMatches = imageLinkRegex.find(this.text())
        val htmlMatches = imageLinkRegex.find(this.outerHtml())
        return textMatches?.value ?: htmlMatches?.value
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
