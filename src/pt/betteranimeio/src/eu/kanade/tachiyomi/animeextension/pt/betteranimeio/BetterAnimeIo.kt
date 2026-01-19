package eu.kanade.tachiyomi.animeextension.pt.betteranimeio

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class BetterAnimeIo : DooPlay(
    "pt-BR",
    "BetterAnimeIo",
    "https://betteranime.io",
) {
    private val contentUrl = "$baseUrl/animes"

    private val json: Json by injectLazy()

    private val extractor by lazy { BetterAnimeIoExtractor(client, json) }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#featured-titles article.item div.poster"

    override fun popularAnimeRequest(page: Int): Request = GET(contentUrl, headers)

    // =============================== Latest ===============================
    override fun latestUpdatesSelector() = "div#archive-content article.item div.poster"

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$contentUrl/page/$page", headers)

    // ============================== Episodes ==============================
    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.selectFirst("span.se-t")?.text() ?: "1"
        return season.select(episodeListSelector()).mapNotNull { element ->
            runCatching {
                episodeFromElement(element, seasonName)
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        return SEpisode.create().apply {
            val link = element.selectFirst(".episodiotitle a")!!
            val episodeText = link.text()
            val epNum = episodeText.substringBefore(" -").trim()

            episode_number = epNum.toFloatOrNull() ?: 0F
            name = "$episodeSeasonPrefix $seasonName x $epNum"
            setUrlWithoutDomain(link.attr("href"))

            element.selectFirst(".timeAgo[data-time]")?.attr("data-time")?.let { dateStr ->
                date_upload = parseIsoDate(dateStr)
            }
        }
    }

    private fun parseIsoDate(dateStr: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
                .parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ============================== Filters ===============================
    override fun genresListRequest(): Request = GET("$baseUrl/episodios/", headers)

    override fun genresListSelector() = "nav.genres ul.genres li a"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = getPlayerUrl(player)
        if (url.isEmpty()) return emptyList()

        return when {
            "jwplayer?source=" in url || "jwplayer/?source=" in url -> {
                val encodedSource = url.toHttpUrl().queryParameter("source") ?: return emptyList()
                extractor.extractVideosFromApi(encodedSource)
            }
            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return try {
            client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num", headers))
                .execute()
                .use { response ->
                    response.body.string()
                        .substringAfter("\"embed_url\":\"")
                        .substringBefore("\",")
                        .replace("\\", "")
                }
        } catch (e: Exception) {
            ""
        }
    }

    // ============================= Utilities ==============================
    override val prefQualityValues = arrayOf("360p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues
}
