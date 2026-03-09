package eu.kanade.tachiyomi.lib.megaupextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL

class MegaUpExtractor(
    private val client: OkHttpClient,
) {
    fun videosFromUrl(
        url: String,
        prefix: String = "MegaUp ",
        referer: String? = null,
    ): List<Video> {
        val parsedUrl = URL(url)
        val megaBase = "${parsedUrl.protocol}://${parsedUrl.host}"
        val token =
            parsedUrl.path
                .split("/")
                .lastOrNull { it.isNotEmpty() }
                ?.substringBefore("?")
                ?: return emptyList()

        val mediaResponse = client.newCall(Request.Builder().url("$megaBase/media/$token").build()).execute()
        val megaToken = JSONObject(mediaResponse.body.string()).getString("result")

        val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        val postBody =
            Json.encodeToString(
                DecodeRequest.serializer(),
                DecodeRequest(megaToken, userAgent),
            )
        val decodeResponse =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("https://enc-dec.app/api/dec-mega")
                        .post(postBody.toRequestBody("application/json".toMediaTypeOrNull()))
                        .build(),
                ).execute()

        val decoded = JSONObject(decodeResponse.body.string()).getString("result")
        val result = Json.decodeFromString<MegaUpResult>(decoded)

        val masterUrl =
            result.sources.firstOrNull { it.file.contains("list") && it.file.endsWith(".m3u8") }?.file
                ?: result.sources.firstOrNull()?.file
                ?: return emptyList()

        val subtitles =
            result.tracks
                .filter { it.kind == "captions" && it.file.endsWith(".vtt") }
                .sortedByDescending { it.default }
                .map { Track(it.file, it.label ?: "Unknown") }

        val videoHeaders =
            Headers
                .Builder()
                .apply {
                    add("User-Agent", userAgent)
                    if (!referer.isNullOrBlank()) add("Referer", referer)
                }.build()

        return parsePlaylist(masterUrl, prefix, videoHeaders, subtitles)
    }

    private fun parsePlaylist(
        playlistUrl: String,
        prefix: String,
        headers: Headers,
        subtitles: List<Track>,
    ): List<Video> {
        val playlistContent =
            client
                .newCall(Request.Builder().url(playlistUrl).build())
                .execute()
                .body
                .string()

        if (!playlistContent.contains("#EXT-X-STREAM-INF")) return emptyList()

        val lines = playlistContent.lines()
        val resolutionRegex = Regex("RESOLUTION=\\d+x(\\d+)")

        return buildList {
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val height = resolutionRegex.find(lines[i])?.groupValues?.get(1) ?: continue
                val streamUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                if (streamUrl.isEmpty()) continue

                val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else playlistUrl.substringBeforeLast("/") + "/" + streamUrl
                add(
                    Video(
                        videoTitle = "$prefix${height}p",
                        videoUrl = absoluteUrl,
                        headers = headers,
                        subtitleTracks = subtitles,
                        audioTracks = emptyList(),
                    ),
                )
            }
        }
    }

    @Serializable
    private data class MegaUpResult(
        val sources: List<Source>,
        val tracks: List<SubTrack> = emptyList(),
    )

    @Serializable
    private data class Source(
        val file: String,
    )

    @Serializable
    private data class SubTrack(
        val file: String,
        val label: String? = null,
        val kind: String,
        val default: Boolean = false,
    )

    @Serializable
    private data class DecodeRequest(
        val text: String,
        val agent: String,
    )
}
