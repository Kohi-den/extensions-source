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

    private val linkRegex = "(http|https)://([\\w_-]+(?:\\.[\\w_-]+)+)([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])".toRegex()

    private val base64Regex = Regex("'.*'")

    @Serializable
    data class VideoLinkDTO(val file: String)

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        var document = clientDdos.newCall(GET(url)).execute().asJsoup()

        if (document.selectFirst("script")?.data()?.contains("if (typeof localStorage !== 'undefined')") == true) {
            val originalUrl = document.selectFirst("script")?.data()
                ?.substringAfter("window.location.href = '")
                ?.substringBefore("';") ?: return emptyList()

            document = clientDdos.newCall(GET(originalUrl)).execute().asJsoup()
        }

        val script = document.selectFirst("script:containsData(const sources), script:containsData(var sources), script:containsData(wc0)")
            ?.data()
            ?: return emptyList()
        val playlistUrl = when {
            // Layout 1
            script.contains("sources") -> {
                val link = script.substringAfter("hls': '").substringBefore("'")
                if (linkRegex.matches(link)) link else String(Base64.decode(link, Base64.DEFAULT))
            }
            // Layout 2
            script.contains("wc0") -> {
                val base64 = base64Regex.find(script)!!.value
                val decoded = Base64.decode(base64, Base64.DEFAULT).let(::String)
                json.decodeFromString<VideoLinkDTO>(decoded).file
            }
            else -> return emptyList()
        }
        return playlistUtils.extractFromHls(playlistUrl,
            videoNameGen = { quality -> "${prefix}Voe:$quality" }
        )
    }
}
