package eu.kanade.tachiyomi.lib.goodstramextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GoodStreamExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val host = url.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val videoHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", host)
            .build()

        val scriptData = buildString {
            doc.select("script").forEach { script ->
                append(script.data()).append("\n")
            }
        }

        // Extract subtitle tracks from jwplayer tracks config
        val subtitleTracks = SUBTITLE_REGEX.findAll(scriptData)
            .map { Track(it.groupValues[1], it.groupValues[2]) }
            .toList()

        // Extract video source URLs from jwplayer sources config
        val sourceUrl = SOURCE_REGEX.find(scriptData)?.groupValues?.get(1)
            ?: return emptyList()

        return if (sourceUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(
                sourceUrl,
                referer = url,
                videoNameGen = { quality -> "$name$quality" },
                subtitleList = subtitleTracks,
            )
        } else {
            listOf(
                Video(
                    videoTitle = name,
                    videoUrl = sourceUrl,
                    headers = videoHeaders,
                    subtitleTracks = subtitleTracks,
                ),
            )
        }
    }

    companion object {
        private val SOURCE_REGEX = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["'](https?://[^"']+)["']""")
        private val SUBTITLE_REGEX = Regex("""\{[^}]*file\s*:\s*["'](https?://[^"']+\.vtt)["'][^}]*label\s*:\s*["']([^"']+)["'][^}]*kind\s*:\s*["']captions["']""")
    }
}

