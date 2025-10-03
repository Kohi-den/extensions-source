package eu.kanade.tachiyomi.lib.megacloudextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor.VideoDto
import kotlinx.serialization.json.Json
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewResolver(private val globalHeaders: Headers) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val json: Json by injectLazy()
    private val tag by lazy { javaClass.simpleName }

    class JsInterface(private val latch: CountDownLatch) {
        var result: String? = null

        @JavascriptInterface
        fun setResponse(response: String) {
            Log.d("WebViewResolver", "script result: $response")
            result = response
            latch.countDown()
        }
    }

    fun getJsContent(file: String): String {
        return javaClass.getResource(file)!!.readText()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getSources(xrax: String): VideoDto? {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val jsi = JsInterface(latch)

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

            webview.addJavascriptInterface(jsi, "jsinterface")

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(tag, "onPageFinished $url")
                    super.onPageFinished(view, url)

                    Log.d(tag, "injecting scripts")
                    view?.evaluateJavascript(getJsContent("/assets/crypto-js.js")) {}
                    view?.evaluateJavascript(getJsContent("/assets/megacloud.decodedpng.js")) {}
                    view?.evaluateJavascript(getJsContent("/assets/megacloud.getsrcs.js")) {}

                    Log.d(tag, "running script")
                    view?.evaluateJavascript(
                        "getSources(\"${xrax}\")" +
                            ".then( s => jsinterface.setResponse( JSON.stringify(s) ) )",
                    ) {}
                }
            }

            webview.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(
                        tag,
                        "Chrome: [${consoleMessage?.messageLevel()}]" +
                            "${consoleMessage?.message()}" +
                            " at ${consoleMessage?.lineNumber()}" +
                            " in ${consoleMessage?.sourceId()}",
                    )
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            val headers = mapOf("X-Requested-With" to "org.lineageos.jelly")

            webView?.loadUrl("https://megacloud.tv/about", headers)
        }  

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return jsi.result?.let { json.decodeFromString<VideoDto>(it) }
    }

    companion object {
        const val TIMEOUT_SEC: Long = 30
    }
}
