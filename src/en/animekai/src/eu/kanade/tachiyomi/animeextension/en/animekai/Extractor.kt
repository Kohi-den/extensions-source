package eu.kanade.tachiyomi.animeextension.en.animekai

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class Extractor(private val client: OkHttpClient) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun extractVideosFromUrl(
        url: String,
        onResult: (List<Video>) -> Unit,
    ) {
        val videoResults = mutableListOf<Video>()

        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val reqUrl = request?.url.toString()
                if (reqUrl.endsWith(".m3u8")) {
                    val vid = Video(url, "Unknown quality", reqUrl)
                    videoResults.add(vid)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Inject JS to click play button
                val js = """
                    (function() {
                        var btn = document.querySelector('button, .vjs-big-play-button');
                        if (btn) btn.click();
                    })();
                """
                view?.evaluateJavascript(js, null)
                handler.postDelayed({
                    onResult(videoResults)
                    view?.destroy()
                }, 2000)
            }
        }

        webView.loadUrl(url)
    }
}
