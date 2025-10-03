package eu.kanade.tachiyomi.animeextension.en.animekai

import android.app.Application
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.net.URL
import kotlin.getValue

class MegaUp(private val client: OkHttpClient) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val tag = "AnimeKaiMegaUp"

    /**
     * Extracts and returns a list of valid Video objects from a MegaUp URL.
     * Each Video contains the relevant subtitle tracks and headers for playback.
     *
     * @param url The MegaUp video URL.
     * @param userAgent The user agent string for requests.
     * @param qualityPrefix Optional prefix for video quality labels.
     * @param referer The referer to use for requests (usually the anime website base URL).
     * @return List of Video objects, or an empty list if extraction fails.
     */
    fun processUrl(
        url: String,
        userAgent: String,
        qualityPrefix: String? = null,
        referer: String? = null,
    ): List<Video> {
        val parsedUrl = URL(url)
        val baseUrl = buildString {
            append(parsedUrl.protocol)
            append("://")
            append(parsedUrl.host)
            if (parsedUrl.port != -1 && parsedUrl.port != parsedUrl.defaultPort) {
                append(":").append(parsedUrl.port)
            }
        }
        val pathSegments = parsedUrl.path.split("/").filter { it.isNotEmpty() }
        val token = pathSegments.lastOrNull()?.substringBefore("?")
            ?: throw IllegalArgumentException("No token found in URL: $url")
        val reqUrl = "$baseUrl/media/$token"
        val response = client.newCall(okhttp3.Request.Builder().url(reqUrl).build()).execute()
        val responseBody = response.body.string()
        val megaToken = JSONObject(responseBody).getString("result")
        val postBody = MegaDecodePostBody(megaToken, userAgent)
        val postRequest = okhttp3.Request.Builder()
            .url("https://enc-dec.app/api/dec-mega")
            .post(
                Json.encodeToString(MegaDecodePostBody.serializer(), postBody)
                    .toRequestBody("application/json".toMediaTypeOrNull()),
            )
            .build()
        val postResponse = client.newCall(postRequest).execute()
        val postResponseBody = postResponse.body.string()
        val decodedResult = JSONObject(postResponseBody).getString("result")
        val megaUpResult = Json.decodeFromString<MegaUpResult>(decodedResult)
        val masterPlaylistUrl = megaUpResult.sources.firstOrNull { it.file.contains("list") && it.file.endsWith(".m3u8") }?.file
            ?: megaUpResult.sources.firstOrNull()?.file
        val subtitleTracks = buildSubtitleTracks(megaUpResult)
        return buildVideoResults(masterPlaylistUrl, url, subtitleTracks, qualityPrefix, url, userAgent, referer)
    }

    /**
     * Builds a list of subtitle tracks from the MegaUpResult.
     */
    private fun buildSubtitleTracks(megaUpResult: MegaUpResult): List<Track> {
        return megaUpResult.tracks
            .filter { it.kind == "captions" && it.file.endsWith(".vtt") }
            .sortedByDescending { it.default }
            .map { Track(it.file, it.label ?: "Unknown") }
    }

    /**
     * Builds a list of Video objects from the playlist, validating each candidate for accessibility and codec info.
     */
    private fun buildVideoResults(
        masterPlaylistUrl: String?,
        reqUrl: String,
        subtitleTracks: List<Track>,
        qualityPrefix: String?,
        originalUrl: String,
        userAgent: String,
        referer: String?,
    ): MutableList<Video> {
        val videoResults = mutableListOf<Video>()
        val prefix = qualityPrefix ?: "MegaUp - "
        val headers = Headers.Builder().apply {
            add("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) add("Referer", referer)
        }.build()
        try {
            val playlistUrl = masterPlaylistUrl ?: reqUrl
            val playlistResponse = client.newCall(okhttp3.Request.Builder().url(playlistUrl).build()).execute()
            val playlistContent = playlistResponse.body.string().orEmpty()
            if (playlistContent.contains("#EXT-X-STREAM-INF")) {
                val lines = playlistContent.lines()
                val pattern = Regex("RESOLUTION=(\\d+)x(\\d+)")
                val codecsPattern = Regex("CODECS=\"([^\"]+)\"")
                for (i in lines.indices) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val match = pattern.find(line)
                        val height = match?.groupValues?.getOrNull(2)
                        val currentQuality = if (height != null) "${height}p" else null
                        val codecsMatch = codecsPattern.find(line)
                        val currentCodecs = codecsMatch?.groupValues?.getOrNull(1)
                        val streamUrl = lines.getOrNull(i + 1)?.trim()
                        if (!streamUrl.isNullOrEmpty() && currentQuality != null) {
                            val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else playlistUrl.substringBeforeLast("/") + "/" + streamUrl
                            val qualityWithCodec = currentQuality + (if (currentCodecs != null) " [$currentCodecs]" else "")
                            try {
                                val testResponse = client.newCall(okhttp3.Request.Builder().url(absoluteUrl).build()).execute()
                                val testContent = testResponse.body.string().orEmpty()
                                val isMaster = testContent.contains("#EXT-X-STREAM-INF")
                                val isMedia = testContent.contains("#EXTINF")
                                if (isMaster || isMedia) {
                                    videoResults.add(
                                        Video(
                                            originalUrl,
                                            "$prefix$qualityWithCodec",
                                            absoluteUrl,
                                            headers,
                                            subtitleTracks,
                                            emptyList(),
                                        ),
                                    )
                                }
                            } catch (_: Exception) {
                                // Ignore and skip this entry if fetching fails
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore and return whatever was found so far
        }
        return videoResults
    }

    @Serializable
    data class MegaUpResult(
        val sources: List<MegaUpSource>,
        val tracks: List<MegaUpTrack>,
        val download: String? = null,
    )

    @Serializable
    data class MegaUpSource(
        val file: String,
    )

    @Serializable
    data class MegaUpTrack(
        val file: String,
        val label: String? = null,
        val kind: String,
        val default: Boolean = false,
    )

    @Serializable
    data class MegaDecodePostBody(
        val text: String,
        val agent: String,
    )
}
