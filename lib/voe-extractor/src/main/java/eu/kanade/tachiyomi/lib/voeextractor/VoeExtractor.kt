package eu.kanade.tachiyomi.lib.voeextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class VoeExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    private val clientDdos by lazy { client.newBuilder().addInterceptor(DdosGuardInterceptor(client)).build() }

    private val playlistUtils by lazy { PlaylistUtils(clientDdos) }

    @Serializable
    data class VideoLinkDTO(val source: String)

    private fun decodeVoeData(data: String): String {
        val shifted = data.map { char ->
            when (char) {
                in 'A'..'Z' -> 'A' + (char - 'A' + 13).mod(26)
                in 'a'..'z' -> 'a' + (char - 'a' + 13).mod(26)
                else -> char
            }
        }.joinToString()

        val junk = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        var result = shifted
        for (part in junk) {
            result = result.replace(part, "_")
        }
        val clean = result.replace("_", "")

        val transformed = String(Base64.decode(clean, Base64.DEFAULT)).map {
            (it.code - 3).toChar()
        }.joinToString().reversed()

        val decoded = String(Base64.decode(transformed, Base64.DEFAULT))

        return json.decodeFromString<VideoLinkDTO>(decoded).source
    }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        var document = clientDdos.newCall(GET(url)).execute().asJsoup()

        if (document.selectFirst("script")?.data()?.contains("if (typeof localStorage !== 'undefined')") == true) {
            val originalUrl = document.selectFirst("script")?.data()
                ?.substringAfter("window.location.href = '")
                ?.substringBefore("';") ?: return emptyList()

            document = clientDdos.newCall(GET(originalUrl)).execute().asJsoup()
        }

        val encodedVoeData = document.select("script").find { it.data().contains("MKGMa=\"")}?.data()
            ?.substringAfter("MKGMa=\"")
            ?.substringBefore('"') ?: return emptyList()

        val playlistUrl = decodeVoeData(encodedVoeData)

        return playlistUtils.extractFromHls(playlistUrl,
            videoNameGen = { quality -> "${prefix}Voe:$quality" }
        )
    }
}
