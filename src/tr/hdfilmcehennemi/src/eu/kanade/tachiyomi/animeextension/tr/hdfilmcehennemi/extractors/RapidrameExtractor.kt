package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
@Serializable
class TrackDto(val file: String, val label: String, val language: String)

class RapidrameExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String, label: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).await().asJsoup()
        val script = doc.selectFirst("script:containsData(eval):containsData(atob)")?.data()
            ?: return emptyList()

        val unpackedScript = Unpacker.unpack(script).takeIf(String::isNotEmpty)
            ?: return emptyList()

        val varName = script.substringAfter("atob(").substringBefore(")")
        val playlistUrl = unpackedScript.getProperty("$varName=")
            .let { String(Base64.decode(it, Base64.DEFAULT)) }

        val hostUrl = "https://" + url.toHttpUrl().host
        val videoHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("origin", hostUrl)
            .build()

        val subtitles = script.substringAfter("tracks:").substringBefore("],")
            .parseAs<List<TrackDto>> { it + "]" }
            .map { Track(hostUrl + it.file, "[${it.language}] ${it.label}") }

        return playlistUtils.extractFromHls(
            playlistUrl,
            videoHeaders = videoHeaders,
            masterHeaders = videoHeaders,
            subtitleList = subtitles,
            videoNameGen = { "$label - $it" },
        )
    }

    private fun String.getProperty(before: String) =
        substringAfter("$before\"").substringBefore('"')
}
