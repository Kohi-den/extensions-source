package eu.kanade.tachiyomi.animeextension.pt.vizer.interceptor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewResolver(private val globalHeaders: Headers) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val tag by lazy { javaClass.simpleName }

    @SuppressLint("SetJavaScriptEnabled")
    fun getUrl(origRequestUrl: String, baseUrl: String): String? {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var result: String? = null

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = globalHeaders["User-Agent"]
            }
            webview.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    Log.d(tag, "Checking url $url")
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        result = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(tag, "onPageFinished $url")
                    super.onPageFinished(view, url)

                    view?.evaluateJavascript("document.body.innerHTML += '<iframe src=\"" + origRequestUrl + "\" scrolling=\"no\" frameborder=\"0\" allowfullscreen=\"\" webkitallowfullscreen=\"\" mozallowfullscreen=\"\"></iframe>'") {}
                }
            }

            webView?.loadUrl(baseUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        return result
    }

    companion object {
        const val TIMEOUT_SEC: Long = 25
        private val VIDEO_REGEX by lazy { Regex("//(mixdrop|streamtape|warezcdn)|/video/") }
    }
}
