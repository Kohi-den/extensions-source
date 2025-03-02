package eu.kanade.tachiyomi.lib.amazonextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class AmazonExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        if (url.contains("disable", true)) return emptyList()

        val document = client.newCall(GET(url)).execute().asJsoup()

        val shareIdScript = document.select("script:containsData(var shareId)").firstOrNull()?.data()
        if (shareIdScript.isNullOrBlank()) return emptyList()

        val shareId = shareIdScript.substringAfter("shareId = \"").substringBefore("\"")
        val amazonApiJsonUrl = "https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"

        val amazonApiJson = client.newCall(GET(amazonApiJsonUrl)).execute().asJsoup()

        val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
        val amazonApiUrl = "https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"

        val amazonApi = client.newCall(GET(amazonApiUrl)).execute().asJsoup()

        val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")

        val serverName = if (videoUrl.contains("&ext=es")) "AmazonES" else "Amazon"

        return if (videoUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(videoUrl, videoNameGen = { "${prefix}$serverName:$it" })
        } else {
            listOf(Video(videoUrl, "${prefix}$serverName", videoUrl))
        }
    }
}
