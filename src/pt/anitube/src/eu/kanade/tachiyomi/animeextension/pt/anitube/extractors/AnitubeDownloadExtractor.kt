package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMapNotNullBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class AnitubeDownloadExtractor(
    private val headers: Headers,
    private val client: OkHttpClient,
) {

    private val qualities = listOf("SD", "HD", "FULLHD")
    private val tag by lazy { javaClass.simpleName }

    private fun videosFromFile4Go(url: String, quality: String): Video? {
        Log.d(tag, "Checking download for $url")

        val newHeaders = headers.newBuilder()
            .set("Referer", "https://${url.toHttpUrl().host}/")
            .add("Accept", "*/*")
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
            .add("Connection", "keep-alive")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-site")
            .build()

        val id = url.split('/').last()
        val idusuario =
            client.newCall(GET("$ADS_URL/file4go.php", headers = newHeaders))
                .execute()
                .body.string()
                .substringAfter("\"publicidade\"")
                .substringAfter('"')
                .substringBefore('"')

        val body = FormBody.Builder().apply {
            add("id", id)
            add("idusuario", idusuario)
        }.build()

        val postUrl = "https://www.file4go.net/getdownload_new_anitube.php"

        val postHeaders = headers.newBuilder()
            .set("Referer", url)
            .build()

        val docFinal =
            client.newCall(POST(postUrl, headers = postHeaders, body = body))
                .execute().asJsoup()

        val videoUrl = docFinal.selectFirst("a.novobotao.download")?.attr("href")

        if (videoUrl == null) {
            Log.d(tag, "Download link not found for $url")
            return null
        }

        return Video(videoUrl, "$quality - File4Go", videoUrl)
    }

    private fun videosFromDownloadPage(url: String, epName: String): List<Video> {
        Log.d(tag, "Extracting videos links for URL: $url")
        val docDownload = client.newCall(GET(url)).execute().asJsoup()

        val row = docDownload.select("table.downloadpag_episodios tr").firstOrNull {
            it.text().contains(epName)
        }

        if (row == null) {
            Log.d(tag, "Episode $epName not found in download page")
            return emptyList()
        }

        val links = row.select("td").mapIndexedNotNull { index, el ->
            val link = el.selectFirst("a") ?: return@mapIndexedNotNull null

            object {
                var quality = qualities.get(index - 1)
                var url = link.attr("href")
            }
        }

        Log.d(tag, "Found ${links.size} links for $epName")

        return links.parallelMapNotNullBlocking {
            if (!it.url.contains("file4go.net")) {
                return@parallelMapNotNullBlocking null
            }
            videosFromFile4Go(it.url, it.quality)
        }.reversed()
    }

    fun videosFromUrl(url: String, epName: String, quality: String = "Default"): List<Video> {
        if (url.contains("file4go.net")) {
            return listOfNotNull(videosFromFile4Go(url, quality))
        }

        return videosFromDownloadPage(url, epName)
    }

    companion object {
        private const val ADS_URL = "https://ads.anitube.vip"
    }
}
