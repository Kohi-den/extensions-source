package eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class StrmupExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client) }
    fun videosFromUrl(url: String, name: String = "NOA"): List<Video> {
        val id = url.split("/").last()
        val body = client.newCall(GET("https://strmup.to/ajax/stream?filecode=$id", headers)).execute()
            .body.string()

        return when {
            "streaming_url" in body -> {
                val videoUrl = body.substringAfter("streaming_url")
                    .substringAfter(":\"")
                    .substringBefore('"')
                    .replace("\\", "")

                playlistUtils.extractFromHls(
                    videoUrl,
                    videoNameGen = { "Strmup - $it" },
                )
            }

            else -> emptyList()
        }
    }
}
