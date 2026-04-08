package eu.kanade.tachiyomi.animeextension.tr.hentaizm.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class MailRuExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = "Mail.Ru - "): List<Video> {
        val response = client.newCall(GET(url)).execute()
        val document = response.body.string()

        val videoList = mutableListOf<Video>()

        // Mail.Ru'nun kaynak kodundaki video kalitesi (key) ve MP4 linkini (url) bulan Regex kuralı
        val videoRegex = """"key"\s*:\s*"([^"]+)".*?"url"\s*:\s*"([^"]+)"""".toRegex()

        videoRegex.findAll(document).forEach { match ->
            val qualityStr = match.groupValues[1] 
            var videoUrl = match.groupValues[2]   

            if (videoUrl.startsWith("//")) {
                videoUrl = "https:$videoUrl"
            }

            val quality = "$prefix$qualityStr"
            videoList.add(Video(videoUrl, quality, videoUrl))
        }

        return videoList.sortedByDescending { it.quality }
    }
}
