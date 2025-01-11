package eu.kanade.tachiyomi.animeextension.pt.animesbr.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class RuplayExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        return client.newCall(GET(url)).execute()
            .body.string()
            .substringAfter("Playerjs({")
            .substringAfter("file:")
            .substringAfter("\"")
            .substringBefore("\"")
            .split(",")
            .map { file ->
                val videoUrl = file.substringAfter("]")
                val quality = file.substringAfter("[", "")
                    .substringBefore("]")
                    .ifEmpty { "Default" }

                val headers = Headers.headersOf("Referer", videoUrl)
                Video(videoUrl, "$prefix Ruplay - $quality".trim(), videoUrl, headers = headers)
            }
    }
}
