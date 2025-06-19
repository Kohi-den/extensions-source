package eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors

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
    private val tag by lazy { javaClass.simpleName }
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers): List<Video> {
        Log.d(tag, "Fetching videos from: $origRequestUrl")
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
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(tag, "Page loaded, injecting script")
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    Log.d(tag, "Intercepted URL: $url")
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        resultUrl = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl("$origRequestUrl&dl=1", headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return when {
            "m3u8" in resultUrl -> {
                Log.d(tag, "m3u8 URL: $resultUrl")
                playlistUtils.extractFromHls(resultUrl, origRequestUrl, videoNameGen = { "$host: $it" })
            }
            "mpd" in resultUrl -> {
                Log.d(tag, "mpd URL: $resultUrl")
                playlistUtils.extractFromDash(resultUrl, { it -> "$host: $it" }, referer = origRequestUrl)
            }
            "mp4" in resultUrl -> {
                Log.d(tag, "mp4 URL: $resultUrl")
                Video(resultUrl, "$host: mp4", resultUrl, Headers.headersOf("referer", origRequestUrl)).let(::listOf)
            }
            else -> emptyList()
        }
    }

    private fun String.proper(): String {
        return this.replaceFirstChar {
            if (it.isLowerCase()) {
                it.titlecase(
                    Locale.getDefault(),
                )
            } else it.toString()
        }
    }

    companion object {
        const val TIMEOUT_SEC: Long = 10
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$") }
        private val CHECK_SCRIPT by lazy {
            """
            setInterval(() => {
                var playButton = document.getElementById('player-button-container')
                if (playButton) {
                    playButton.click()
                }
                var downloadButton = document.querySelector(".downloader-button")
                if (downloadButton) {
                    if (downloadButton.href) {
                        location.href = downloadButton.href
                    } else {
                        downloadButton.click()
                    }
                }
            }, 2500)
            """.trimIndent()
        }
    }
}
