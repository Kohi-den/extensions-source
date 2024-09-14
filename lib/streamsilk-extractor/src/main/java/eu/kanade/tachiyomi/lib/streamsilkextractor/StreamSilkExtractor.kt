package eu.kanade.tachiyomi.lib.streamsilkextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.internal.commonEmptyHeaders
import uy.kohesive.injekt.injectLazy

class StreamSilkExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {

    private val srcRegex = Regex("var urlPlay =\\s*\"(.*?m3u8.*?)\"")

    private val subsRegex = Regex("jsonUrl = `([^`]*)`")

    private val videoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$STREAM_SILK_URL/")
            .set("Origin", STREAM_SILK_URL)
            .build()
    }

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, videoHeaders) }

    fun videosFromUrl(url: String, prefix: String) = videosFromUrl(url) { "${prefix}StreamSilk:$it" }

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamSilk:$quality" }): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val scriptData = document.select("script").firstOrNull { it.html().contains("h,u,n,t,e,r") }?.data() ?: return emptyList()
        val deHunt = JsHunter(scriptData).dehunt() ?: return emptyList()
        val link = extractLink(deHunt) ?: return emptyList()

        val subs = buildList {
            val subUrl = extractSubs(deHunt)
            if (!subUrl.isNullOrEmpty()) {
                runCatching {
                    client.newCall(GET(subUrl, videoHeaders)).execute().body.string()
                        .let { json.decodeFromString<List<SubtitleDto>>(it) }
                        .forEach { add(Track(it.file, it.label)) }
                }
            }
        }

        return playlistUtils.extractFromHls(link, videoNameGen = videoNameGen, subtitleList = subs)
    }

    private fun extractLink(script: String) = srcRegex.find(script)?.groupValues?.get(1)?.trim()

    private fun extractSubs(script: String) = subsRegex.find(script)?.groupValues?.get(1)?.trim()

    @Serializable
    data class SubtitleDto(val file: String, val label: String)
}

private const val STREAM_SILK_URL = "https://streamsilk.com"
