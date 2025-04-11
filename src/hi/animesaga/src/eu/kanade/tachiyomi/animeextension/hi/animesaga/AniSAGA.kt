package eu.kanade.tachiyomi.animeextension.hi.anisaga

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.SubtitleFile
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Element
import eu.kanade.tachiyomi.animeextension.hi.anisaga.extractors.PlyrXExtractor

class AniSAGA : DooPlay(
    "hi",
    "AniSAGA",
    "https://www.anisaga.org",
) {
    private val videoHost = "https://plyrxcdn.site/"
    private val chillxExtractor by lazy { ChillxExtractor(client, headers) }
    private val plyrXExtractor by lazy { PlyrXExtractor(client) }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.top-imdb-list > div.top-imdb-item"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val players = document.select("ul#playeroptionsul li:not([id=player-option-trailer])")
        val videoList = mutableListOf<Video>()

        players.forEach { player ->
            val url = getPlayerUrl(player)

            val videos = when {
                videoHost in url -> plyrXExtractor.videosFromUrl(url, baseUrl, subtitleCallback)
                else -> chillxExtractor.videoFromUrl(url, baseUrl)
            }

            videoList.addAll(videos)
        }

        return videoList
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        val response = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .body.string()

        return response.substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    // Needed for subtitles
    private var subtitleCallback: (SubtitleFile) -> Unit = {}

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        // You can add custom settings here if needed
    }

    override fun setVideoLoadListener(subtitleCb: (SubtitleFile) -> Unit) {
        subtitleCallback = subtitleCb
    }
}
