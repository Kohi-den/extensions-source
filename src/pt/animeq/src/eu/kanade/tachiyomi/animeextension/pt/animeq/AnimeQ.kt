package eu.kanade.tachiyomi.animeextension.pt.animeq

import android.util.Log
import eu.kanade.tachiyomi.animeextension.pt.animeq.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.collections.emptyList

class AnimeQ : DooPlay(
    "pt-BR",
    "AnimeQ",
    "https://animeq.net",
) {
    private val tag by lazy { javaClass.simpleName }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.w_item_a > a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime/one-piece/", headers)

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            description = doc.getDescription()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    override val prefQualityValues = arrayOf("360p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val m3u8Integration by lazy { M3u8Integration(client) }

    private fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text()
            .run {
                when (this.uppercase()) {
                    "SD" -> "360p"
                    "HD" -> "720p"
                    "SD/HD" -> "720p"
                    "FHD", "FULLHD" -> "1080p"
                    else -> this
                }
            }

        val url = getPlayerUrl(player)

        var videos = when {
            "jwplayer/?source=" in url -> {
                val videoUrl = url.toHttpUrl().queryParameter("source") ?: return emptyList()

                return listOf(
                    Video(videoUrl, name, videoUrl, headers),
                )
            }

            else -> emptyList<Video>()
        }

        if (videos.isEmpty()) {
            Log.d(tag, "Videos are empty, fetching videos from using universal extractor: $url")
            val newHeaders = headers.newBuilder().set("Referer", baseUrl).build()
            videos = universalExtractor.videosFromUrl(url, newHeaders, name)
            // Process M3U8 videos through local server (automatic detection)
            return runBlocking { m3u8Integration.processVideoList(videos) }
        }

        return videos
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .execute()
            .let { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }

    // ============================= Utilities ==============================
    private fun Element.tryGetAttr(vararg attributeKeys: String): String? {
        val attributeKey = attributeKeys.firstOrNull { hasAttr(it) }
        return attributeKey?.let { attr(attributeKey) }
    }
}
