package eu.kanade.tachiyomi.animeextension.en.animekai.extractors

import eu.kanade.tachiyomi.animeextension.en.animekai.AnimekaiDecoder
import eu.kanade.tachiyomi.source.model.Video
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class AnimeKaiMegaUpExtractor {

    private val client: OkHttpClient by injectLazy()

    fun getVideoList(url: String): List<Video> {
        // Adjust media URL dynamically for both animekai.to and animekai.bz
        val mediaUrl = url.replace("/e/", "/media/").replace("/e2/", "/media/")

        val encodedResult = runCatching {
            val response = client.newCall(GET(mediaUrl)).execute().body?.string() ?: return emptyList()
            Jsoup.parse(response).selectFirst("body")?.text()?.let { json ->
                json.substringAfter("\"result\":\"").substringBefore("\",\"status\"")
            }
        }.getOrNull() ?: return emptyList()

        val decryptSteps = runCatching {
            val json = client.newCall(GET(KEYS_URL)).execute().body?.string()
            Json.decodeFromString(AnimeKaiKey.serializer(), json).megaup.decrypt
        }.getOrNull() ?: return emptyList()

        val decodedJson = runCatching {
            AnimekaiDecoder().decode(encodedResult, decryptSteps).replace("\\", "")
        }.getOrNull() ?: return emptyList()

        val m3u8Data = runCatching {
            Json.decodeFromString(M3U8.serializer(), decodedJson)
        }.getOrNull() ?: return emptyList()

        val videoList = mutableListOf<Video>()

        m3u8Data.sources.forEach { source ->
            val quality = source.file.split("quality=").getOrNull(1)?.split("&")?.firstOrNull() ?: "MegaUp - Auto"
            videoList.add(
                Video(
                    url = source.file,
                    quality = quality,
                    videoUrl = source.file,
                ),
            )
        }

        return videoList
    }

    companion object {
        private const val KEYS_URL =
            "https://raw.githubusercontent.com/amarullz/kaicodex/refs/heads/main/generated/kai_codex.json"

        private fun get(url: String): Request {
            // Adjust headers dynamically for animekai.to and animekai.bz
            val referer = if (url.contains("animekai.to")) {
                "https://animekai.to/"
            } else {
                "https://animekai.bz/"
            }

            return Request.Builder()
                .url(url)
                .headers(
                    Headers.headersOf(
                        "User-Agent",
                        "Mozilla/5.0",
                        "Accept",
                        "application/json",
                        "Referer",
                        referer,
                    ),
                )
                .build()
        }
    }
}

@Serializable
data class M3U8(
    val sources: List<M3U8Source>,
)

@Serializable
data class M3U8Source(
    val file: String,
)

data class Video(
    val url: String,
    val quality: String,
    val videoUrl: String,
)