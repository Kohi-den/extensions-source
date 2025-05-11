package eu.kanade.tachiyomi.animeextension.en.aniwatch

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Element

class AniWatchtv : ZoroTheme(
    "en",
    "AniWatchtv",
    "https://aniwatchtv.to",
    hosterNames = listOf(
        "VidSrc",
        "MegaCloud",
    ),
) {
    override val id = 8051984946387208343L

    override val ajaxRoute = "/v2"

    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, preferences) }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recently-updated?page=$page", docHeaders)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply {
            url = url.substringBefore("?")
        }
    }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "VidSrc", "MegaCloud" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            else -> emptyList()
        }
    }
}
