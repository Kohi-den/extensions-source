package eu.kanade.tachiyomi.lib.universalextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversalExtractor(private val client: OkHttpClient) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, customQuality: String? = null, prefix: String = ""): List<Video> {
        val host = origRequestUrl.toHttpUrl().host.substringBefore(".").proper()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""
        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val newView = WebView(context)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = origRequestHeader["User-Agent"]
            }
            newView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        resultUrl = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl, headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        // terabox special case start
        if ("M3U8_AUTO_360" in resultUrl) {
            val qualities = listOf("1080", "720", "480", "360")
            val allVideos = mutableListOf<Video>()

            for (quality in qualities) {
                val modifiedUrl = resultUrl.replace("M3U8_AUTO_360", "M3U8_AUTO_$quality")
                val videos = playlistUtils.extractFromHls(modifiedUrl, origRequestUrl, videoNameGen = { "$prefix - $host: $it $quality" + "p" })

                if (videos.isNotEmpty()) {
                    allVideos.addAll(videos)
                }
            }

            if (allVideos.isNotEmpty()) {
                return allVideos
            }
        }
        // terabox special case end

        return when {
            "m3u8" in resultUrl -> {
                Log.d("UniversalExtractor", "m3u8 URL: $resultUrl")
                playlistUtils.extractFromHls(resultUrl, origRequestUrl, videoNameGen = { "$prefix - $host: $it" })
            }
            "mpd" in resultUrl -> {
                Log.d("UniversalExtractor", "mpd URL: $resultUrl")
                playlistUtils.extractFromDash(resultUrl, { it -> "$prefix - $host: $it" }, referer = origRequestUrl)
            }
            "mp4" in resultUrl -> {
                Log.d("UniversalExtractor", "mp4 URL: $resultUrl")
                Video(resultUrl, "$prefix - $host: ${customQuality ?: "Mirror"}", resultUrl, origRequestHeader.newBuilder().add("referer", origRequestUrl).build()).let(::listOf)
            }
            else -> emptyList()
        }
    }

    private fun String.proper(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(
            Locale.getDefault()) else it.toString() }
    }

    companion object {
        const val TIMEOUT_SEC: Long = 10
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$") }
    }
}
