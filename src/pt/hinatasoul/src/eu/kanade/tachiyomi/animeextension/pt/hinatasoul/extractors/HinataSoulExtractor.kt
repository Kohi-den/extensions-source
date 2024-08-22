package eu.kanade.tachiyomi.animeextension.pt.hinatasoul.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.Calendar
import java.util.Date

class HinataSoulExtractor(private val headers: Headers, private val client: OkHttpClient) {

    private var authCodeCache: String = ""
    private var authCodeDate: Date = Calendar.getInstance().getTime()

    private fun getAuthCode(serverUrl: String, thumbUrl: String): String {
        val duration = Calendar.getInstance().getTime().time - authCodeDate.time

        // check the authCode in cache for 1 hour
        if (authCodeCache.isNotBlank() && duration < 60 * 60 * 1000) {
            Log.d("HinataSoulExtractor", "Using authCode from cache")
            return authCodeCache
        }

        Log.d("HinataSoulExtractor", "Fetching new authCode")
        val videoName = serverUrl.split('/').last()

        val adsUrl =
            client.newCall(GET("$SITE_URL/playerricas.php?name=apphd/$videoName&img=$thumbUrl&url=$serverUrl"))
                .execute()
                .body.string()
                .substringAfter("ADS_URL")
                .substringAfter('"')
                .substringBefore('"')

        Log.d("HinataSoulExtractor", "ADS URL: $adsUrl")
        val adsContent = client.newCall(GET(adsUrl)).execute().body.string()

        val body = FormBody.Builder()
            .add("category", "client")
            .add("type", "premium")
            .add("ad", adsContent)
            .build()

        val newHeaders = headers.newBuilder()
            .set("Referer", SITE_URL)
            .add("Accept", "*/*")
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
            .add("Connection", "keep-alive")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-site")
            .build()

        val publicidade =
            client.newCall(POST("$ADS_URL/", headers = newHeaders, body = body))
                .execute()
                .body.string()
                .substringAfter("\"publicidade\"")
                .substringAfter('"')
                .substringBefore('"')

        if (publicidade.isBlank()) {
            Log.e("HinataSoulExtractor", "Failed to fetch \"publicidade\" code")
            return ""
        }

        authCodeCache =
            client.newCall(
                GET(
                    "$ADS_URL/?token=$publicidade",
                    headers = newHeaders,
                ),
            )
                .execute()
                .body.string()
                .substringAfter("\"publicidade\"")
                .substringAfter('"')
                .substringBefore('"')

        if (authCodeCache.isBlank()) {
            Log.e("HinataSoulExtractor", "Failed to fetch auth code")
        } else {
            Log.d("HinataSoulExtractor", "Auth code fetched successfully")
        }

        return authCodeCache
    }

    fun getVideoList(response: Response): List<Video> {
        val doc = response.asJsoup()
        val hasFHD = doc.selectFirst("div.abaItem:contains(FULLHD)") != null
        val serverUrl = doc.selectFirst("meta[itemprop=contentURL]")!!
            .attr("content")
            .replace("cdn1", "cdn3")
        val thumbUrl = doc.selectFirst("meta[itemprop=thumbnailUrl]")!!
            .attr("content")
        val type = serverUrl.split("/").get(3)
        val qualities = listOfNotNull("SD", "HD", if (hasFHD) "FULLHD" else null)
        val paths = listOf("appsd", "apphd").let {
            if (type.endsWith("2")) {
                it.map { path -> path + "2" }
            } else {
                it
            }
        } + listOf("appfullhd")

        val authCode = getAuthCode(serverUrl, thumbUrl)

        return qualities.mapIndexed { index, quality ->
            val path = paths[index]
            val url = serverUrl.replace(type, path) + authCode
            Video(url, quality, url, headers = headers)
        }.reversed()
    }

    companion object {
        val ADS_URL = "https://ads.anitube.vip"
        val SITE_URL = "https://www.anitube.vip"
    }
}
