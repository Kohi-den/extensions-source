package eu.kanade.tachiyomi.animeextension.all.animeonsen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class SearchInterceptor(client: OkHttpClient, baseUrl: String, searchUrl: String) : Interceptor {

    private val token: String
    private val host: String

    init {
        token = try {
            val document = client.newCall(
                GET(
                    baseUrl,
                ),
            ).execute().asJsoup()

            document.selectFirst("meta[name=ao-search-token]")?.attr("content") ?: ""
        } catch (_: Throwable) {
            ""
        }
        host = searchUrl.toHttpUrl().host
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        if (originalRequest.url.host == host) {
            val newRequest = originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()

            return chain.proceed(newRequest)
        }

        return chain.proceed(originalRequest)
    }
}
