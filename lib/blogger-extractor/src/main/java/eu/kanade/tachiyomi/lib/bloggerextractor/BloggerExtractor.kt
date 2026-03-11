package eu.kanade.tachiyomi.lib.bloggerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy

class BloggerExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, headers: Headers, suffix: String = ""): List<Video> {
        val body = client.newCall(GET(url, headers)).execute()
            .body.string()

        var videos = getStreamVideos(body, headers, suffix)

        if (videos.isEmpty()) {
            videos = getRpcVideos(url, body, headers, suffix)
        }

        return videos
    }

    private fun getStreamVideos(body: String, headers: Headers, suffix: String = ""): List<Video> {
        return body
            .takeIf { !it.contains("errorContainer") }
            .let { it ?: return emptyList() }
            .substringAfter("\"streams\":[", "")
            .substringBefore("]")
            .split("},")
            .mapNotNull {
                val videoUrl = it.substringAfter("\"play_url\":\"").substringBefore('"')
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val format = it.substringAfter("\"format_id\":").substringBefore('}')
                val quality = when (format) {
                    "7" -> "240p"
                    "18" -> "360p"
                    "22" -> "720p"
                    "37" -> "1080p"
                    else -> "Unknown"
                }
                Video(videoUrl, "Blogger - $quality $suffix".trimEnd(), videoUrl, headers)
            }
    }

    /**
     * Extract videos from the RPC URL
     * Based on https://github.com/FightFarewellFearless/AniFlix/blob/4b07254fc0051664691fd2f3c001dbd6b43e18ad/src/utils/scrapers/animeSeries.ts#L445
     */
    private fun getRpcVideos(
        url: String,
        body: String,
        headers: Headers,
        suffix: String = "",
    ): List<Video> {
        val token = url.toHttpUrl().queryParameter("token")

        val f_sid = body.substringAfter("FdrFJe\":\"").substringBefore("\"")
        val bl = body.substringAfter("cfb2h\":\"").substringBefore("\"")
        val reqid = ((System.currentTimeMillis() / 1000L) % 86400L).toString() // Number of seconds of the day

        val rpcUrl =
            "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=${f_sid}&bl=${bl}&hl=en-US&_reqid=${reqid}&rt=c"
        val rpcBody =
            "f.req=%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22${token}%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D&".toRequestBody()
        val rpcHeaders = Headers.headersOf(
            "accept",
            "*/*",
            "accept-language",
            "en-US,en;q=0.9",
            "content-type",
            "application/x-www-form-urlencoded;charset=UTF-8",
            "priority",
            "u=1, i",
            "sec-fetch-dest",
            "empty",
            "sec-fetch-mode",
            "cors",
            "sec-fetch-site",
            "same-origin",
            "User-Agent",
            headers["User-Agent"] ?: "",
            "x-same-domain",
            "1",
            "Referer",
            "https://www.blogger.com/",
        )

        val rpcString = client.newCall(POST(rpcUrl, body = rpcBody, headers = rpcHeaders)).execute()
            .body.string()

        return rpcString
            .let { it ?: return emptyList() }
            .substringAfter("[[\\\"", "")
            .substringBefore("]]]")
            .takeIf { it.contains("https://") }
            .let { it ?: return emptyList() }
            .let { "\\\"$it]" }
            .split("],[")
            .mapNotNull {
                val videoUrl = it.substringAfter("\\\"", "")
                    .substringBefore("\\\"")
                    .takeIf(String::isNotBlank)
                    .let { json.decodeFromString<String>("\"$it\"") }
                    .let { json.decodeFromString<String>("\"$it\"") } // Yes, need decode twice
                    ?: return@mapNotNull null

                val format = it.substringAfter("[").substringBefore("]")

                val quality = when (format) {
                    "7" -> "240p"
                    "18" -> "360p"
                    "22" -> "720p"
                    "37" -> "1080p"
                    else -> "Unknown"
                }
                Video(videoUrl, "Blogger - $quality $suffix".trimEnd(), videoUrl, headers)
            }
    }
}
