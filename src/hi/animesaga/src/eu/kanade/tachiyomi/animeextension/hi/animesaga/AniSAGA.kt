package eu.kanade.tachiyomi.animeextension.hi.animesaga

import eu.kanade.tachiyomi.animeextension.hi.animesaga.extractors.PlyrXExtractor
import eu.kanade.tachiyomi.animesource.model.SubtitleFile
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Element

class AniSAGA : DooPlay(
    "hi",
    "AniSAGA",
    "https://www.anisaga.org",
) {
    private val videoHost = "plyrxcdn.site"

    private val chillxExtractor by lazy { ChillxExtractor(client, headers) }
    private val plyrXExtractor by lazy { PlyrXExtractor(network, headers) }

    override fun popularAnimeSelector() = "div.top-imdb-list > div.top-imdb-item"

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val playerUrls = doc.select("ul#playeroptionsul li:not([id=player-option-trailer])")
            .map(::getPlayerUrl)

        val videoList = mutableListOf<Video>()
        playerUrls.forEach { url ->
            val videos = getPlayerVideos(url) {
                subtitleList.add(it)
            }
            videoList += videos
        }
        return videoList
    }

    private val subtitleList = mutableListOf<SubtitleFile>()

    override fun subtitleListParse(response: Response): List<SubtitleFile> = subtitleList

    private fun getPlayerVideos(url: String, subtitleCallback: (SubtitleFile) -> Unit = {}): List<Video> {
        return when {
            videoHost in url -> plyrXExtractor.videosFromUrl(url, baseUrl, subtitleCallback)
            else -> chillxExtractor.videoFromUrl(url, "$baseUrl/") // fallback
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .body!!.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }
}
