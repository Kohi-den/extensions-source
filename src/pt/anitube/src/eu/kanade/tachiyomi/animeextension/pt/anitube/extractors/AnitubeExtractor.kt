package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

object AnitubeExtractor {
    fun getVideoList(response: Response, headers: Headers, client: OkHttpClient): List<Video> {
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

        val videoName = serverUrl.split('/').last()

        val adsUrl =
            client.newCall(GET("https://www.anitube.vip/playerricas.php?name=apphd/$videoName&img=$thumbUrl&url=$serverUrl"))
                .execute()
                .body.string()
                .substringAfter("ADS_URL")
                .substringAfter('"')
                .substringBefore('"')

        val adsContent = client.newCall(GET(adsUrl)).execute().body.string()

        val body = FormBody.Builder()
            .add("category", "client")
            .add("type", "premium")
            .add("ad", adsContent)
            .build()

        val publicidade = client.newCall(POST("https://ads.anitube.vip/", body = body))
            .execute()
            .body.string()
            .substringAfter("\"publicidade\"")
            .substringAfter('"')
            .substringBefore('"')

        val authCode = client.newCall(GET("https://ads.anitube.vip/?token=$publicidade"))
            .execute()
            .body.string()
            .substringAfter("\"publicidade\"")
            .substringAfter('"')
            .substringBefore('"')

        return qualities.mapIndexed { index, quality ->
            val path = paths[index]
            val url = serverUrl.replace(type, path) + authCode
            Video(url, quality, url, headers = headers)
        }.reversed()
    }
}
