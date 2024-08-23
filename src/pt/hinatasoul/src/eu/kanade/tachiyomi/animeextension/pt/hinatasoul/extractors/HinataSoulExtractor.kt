package eu.kanade.tachiyomi.animeextension.pt.hinatasoul.extractors

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

class HinataSoulExtractor(
    private val headers: Headers,
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {

    private fun getAdsUrl(
        serverUrl: String,
        thumbUrl: String,
        link: String,
        linkHeaders: Headers,
    ): String {
        val videoName = serverUrl.split('/').last()

        val docLink = client.newCall(GET(link, headers = linkHeaders)).execute().asJsoup()

        val refresh = docLink.selectFirst("meta[http-equiv=refresh]")?.attr("content")

        if (!refresh.isNullOrBlank()) {
            val newLink = refresh.substringAfter("=")
            val newHeaders = linkHeaders.newBuilder().set("Referer", link).build()
            Log.d("HinataSoulExtractor", "Following link redirection to $newLink")

            return getAdsUrl(serverUrl, thumbUrl, newLink, newHeaders)
        }

        Log.d("HinataSoulExtractor", "Fetching ADS URL")

        val newHeaders = linkHeaders.newBuilder().set("Referer", link).build()

        try {
            val adsUrl =
                client.newCall(
                    GET(
                        "$SITE_URL/playerricas.php?name=apphd/$videoName&img=$thumbUrl&url=$serverUrl",
                        headers = newHeaders,
                    ),
                )
                    .execute()
                    .body.string()
                    .substringAfter("ADS_URL")
                    .substringAfter('"')
                    .substringBefore('"')

            if (adsUrl.startsWith("http")) {
                Log.d("HinataSoulExtractor", "ADS URL: $adsUrl")
                return adsUrl
            }
        } catch (e: Exception) {
        }

        // Try default url
        Log.e("HinataSoulExtractor", "Failed to get the ADS URL, trying the default")
        return "https://www.popads.net/js/adblock.js"
    }

    private fun getAuthCode(serverUrl: String, thumbUrl: String, link: String): String {
        var authCode = preferences.getString(PREF_AUTHCODE_KEY, "")!!

        if (authCode.isNotBlank()) {
            Log.d("HinataSoulExtractor", "AuthCode found in preferences")

            val isSuccessful = client.newCall(GET("${serverUrl}$authCode", headers = headers))
                .execute().isSuccessful

            if (isSuccessful) {
                Log.d("HinataSoulExtractor", "AuthCode is OK")
                return authCode
            }
            Log.d("HinataSoulExtractor", "AuthCode is invalid")
        }

        Log.d("HinataSoulExtractor", "Fetching new authCode")

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
                "HinataSoulExtractor",
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
            Log.d("HinataSoulExtractor", "Auth code fetched successfully")
            preferences.edit().putString(PREF_AUTHCODE_KEY, authCode).commit()
        } else {
            Log.e(
                "HinataSoulExtractor",
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

        val firstLink = doc.selectFirst("div.video_container > a, div.playerContainer > a")!!.attr("href")

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
