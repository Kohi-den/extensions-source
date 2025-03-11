package eu.kanade.tachiyomi.animeextension.en.zoro

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Element

class HiAnime : ZoroTheme(
    "en",
    "HiAnime",
    "https://hianime.to",
    hosterNames = listOf(
        "HD-1",
        "HD-2",
        "StreamTape",
    ),
) {
    override val id = 6706411382606718900L

    override val ajaxRoute = "/v2"

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, preferences) }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recently-updated?page=$page", docHeaders)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply {
            url = url.substringBefore("?")
        }
    }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "StreamTape" -> {
                streamtapeExtractor.videoFromUrl(server.link, "Streamtape - ${server.type}")
                    ?.let(::listOf)
                    ?: emptyList()
            }
            "HD-1", "HD-2" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            else -> emptyList()
        }
    }
}
