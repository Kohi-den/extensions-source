package eu.kanade.tachiyomi.lib.buzzheavierextractor


import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.internal.EMPTY_HEADERS

class BuzzheavierExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    @OptIn(ExperimentalSerializationApi::class)
    fun videosFromUrl(url: String, prefix: String = "Buzzheavier - ", proxyUrl: String? = null): List<Video> {
        val httpUrl = url.toHttpUrl()
        val id = httpUrl.pathSegments.first()

        val dlHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Host", httpUrl.host)
            add("HX-Current-URL", url)
            add("HX-Request", "true")
            add("Referer", url)
        }.build()

        val videoHeaders = headers.newBuilder().apply {
            add("Referer", url)
        }.build()

        val path = client.newCall(
            GET("https://${httpUrl.host}/$id/download", dlHeaders)
        ).execute().headers["hx-redirect"].orEmpty()

        return if (path.isNotEmpty()) {
            val videoUrl = if (path.startsWith("http")) path else "https://${httpUrl.host}$path"
            listOf(Video(videoUrl, "${prefix}Video", videoUrl, videoHeaders))
        } else if (proxyUrl?.isNotEmpty() == true) {
            val videoUrl = client.newCall(GET(proxyUrl + id)).execute().parseAs<UrlDto>().url
            listOf(Video(videoUrl, "${prefix}Video", videoUrl, videoHeaders))
        } else {
            emptyList()
        }
    }

    @Serializable
    data class UrlDto(val url: String)
}
