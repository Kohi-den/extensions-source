package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.net.ProtocolException

class AnitubeExtractor(
    private val headers: Headers,
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {

    private val tag by lazy { javaClass.simpleName }

    private data class VideoExists(
        val exists: Boolean,
        val code: Int,
    )

    private fun checkVideoExists(url: String): VideoExists {
        try {
            val newHeaders = headers.newBuilder()
                .set("Connection", "close")
                .build()

            val request = Request.Builder()
                .head()
                .url(url)
                .headers(newHeaders)
                .build()

            val response = client.newCall(request).execute()

            return VideoExists(response.isSuccessful, response.code)
        } catch (e: ProtocolException) {
            // There are a bug in the response that sometimes that the content is without headers
            if (e.message?.contains("Unexpected status line") == true) {
                return VideoExists(true, 200)
            }
        } catch (e: Exception) {
            Log.d(tag, "Failed to check video, error: ${e.message}")
        }

        return VideoExists(false, 404)
    }

    private fun getAdsUrl(
        serverUrl: String,
        thumbUrl: String,
        link: String,
        linkHeaders: Headers,
    ): String {
        val videoName = serverUrl.split('/').last()

        val finalLink =
            if (link.startsWith("//")) {
                "https:$link"
            } else {
                link
            }
        Log.d(tag, "Accessing the link $finalLink")
        val response = client.newCall(GET(finalLink, headers = linkHeaders)).execute()
        val docLink = response.asJsoup()

        val refresh = docLink.selectFirst("meta[http-equiv=refresh]")?.attr("content")

        if (!refresh.isNullOrBlank()) {
            val newLink = refresh.substringAfter("=")
            val newHeaders = linkHeaders.newBuilder().set("Referer", finalLink).build()
            Log.d(tag, "Following link redirection to $newLink")

            return getAdsUrl(serverUrl, thumbUrl, newLink, newHeaders)
        }

        if (docLink.data().contains("window.location.href = redirectUrl")) {
            val newLink = docLink.data()
                .substringAfter("redirectUrl = `")
                .substringBefore("`")
                .replace("\${token}", finalLink.toHttpUrl().queryParameter("t") ?: "")
            val newHeaders = linkHeaders.newBuilder().set("Referer", finalLink).build()
            Log.d(tag, "Following javascript redirection to $newLink")

            return getAdsUrl(serverUrl, thumbUrl, newLink, newHeaders)
        }

        val referer: String = docLink.location() ?: link

        Log.d(tag, "Final URL: $referer")
        Log.d(tag, "Fetching ADS URL")

        val newHeaders =
            linkHeaders.newBuilder().set("Referer", "https://${referer.toHttpUrl().host}/").build()

        try {
            val now = System.currentTimeMillis()
            val body = client.newCall(
                GET(
                    "$SITE_URL?name=apphd/$videoName&img=$thumbUrl&pais=pais=BR&time=$now&url=$serverUrl",
                    headers = newHeaders,
                ),
            )
                .execute()
                .body.string()

            val adsUrl = body.let {
                Regex("""(?:urlToFetch|ADS_URL)\s*=\s*['"]([^'"]+)['"]""")
                    .find(it)?.groups?.get(1)?.value
                    ?: ""
            }

            if (adsUrl.startsWith("http")) {
                Log.d(tag, "ADS URL: $adsUrl")
                return adsUrl
            }
        } catch (e: Exception) {
            Log.e(tag, e.toString())
        }

        // Try default url
        Log.e(tag, "Failed to get the ADS URL, trying the default")
        return "https://widgets.outbrain.com/outbrain.js"
    }

    private fun getAuthCode(serverUrl: String, thumbUrl: String, link: String): String {
        var authCode = preferences.getString(PREF_AUTHCODE_KEY, "")!!

        if (authCode.isNotBlank()) {
            Log.d(tag, "AuthCode found in preferences")

            val response = checkVideoExists("${serverUrl}$authCode")

            if (response.exists || response.code == 500) {
                Log.d(tag, "AuthCode is OK")
                return authCode
            }
            Log.d(tag, "AuthCode is invalid")
        }

        Log.d(tag, "Fetching new authCode")

        val adsUrl = getAdsUrl(serverUrl, thumbUrl, link, headers)

        val adsContent = client.newCall(GET(adsUrl)).execute().body.string()

        val body = FormBody.Builder()
            .add("category", "client")
            .add("type", "premium")
            .add("ad", adsContent)
            .build()

        val newHeaders = headers.newBuilder()
            .set("Referer", "https://${SITE_URL.toHttpUrl().host}/")
            .add("Accept", "*/*")
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
            .add("Connection", "keep-alive")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-site")
            .build()

        val publicidade =
            client.newCall(POST(ADS_URL, headers = newHeaders, body = body))
                .execute()
                .body.string()
                .substringAfter("\"publicidade\"")
                .substringAfter('"')
                .substringBefore('"')

        if (publicidade.isBlank()) {
            Log.e(
                tag,
                "Failed to fetch \"publicidade\" code, the current response: $publicidade",
            )

            throw Exception("Por favor, abra o vídeo uma vez no navegador para liberar o IP")
        }

        authCode =
            client.newCall(
                GET(
                    "$ADS_URL?token=$publicidade",
                    headers = newHeaders,
                ),
            )
                .execute()
                .body.string()
                .substringAfter("\"publicidade\"")
                .substringAfter('"')
                .substringBefore('"')

        if (authCode.startsWith("?")) {
            Log.d(tag, "Auth code fetched successfully")
            preferences.edit().putString(PREF_AUTHCODE_KEY, authCode).commit()
        } else {
            Log.e(
                tag,
                "Failed to fetch auth code, the current response: $authCode",
            )
        }

        return authCode
    }

    fun getVideoList(doc: Document): List<Video> {
        val hasFHD = doc.selectFirst("div.abaItem:contains(FULLHD)") != null
        val serverUrl = doc.selectFirst("meta[itemprop=contentURL]")!!
            .attr("content")
            .replace("cdn1", "cdn3")
            .replace("cdn80", "cdn8")
        val thumbUrl = doc.selectFirst("meta[itemprop=thumbnailUrl]")!!
            .attr("content")
        val type = serverUrl.split("/").get(3)
        val qualities = listOfNotNull("SD", "HD", if (hasFHD) "FULLHD" else null)
        val paths = listOf("appsd", "apphd").let {
            if (type.endsWith("2")) {
                it.map { path -> path + "2" }
            } else {
                it
            }
        } + listOf("appfullhd")

        val firstLink =
            doc.selectFirst("div.video_container > a, div.playerContainer > a")!!.attr("href")

        val authCode = getAuthCode(serverUrl, thumbUrl, firstLink)

        return qualities
            .mapIndexed { index, quality ->
                object {
                    var path = paths[index]
                    var url = serverUrl.replace(type, path) + authCode
                    var quality = "$quality - Anitube"
                }
            }
            .parallelCatchingFlatMapBlocking {
                if (!checkVideoExists(it.url).exists) {
                    Log.d(tag, "Video not exists: ${it.url.substringBefore("?")}")
                    return@parallelCatchingFlatMapBlocking emptyList()
                }
                listOf(Video(it.url, it.quality, it.url, headers = headers))
            }
            .reversed()
    }

    companion object {
        private const val PREF_AUTHCODE_KEY = "authcode"
        private const val ADS_URL = "https://ads.anitube.vip/adblockturbo.php"
        private const val SITE_URL = "https://www.anitube.vip/playerricas.php"
    }
}
