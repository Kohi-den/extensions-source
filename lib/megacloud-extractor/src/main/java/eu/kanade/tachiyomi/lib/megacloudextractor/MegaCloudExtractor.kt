package eu.kanade.tachiyomi.lib.megacloudextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class MegaCloudExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val SOURCES_REGEX = Regex("/([^/?]+)(?:\\?|\$)")

    private suspend fun getClientKey(embedUrl: String, referer: String): String {
        val noncePattern1 = Regex("\\b[a-zA-Z0-9]{48}\\b")
        val noncePattern2 = Regex("\\b([a-zA-Z0-9]{16})\\b.*?\\b([a-zA-Z0-9]{16})\\b.*?\\b([a-zA-Z0-9]{16})\\b")
        val varPattern = Regex("_[a-zA-Z0-9_]+\\s*=\\s*['\"]([a-zA-Z0-9]{32,})['\"]")
        val objPattern = Regex("_[a-zA-Z0-9_]+\\s*=\\s*\\{[^}]*x\\s*:\\s*['\"]([a-zA-Z0-9]{16,})['\"][^}]*y\\s*:\\s*['\"]([a-zA-Z0-9]{16,})['\"][^}]*z\\s*:\\s*['\"]([a-zA-Z0-9]{16,})['\"]")

        repeat(10) { attempt ->
            try {
                val response = client.newCall(
                    GET(
                        embedUrl,
                        headers = headers.newBuilder()
                            .set("Referer", referer)
                            .set("X-Requested-With", "XMLHttpRequest")
                            .build()
                    )
                ).await()
                val html = response.body.string()

                noncePattern1.find(html)?.value?.takeIf { it.length in 32..64 }?.let { return it }
                noncePattern2.find(html)?.groupValues?.takeIf { it.size == 4 }?.let {
                    val key = it[1] + it[2] + it[3]
                    if (key.length in 32..64) return key
                }
                varPattern.findAll(html).forEach {
                    val key = it.groupValues[1]
                    if (key.length in 32..64) return key
                }
                objPattern.find(html)?.groupValues?.takeIf { it.size == 4 }?.let {
                    val key = it[1] + it[2] + it[3]
                    if (key.length in 32..64) return key
                }
            } catch (_: Exception) { /* silent retry */ }

            if (attempt < 9) delay(300L shl attempt)
        }
        throw Exception("Failed to extract client key (_k)")
    }

    suspend fun videosFromUrl(
        url: String,
        prefix: String
    ): List<Video> {
        val videoUrl = url.toHttpUrl()
        val referer = "https://${videoUrl.host}/"

        val sourceId = SOURCES_REGEX.find(videoUrl.encodedPath)?.groupValues?.get(1)
            ?: throw Exception("Failed to extract source ID")

        val basePath = videoUrl.encodedPath.substringBeforeLast("/")
        val sourcesBase = "${videoUrl.scheme}://${videoUrl.host}$basePath/getSources"

        // Automatically decide if we need _k based on domain
        val sourcesUrl = if (videoUrl.host.contains("megacloud", ignoreCase = true)) {
            val clientKey = getClientKey(url, referer)
            "$sourcesBase?id=$sourceId&_k=$clientKey"
        } else {
            // RapidCloud.co. — no _k needed
            "$sourcesBase?id=$sourceId"
        }

        val sourcesResponse = client.newCall(
            GET(
                sourcesUrl,
                headers = headers.newBuilder()
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Referer", url)
                    .build()
            )
        ).await()

        val body = sourcesResponse.body.string()
        val data = json.decodeFromString<SourcesResponseDto>(body)

        if (data.encrypted) {
            throw Exception("MATE WE'RE SCREWED")
        }

        val sourceList = data.sources ?: emptyList()

        val masterUrl = sourceList.find { it.type == "hls" }?.file
            ?: sourceList.firstOrNull()?.file
            ?: throw Exception("No video sources found")

        val subtitles = (data.tracks ?: emptyList())
            .filter { it.kind == "captions" }
            .map { Track(it.file, it.label ?: "English") }

        return playlistUtils.extractFromHls(
            masterUrl,
            referer = referer,
            videoHeadersGen = { _, _, _ ->
                headers.newBuilder()
                    .set("Referer", referer)
                    .build()
            },
            subtitleList = subtitles,
            videoNameGen = { quality -> "$prefix - $quality" }
        )
    }
}


@Serializable
private data class SourcesResponseDto(
    val sources: List<VideoLink>? = null,
    val encrypted: Boolean = false,
    val tracks: List<TrackDto>? = null,
    val intro: IntroOutro? = null,
    val outro: IntroOutro? = null,
)

@Serializable
private data class VideoLink(
    val file: String,
    val type: String? = null,
)

@Serializable
private data class TrackDto(
    val file: String,
    val kind: String,
    val label: String? = null,
)

@Serializable
private data class IntroOutro(
    val start: Int = 0,
    val end: Int = 0,
)
