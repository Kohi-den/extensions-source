package eu.kanade.tachiyomi.lib.streamhidevidextractor

import android.util.Log
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamHideVidExtractor(private val client: OkHttpClient, private val headers: Headers) {


    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamHideVid - $quality" }): List<Video> {

        val doc = client.newCall(GET(getEmbedUrl(url), headers)).execute().asJsoup()

        val scriptBody = doc.selectFirst("script:containsData(m3u8)")?.data()
            ?.let { script ->
                if (script.contains("eval(function(p,a,c")) {
                    JsUnpacker.unpackAndCombine(script)
                } else {
                    script
                }
            }
        val masterUrl = scriptBody
            ?.substringAfter("source", "")
            ?.substringAfter("file:\"", "")
            ?.substringBefore("\"", "")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        Log.d("StreamHideVidExtractor", "Playlist URL: $masterUrl")
        return playlistUtils.extractFromHls(masterUrl, url, videoNameGen = videoNameGen)
    }

     private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }
}
