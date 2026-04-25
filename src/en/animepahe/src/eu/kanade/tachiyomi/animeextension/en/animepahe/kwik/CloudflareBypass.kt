package eu.kanade.tachiyomi.animeextension.en.animepahe.kwik
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class CloudFlareBypassResult(
    val cookies: String,
    val userAgent: String,
)

class CloudflareBypass(private val context: Context) {

    fun getCookies(pageUrl: String): CloudFlareBypassResult? {
        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null

        // We MUST jump to the Main Thread because WebView is UI-bound
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            val defaultUserAgent = webView.settings.userAgentString

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    pollForClearance(view, pageUrl, defaultUserAgent) { bypassResult ->
                        result = bypassResult
                        latch.countDown() // Release the background thread
                    }
                }
            }

            CookieManager.getInstance().setCookie(pageUrl, "")
            webView.loadUrl(pageUrl)
        }

        // Wait here for up to 30 seconds
        latch.await(30, TimeUnit.SECONDS)
        return result
    }

    private fun pollForClearance(
        view: WebView,
        url: String,
        userAgent: String,
        onComplete: (CloudFlareBypassResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(url)

                if (cookies?.contains("cf_clearance=") == true) {
                    val finalResult = CloudFlareBypassResult(cookies, userAgent)
                    view.destroy()
                    onComplete(finalResult)
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(runnable)
    }
}
