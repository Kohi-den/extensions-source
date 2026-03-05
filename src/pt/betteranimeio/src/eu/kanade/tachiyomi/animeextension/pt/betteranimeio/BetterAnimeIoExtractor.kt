package eu.kanade.tachiyomi.animeextension.pt.betteranimeio

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class BetterAnimeIoExtractor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    fun extractVideosFromApi(encodedSource: String): List<Video> {
        val apiUrl = "$API_URL$encodedSource"
        return try {
            val response = client.newCall(GET(apiUrl)).execute()
            val responseBody = response.body.string()
            val videoResponse = json.decodeFromString<VideoApiResponse>(responseBody)

            if (videoResponse.status == "success") {
                videoResponse.play.map { video ->
                    Video(video.src, video.sizeText, video.src)
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val API_URL = "https://api.myblogapi.site/api/v1/decode/blogg/"
    }
}
