package eu.kanade.tachiyomi.lib.goodstramextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class GoodStreamExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, name: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val videos = mutableListOf<Video>()

        doc.select("script").forEach { script ->
            if (script.data().contains(Regex("file|player"))) {
                val urlRegex = Regex("file: \"(https:\\/\\/[a-z0-9.\\/-_?=&]+)\",")
                urlRegex.find(script.data())?.groupValues?.get(1)?.let { link ->
                    videos.add(
                        Video(
                            url = link,
                            quality = name,
                            videoUrl = link,
                            headers = headers
                        )
                    )
                }
            }
        }

        return videos
    }
}
