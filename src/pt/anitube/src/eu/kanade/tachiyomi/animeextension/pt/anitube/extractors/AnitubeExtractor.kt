package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class AnitubeExtractor(
    private val headers: Headers,
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {

    private val tag by lazy { javaClass.simpleName }

    private fun getAdsUrl(
        serverUrl: String,
        thumbUrl: String,
        link: String,
        linkHeaders: Headers,
    ): String {
        val videoName = serverUrl.split('/').last()

        Log.d(tag, "Accessing the link $link")
        val response = client.newCall(GET(link, headers = linkHeaders)).execute()
        val docLink = response.asJsoup()

        val refresh = docLink.selectFirst("meta[http-equiv=refresh]")?.attr("content")

        if (!refresh.isNullOrBlank()) {
            val newLink = refresh.substringAfter("=")
            val newHeaders = linkHeaders.newBuilder().set("Referer", link).build()
            Log.d(tag, "Following link redirection to $newLink")

            return getAdsUrl(serverUrl, thumbUrl, newLink, newHeaders)
        }

        val referer: String = docLink.location() ?: link

        Log.d(tag, "Final URL: $referer")
        Log.d(tag, "Fetching ADS URL")

        val newHeaders = linkHeaders.newBuilder().set("Referer", referer).build()

        try {
            val now = System.currentTimeMillis()
            val adsUrl =
                client.newCall(
                    GET(
                        "$SITE_URL/playerricas.php?name=apphd/$videoName&img=$thumbUrl&pais=pais=BR&time=$now&url=$serverUrl",
                        headers = newHeaders,
                    ),
                )
                    .execute()
                    .body.string()
                    .let {
                        Regex("""ADS_URL\s*=\s*['"]([^'"]+)['"]""")
                            .find(it)?.groups?.get(1)?.value
                            ?: ""
                    }

            if (adsUrl.startsWith("http")) {
                Log.d(tag, "ADS URL: $adsUrl")
                return adsUrl
            }
        } catch (e: Exception) {
        }

        // Try default url
        Log.e(tag, "Failed to get the ADS URL, trying the default")
        return "https://www.popads.net/js/adblock.js"
    }

    private fun getAuthCode(serverUrl: String, thumbUrl: String, link: String): String {
        var authCode = preferences.getString(PREF_AUTHCODE_KEY, "")!!

        if (authCode.isNotBlank()) {
            Log.d(tag, "AuthCode found in preferences")

            val request = Request.Builder()
                .head()
                .url("${serverUrl}$authCode")
                .headers(headers)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful || response.code == 500) {
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
            .set("Referer", SITE_URL)
            .add("Accept", "*/*")
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
            .add("Connection", "keep-alive")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-site")
            .build()

        val publicidade =
            client.newCall(POST("$ADS_URL/", headers = newHeaders, body = body))
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
                    "$ADS_URL/?token=$publicidade",
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

    fun getVideoList(response: Response): List<Video> {
        val doc = response.asJsoup()
        val hasFHD = doc.selectFirst("div.abaItem:contains(FULLHD)") != null
        val serverUrl = doc.selectFirst("meta[itemprop=contentURL]")!!
            .attr("content")
            .replace("cdn1", "cdn3")
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

        return qualities.mapIndexed { index, quality ->
            val path = paths[index]
            val url = serverUrl.replace(type, path) + authCode
            Video(url, quality, url, headers = headers)
        }.reversed()
    }

    companion object {
        private const val PREF_AUTHCODE_KEY = "authcode"
        private const val ADS_URL = "https://ads.anitube.vip"
        private const val SITE_URL = "https://www.anitube.vip"
    }
}
