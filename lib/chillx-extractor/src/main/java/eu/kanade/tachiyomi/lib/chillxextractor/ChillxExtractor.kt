package eu.kanade.tachiyomi.lib.chillxextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient

class ChillxExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val webViewResolver by lazy { WebViewResolver(client, headers) }

    companion object {
        private val REGEX_SOURCES = Regex("""sources:\s*\[\{"file":"([^"]+)""")
        private val REGEX_FILE = Regex("""file: ?"([^"]+)"""")
        private val REGEX_SOURCE = Regex("""source = ?"([^"]+)"""")
        private val REGEX_SUBS = Regex("""\{"file":"([^"]+)","label":"([^"]+)","kind":"captions","default":\w+\}""")
    }

    fun videoFromUrl(url: String, prefix: String = "Chillx - "): List<Video> {
        val data = webViewResolver.getDecryptedData(url) ?: return emptyList()

        val masterUrl = REGEX_SOURCES.find(data)?.groupValues?.get(1)
            ?: REGEX_FILE.find(data)?.groupValues?.get(1)
            ?: REGEX_SOURCE.find(data)?.groupValues?.get(1)
            ?: return emptyList()

        val subtitleList = buildList {
            val subtitles = REGEX_SUBS.findAll(data)
            subtitles.forEach {
                add(Track(it.groupValues[1], decodeUnicodeEscape(it.groupValues[2])))
            }
        }

        val videoList = playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = url,
            videoNameGen = { "$prefix$it" },
            subtitleList = subtitleList,
        )

        return videoList.map {
            Video(
                url = it.url,
                quality = it.quality,
                videoUrl = it.videoUrl,
                headers = it.headers,
                audioTracks = it.audioTracks,
                subtitleTracks = playlistUtils.fixSubtitles(it.subtitleTracks),
            )
        }
    }

    private fun decodeUnicodeEscape(input: String): String {
        val regex = Regex("u([0-9a-fA-F]{4})")
        return regex.replace(input) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
    }
}
