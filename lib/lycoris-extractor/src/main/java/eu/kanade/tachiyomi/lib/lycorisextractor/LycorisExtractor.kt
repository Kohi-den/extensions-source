package eu.kanade.tachiyomi.lib.lycorisextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class LycorisCafeExtractor(private val client: OkHttpClient) {

    private val urlApi = "https://zglyjsqsvevnyudbazgy.supabase.co"

    private val apiLycoris = "https://www.lycoris.cafe"

    private val json: Json by injectLazy()

    fun getVideosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {
        val embedHeaders = headers.newBuilder()
            .add("apikey", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpnbHlqc3FzdmV2bnl1ZGJhemd5Iiwicm9sZSI6ImFub24iLCJpYXQiOjE2OTM0ODYxNjYsImV4cCI6MjAwOTA2MjE2Nn0.H-_D56Tk5_8ebK9X700aFFI-zOPavq7ikhRNtU2njQ0")
            .add("Host", "zglyjsqsvevnyudbazgy.supabase.co")
            .build()

        val httpUrl = url.toHttpUrl()
        val title = httpUrl.queryParameter("title")
        val episode = httpUrl.queryParameter("episode")

        val response = client.newCall(
            GET("$urlApi/rest/v1/anime?select=video_links&anime_title=eq.${title}&episode_number=eq.${episode}", headers = embedHeaders)
        ).execute()

        // Parse the document to extract JSON
        val document = response.asJsoup()
        val jsonString = document.body().text() // Extracts the text content of the body tag

        // Deserialize JSON
        val data: List<PlayerData> = json.decodeFromString(jsonString)

        // Create Video objects for each quality
        val videos = mutableListOf<Video>()
        data.firstOrNull()?.video_links?.let { videoLinks ->
            val sdLink = resolveLink(videoLinks.SD, headers)
            val hdLink = resolveLink(videoLinks.HD, headers)
            val fhdLink = resolveLink(videoLinks.FHD, headers)

            if (fhdLink.isNotEmpty()) {
                videos.add(Video(fhdLink, "${prefix}lycoris.cafe - 1080p", fhdLink))
            }
            if (hdLink.isNotEmpty()) {
                videos.add(Video(hdLink, "${prefix}lycoris.cafe - 720p", hdLink))
            }
            if (sdLink.isNotEmpty()) {
                videos.add(Video(sdLink, "${prefix}lycoris.cafe - 480p", sdLink))
            }
        }
        return videos
    }
    private fun resolveLink(link: String, headers: Headers): String {
        return if(!link.startsWith("https://")) decodeOrFetchLink(link, headers) else link
    }

    private fun decodeOrFetchLink(encodedUrl: String, headers: Headers): String {
        val response = client.newCall(GET("$apiLycoris/api/getLink?id=$encodedUrl", headers = headers)).execute()
        return response.body?.string().orEmpty()
    }

    @Serializable
    data class PlayerData(
        val video_links: VideoLinks,
    ) {
        @Serializable
        data class VideoLinks(
            val HD: String = "",
            val SD: String = "",
            val FHD: String = "",
            val Source: String = "",
            val SourceMKV: String = ""
        )
    }
}
