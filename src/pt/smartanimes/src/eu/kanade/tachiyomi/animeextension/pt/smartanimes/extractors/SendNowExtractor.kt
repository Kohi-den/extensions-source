package eu.kanade.tachiyomi.animeextension.pt.smartanimes.extractors

import android.app.Application
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class SendNowExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    fun videosFromUrl(url: String, name: String): List<Video> {
        // Client hints from: https://github.com/keiyoushi/extensions-source/blob/8f70beda06a70f84c79d793367fbdf6b9ea09b5a/src/pt/mangastop/src/eu/kanade/tachiyomi/extension/pt/mangastop/ClientHintsInterceptor.kt#L27
        val userAgent = headers["User-Agent"]
            ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"

        val chromeVersion = CHROME_REGEX.find(userAgent)?.groupValues?.get(1) ?: "143"
        val isMobile = userAgent.contains("Android") || userAgent.contains("Mobile")

        val secChUa =
            "\"Google Chrome\";v=\"$chromeVersion\", \"Chromium\";v=\"$chromeVersion\", \"Not A(Brand\";v=\"24\""

        val platform = when {
            userAgent.contains("Windows") -> "\"Windows\""
            userAgent.contains("Android") -> "\"Android\""
            userAgent.contains("Mac") -> "\"macOS\""
            userAgent.contains("Linux") -> "\"Linux\""
            else -> "\"Windows\""
        }

        val newHeaders = headers.newBuilder().apply {
            removeAll("Referer")
            set(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            )
            set("Accept-Encoding", "deflate")
            set("accept-language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            set("cache-control", "max-age=600")
            set("Connection", "keep-alive")
            set("Host", url.toHttpUrl().host)
            set("sec-ch-ua", secChUa)
            set("sec-ch-ua-mobile", if (isMobile) "?1" else "?0")
            set("sec-ch-ua-platform", platform)
            set("Sec-Fetch-Dest", "document")
            set("Sec-Fetch-Mode", "navigate")
            set("Sec-Fetch-Site", "none")
            set("Sec-Fetch-User", "?1")
            set("Upgrade-Insecure-Requests", "1")
            set("User-Agent", userAgent)
        }.build()

        val document = client.newCall(GET(url, newHeaders)).execute().asJsoup()

        val source = document.selectFirst("source") ?: return emptyList()

        val videoUrl = source.attr("src")

        val videoHeaders = Headers.headersOf("Referer", "https://${url.toHttpUrl().host}/")

        return listOf(
            Video(videoUrl, name, videoUrl, videoHeaders),
        )
    }

    companion object {
        private val CHROME_REGEX = Regex("""Chrome/(\d+)""")
    }
}
