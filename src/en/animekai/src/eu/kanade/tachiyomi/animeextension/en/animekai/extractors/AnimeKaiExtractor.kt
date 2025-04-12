package eu.kanade.tachiyomi.animeextension.en.animekai.extractors

import eu.kanade.tachiyomi.animeextension.en.animekai.AnimekaiDecoder
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class MegaUpExtractor {

    private val client: OkHttpClient by injectLazy()

    fun getVideoList(url: String): List<Video> {
        val mediaUrl = url.replace("/e/", "/media/").replace("/e2/", "/media/")

        val encodedResult = runCatching {
            val response = client.newCall(GET(mediaUrl)).execute().body.string()
            Jsoup.parse(response).selectFirst("body")?.text()?.let { json ->
                json.substringAfter("\"result\":\"").substringBefore("\",\"status\"")
            }
        }.getOrNull() ?: return emptyList()

        val decryptSteps = runCatching {
            val json = client.newCall(GET(KEYS_URL)).execute().body.string()
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
            val quality = "MegaUp - Auto"
            videoList.add(Video(source.file, quality, source.file))
        }

        return videoList
    }

    companion object {
        private const val KEYS_URL =
            "https://raw.githubusercontent.com/amarullz/kaicodex/refs/heads/main/generated/kai_codex.json"

        private fun GET(url: String): Request {
            return Request.Builder()
                .url(url)
                .headers(
                    Headers.headersOf(
                        "User-Agent", "Mozilla/5.0",
                        "Accept", "application/json",
                        "Referer", "https://animekai.to/"
                    )
                )
                .build()
        }
    }

    @Serializable
    data class AnimeKaiKey(val kai: Kai, val megaup: Megaup)

    @Serializable
    data class Kai(val encrypt: List<List<String>>, val decrypt: List<List<String>>)

    @Serializable
    data class Megaup(val encrypt: List<List<String>>, val decrypt: List<List<String>>)

    @Serializable
    data class M3U8(
        val sources: List<Source>,
        val tracks: List<Track> = emptyList(),
        val download: String? = null
    )

    @Serializable
    data class Source(val file: String)

    @Serializable
    data class Track(
        val file: String,
        val label: String? = null,
        val kind: String? = null,
        val default: Boolean? = false
    )
}

data class Video(
    val url: String,
    val quality: String,
    val videoUrl: String
)
