package eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient

class PlayerFlixExtractor(
    private val client: OkHttpClient,
    private val defaultHeaders: Headers,
    private val genericExtractor: (String, String) -> List<Video>,
) {
    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, defaultHeaders)).execute().asJsoup()

        val items = doc.select("#hostList div.buttonLoadHost").mapNotNull {
            val url = it.attr("onclick")
                .substringAfter('"', "")
                .substringBefore('"')
                ?: return@mapNotNull null

            val language = if (it.hasClass("hostDub")) {
                "Dublado"
            } else {
                "Legendado"
            }

            language to url // (Language, videoId)
        }

        return items.parallelCatchingFlatMapBlocking { genericExtractor(it.second, it.first) }
    }
}
