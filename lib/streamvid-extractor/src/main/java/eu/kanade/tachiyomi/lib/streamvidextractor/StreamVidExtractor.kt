package eu.kanade.tachiyomi.lib.streamvidextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class StreamVidExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = "", sourceChange: Boolean = false): List<Video> {
        return runCatching {
            val doc = client.newCall(GET(url)).execute().asJsoup()

            val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
                ?.let(JsUnpacker::unpackAndCombine)
                ?: return emptyList()
            val masterUrl = if (!sourceChange) {
                script.substringAfter("sources:[{src:\"").substringBefore("\",")
            } else {
                script.substringAfter("sources:[{file:\"").substringBefore("\"")
            }
            PlaylistUtils(client).extractFromHls(masterUrl, videoNameGen = { "${prefix}StreamVid - (${it}p)" })
        }.getOrElse { emptyList() }
    }
}
