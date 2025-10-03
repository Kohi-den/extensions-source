package eu.kanade.tachiyomi.lib.chillxextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewResolver(
    private val client: OkHttpClient,
    private val globalHeaders: Headers,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class JsInterface(private val latch: CountDownLatch) {
        var result: String? = null

        @JavascriptInterface
        fun passPayload(payload: String) {
            result = payload
            latch.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getDecryptedData(embedUrl: String): String? {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val jsi = JsInterface(latch)
        val interfaceName = randomString()

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

            webview.addJavascriptInterface(jsi, interfaceName)
            webview.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request?.url.toString().equals(embedUrl, true)) {
                        return patchBody(request!!.url.toString(), interfaceName)
                            ?: super.shouldInterceptRequest(view, request)
                    }

                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(embedUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return jsi.result
    }

    companion object {
        const val TIMEOUT_SEC: Long = 30
    }

    private fun randomString(length: Int = 10): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }

    private fun patchBody(url: String, interfaceName: String): WebResourceResponse? {
        val html = client.newCall(GET(url, globalHeaders)).execute().asJsoup()

        val oldFunc = randomString()
        val script = html.createElement("script").apply {
            appendText(
                """
                    const $oldFunc = Function;
                    window.Function = function (...args) {
                      if (args.length == 1) {
                        window.$interfaceName.passPayload(args[0]);
                      }
                      return $oldFunc(...args);
                    };
                """.trimIndent()
            )
        }

        html.body().insertChildren(0, script)

        return WebResourceResponse(
            "text/html",
            "utf-8",
            200,
            "ok",
            mapOf("server" to "cloudflare"),
            ByteArrayInputStream(html.outerHtml().toByteArray()),
        )
    }
}
