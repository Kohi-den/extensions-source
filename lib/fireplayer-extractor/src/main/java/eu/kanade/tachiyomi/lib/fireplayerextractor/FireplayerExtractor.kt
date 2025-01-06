package eu.kanade.tachiyomi.lib.fireplayerextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class FireplayerExtractor(
    private val client: OkHttpClient,
    private val defaultHost: String? = null,
) {
    fun videosFromUrl(
        url: String,
        videoNameGen: (String) -> String = { quality -> quality },
        videoHost: String? = null,
    ): List<Video> {
        val host = videoHost ?: defaultHost ?: "https://${url.toHttpUrl().host}"

        val headers = Headers.Builder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", host)
            .set("Origin", "https://${host.toHttpUrl().host}")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        var id = url.substringAfterLast("/")

        if (id.length < 32) {
            val doc = client.newCall(GET(url, headers)).execute().asJsoup()

            val script =
                doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
                    ?.replace(Regex("[\\u00E0-\\u00FC]"), "-") // Fix a bug in JsUnpacker with accents
                    ?.let(JsUnpacker::unpackAndCombine)
                    ?: doc.selectFirst("script:containsData(FirePlayer)")?.data()

            if (script?.contains("FirePlayer(") == true) {
                id = script.substringAfter("FirePlayer(\"").substringBefore('"')
            }
        }

        val postUrl = "$host/player/index.php?data=$id&do=getVideo"
        val body = FormBody.Builder()
            .add("hash", id)
            .add("r", "")
            .build()

        val masterUrl = client.newCall(POST(postUrl, headers, body = body)).execute()
            .body.string()
            .substringAfter("securedLink\":\"")
            .substringBefore('"')
            .replace("\\", "")

        val playlistUtils = PlaylistUtils(client, headers)

        return playlistUtils.extractFromHls(masterUrl, videoNameGen = videoNameGen)
    }
}
