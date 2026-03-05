package eu.kanade.tachiyomi.animeextension.pt.animeito

import eu.kanade.tachiyomi.animeextension.pt.animeito.extractors.AnimeItoExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import org.jsoup.nodes.Element

class AnimeIto : AnimeStream(
    "pt-BR",
    "AnimeIto",
    "https://animei.to",
) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    override val prefQualityEntries = prefQualityValues

    // ============================ Video Links =============================

    override fun videoListSelector() = "ul.tabs_videos li"

    override fun getHosterUrl(element: Element): String {
        val encodedData = element.attr("value")

        return getHosterUrl(encodedData)
    }

    private val animeitoExtractor by lazy { AnimeItoExtractor(client, headers) }
    override fun getVideoList(url: String, name: String): List<Video> {
        return when {
            "anidrive.click" in url -> animeitoExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }
}
