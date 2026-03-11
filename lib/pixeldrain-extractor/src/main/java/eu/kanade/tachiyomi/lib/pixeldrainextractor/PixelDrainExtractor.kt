package eu.kanade.tachiyomi.lib.pixeldrainextractor

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

class PixelDrainExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val mId = Regex("/u/(.*)").find(url)?.groupValues?.get(1)
        return if (mId.isNullOrEmpty()) {
            listOf(Video(url, "${prefix}PixelDrain", url))
        } else {
            listOf(Video("https://pixeldrain.com/api/file/${mId}?download", "${prefix}PixelDrain", "https://pixeldrain.com/api/file/${mId}?download"))
        }
    }
}
