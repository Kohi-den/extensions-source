package eu.kanade.tachiyomi.animeextension.pt.rinecloud.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class RineCloudExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String): List<Video> {
        val playerDoc = client.newCall(GET(url, headers)).execute().asJsoup()
        val encodedScript = playerDoc.selectFirst("script:containsData(JuicyCodes.Run)")
            ?.data()

        val script = if (encodedScript != null) {
            val decodedData = encodedScript.substringAfter("(").substringBefore(")")
                .split("+\"")
                .joinToString("") { it.replace("\"", "") }
                .let { Base64.decode(it, Base64.DEFAULT) }
                .let(::String)
            Unpacker.unpack(decodedData).ifEmpty { return emptyList() }
        } else {
            playerDoc.selectFirst("script:containsData(const player)")?.data()
                ?: return emptyList()
        }

        return script.substringAfter("sources:").substringBefore("]")
            .split("{")
            .drop(1)
            .flatMap {
                val videoUrl = it.substringAfter("\"file\"").substringAfter('"').substringBefore('"')
                val quality = it.substringAfter("\"label\"").substringAfter('"').substringBefore('"').ifBlank { "Unknown" }

                when {
                    arrayOf("googlevideo", ".mp4").any(videoUrl) -> listOf(Video(videoUrl, "Rinecloud - $quality", videoUrl, headers))
                    else -> playlistUtils.extractFromHls(videoUrl, videoNameGen = { "Rinecloud - ${if (it == "Video") quality else it}" })
                }
            }
    }

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }
}
