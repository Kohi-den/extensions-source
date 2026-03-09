package eu.kanade.tachiyomi.lib.mp4uploadextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class Mp4uploadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(
        url: String,
        headers: Headers,
        prefix: String = "",
        suffix: String = "",
        proxyUrlFn: ((videoUrl: String, videoHeaders: Map<String, String>) -> String?)? = null,
    ): List<Video> {
        val embedHeaders = headers.newBuilder()
            .set("Referer", REFERER)
            .build()

        val doc = client.newCall(GET(url, embedHeaders)).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: doc.selectFirst("script:containsData(player.src)")?.data()
            ?: return emptyList()

        val videoUrl = script.substringAfter(".src(").substringBefore(")")
            .substringAfter("src:").substringAfter('"').substringBefore('"')

        if (videoUrl.isBlank() || !videoUrl.startsWith("http")) return emptyList()

        val resolution = QUALITY_REGEX.find(script)?.groupValues?.get(1)
            ?.takeIf { it != "0" }
            ?.let { "${it}p" }
            ?: IFRAME_HEIGHT_REGEX.find(script)?.groupValues?.get(1)
                ?.takeIf { it != "0" }
                ?.let { "${it}p" }
            ?: "Unknown resolution"
        val quality = "${prefix}Mp4Upload - $resolution$suffix"

        val videoHeaders = mapOf("Referer" to REFERER)
        val finalUrl = proxyUrlFn?.invoke(videoUrl, videoHeaders) ?: videoUrl

        val headersForVideo = headers.newBuilder()
            .set("Referer", REFERER)
            .build()

        return listOf(Video(videoTitle = quality, videoUrl = finalUrl, headers = headersForVideo, subtitleTracks = emptyList(), audioTracks = emptyList()))
    }

    companion object {
        private val QUALITY_REGEX by lazy { """\WHEIGHT=(\d+)""".toRegex() }
        private val IFRAME_HEIGHT_REGEX by lazy { """HEIGHT=(\d+)\s""".toRegex() }
        private const val REFERER = "https://www.mp4upload.com/"
    }
}
