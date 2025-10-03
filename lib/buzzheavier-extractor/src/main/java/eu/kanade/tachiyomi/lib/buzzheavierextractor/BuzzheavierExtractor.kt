package eu.kanade.tachiyomi.lib.buzzheavierextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import java.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class BuzzheavierExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    companion object {
        private val SIZE_REGEX = Regex("""Size\s*-\s*([0-9.]+\s*[GMK]B)""")
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun videosFromUrl(url: String, prefix: String = "Buzzheavier - ", proxyUrl: String? = null): List<Video> {
        val httpUrl = url.toHttpUrl()
        val id = httpUrl.pathSegments.first()

        val dlHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("HX-Current-URL", url)
            add("HX-Request", "true")
            add("Priority", "u=1, i")
            add("Referer", url)
        }.build()

        val videoHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            add("Priority", "u=0, i")
            add("Referer", url)
        }.build()

        val siteRequest = client.newCall(GET(url)).execute()
        val parsedHtml = siteRequest.asJsoup()
        val detailsText = parsedHtml.selectFirst("li:contains(Details:)")?.text() ?: ""
        val size = SIZE_REGEX.find(detailsText)?.groupValues?.getOrNull(1)?.trim() ?: "Unknown"

        val downloadRequest = GET("https://${httpUrl.host}/$id/download", dlHeaders)
        val path = client.executeWithRetry(downloadRequest, 5, 204).use { response ->
            response.header("hx-redirect").orEmpty()
        }

        val videoUrl = if (path.isNotEmpty()) {
            if (path.startsWith("http")) path else "https://${httpUrl.host}$path"
        } else if (proxyUrl?.isNotEmpty() == true) {
            client.executeWithRetry(GET(proxyUrl + id), 5, 200).parseAs<UrlDto>().url
        } else {
            return emptyList()
        }

        return listOf(Video(videoUrl, "${prefix}${size}", videoUrl, videoHeaders))
    }

    private fun OkHttpClient.executeWithRetry(request: Request, maxRetries: Int, validCode: Int): Response {
        var response: Response? = null
        for (attempt in 0 until maxRetries) {
            response?.close()
            response = this.newCall(request).execute()
            if (response.code == validCode) {
                return response
            }
            if (attempt < maxRetries - 1) {
                Thread.sleep(1000)
            }
        }
        return response ?: throw IOException("Failed to execute request after $maxRetries attempts")
    }

    @Serializable
    data class UrlDto(val url: String)
}
