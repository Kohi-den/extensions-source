package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnimeBlackMarketExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String): List<Video> {
        val newHeaders = headers.newBuilder().set("referer", "https://animeblackmarketapi.com/").build()

        val body = client.newCall(GET(url)).execute().body.string()

        return body.substringAfter("\"data\":[")
            .substringBefore(']')
            .split("{")
            .drop(1)
            .map {
                val language = it
                    .substringAfter("\"idioma\":\"")
                    .substringBefore('"')
                    .let {
                        when (it) {
                            "DUB" -> "Dublado"
                            "LEG" -> "Legendado"
                            else -> it
                        }
                    }
                val quality = it
                    .substringAfter("\"quality\":\"")
                    .substringBefore('"')
                    .let {
                        when (it) {
                            "FHD" -> "1080p"
                            "HD" -> "720p"
                            "SD" -> "480p"
                            else -> it
                        }
                    }
                val videoUrl = it.substringAfter("file")
                    .substringAfter("\"video\":\"")
                    .substringBefore('"')
                    .let { String(Base64.decode(it, Base64.DEFAULT)) }

                Video(videoUrl, "$language - $quality", videoUrl, newHeaders)
            }
    }
}
