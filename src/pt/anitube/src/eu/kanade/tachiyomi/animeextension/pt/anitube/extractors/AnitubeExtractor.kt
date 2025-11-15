package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import android.content.SharedPreferences
import android.util.Base64
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
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AnitubeExtractor(
    private val headers: Headers,
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {

    private val tag by lazy { javaClass.simpleName }

    private data class VideoInfo(
        val path: String,
        val url: String,
        val quality: String,
    )

    private fun buildApiHeaders(referer: String = SITE_URL): Headers {
        return headers.newBuilder()
            .set("Referer", "https://${referer.toHttpUrl().host}/")
            .add("Accept", "*/*")
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
            .add("Connection", "keep-alive")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-site")
            .build()
    }

    private fun extractPublicidadeCode(response: String): String {
        return response
            .substringAfter("\"publicidade\"")
            .substringAfter('"')
            .substringBefore('"')
    }

    private fun normalizeLink(link: String): String {
        return if (link.startsWith("//")) "https:$link" else link
    }

    private fun getAdsUrl(
        videoUrl: String,
        thumbUrl: String,
        link: String,
        linkHeaders: Headers,
    ): String {
        val videoName = videoUrl.split('/').last()
        val finalLink = normalizeLink(link)
        val response = client.newCall(GET(finalLink, headers = linkHeaders)).execute()
        val docLink = response.asJsoup()

        // Handle meta refresh redirect
        docLink.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { refresh ->
                val newLink = refresh.substringAfter("=")
                val newHeaders = linkHeaders.newBuilder().set("Referer", finalLink).build()
                return getAdsUrl(videoUrl, thumbUrl, newLink, newHeaders)
            }

        // Handle JavaScript redirect
        docLink.data().takeIf { it.contains("window.location.href = redirectUrl") }
            ?.let { data ->
                val newLink = data
                    .substringAfter("redirectUrl = `")
                    .substringBefore("`")
                    .replace("\${token}", finalLink.toHttpUrl().queryParameter("t") ?: "")
                val newHeaders = linkHeaders.newBuilder().set("Referer", finalLink).build()
                return getAdsUrl(videoUrl, thumbUrl, newLink, newHeaders)
            }

        val referer = docLink.location() ?: link
        val newHeaders = linkHeaders.newBuilder()
            .set("Referer", "https://${referer.toHttpUrl().host}/")
            .build()

        return try {
            val now = System.currentTimeMillis()
            val body = client.newCall(
                GET(
                    "$SITE_URL?name=apphd/$videoName&img=$thumbUrl&pais=pais=BR&time=$now&url=$videoUrl",
                    headers = newHeaders,
                ),
            ).execute().body.string()

            ADS_URL_REGEX.find(body)?.groups?.get(1)?.value
                ?.takeIf { it.startsWith("http") }
                ?: throw IllegalStateException("No valid ADS URL found")
        } catch (e: Exception) {
            Log.e(tag, "Failed to get ADS URL: ${e.message}")
            "https://widgets.outbrain.com/outbrain.js"
        }
    }

    private fun getAuthCode(serverUrl: String, thumbUrl: String, link: String): String? {
        return try {
            // Check cached auth code
            preferences.getString(PREF_AUTHCODE_KEY, "")
                ?.takeIf { it.isNotBlank() && isValidAuthCode(it, serverUrl) }
                ?.also { Log.d(tag, "Using cached auth code") }
                ?: fetchNewAuthCode(serverUrl, thumbUrl, link)
        } catch (e: Exception) {
            Log.e(tag, "Error getting auth code: ${e.message}")
            preferences.edit().putString(PREF_AUTHCODE_KEY, "").commit()
            null
        }
    }

    private fun isValidAuthCode(authCode: String, serverUrl: String): Boolean {
        return try {
            val authArgs = String(Base64.decode(authCode.substringAfter("="), Base64.DEFAULT))
            val url = "$serverUrl?$authArgs".toHttpUrl()

            val serverTime = url.queryParameter("server_time")
                ?.let { SimpleDateFormat("M/d/yyyy h:m:s a", Locale.ENGLISH).parse(it) }
                ?: return false

            val validMinutes = url.queryParameter("validminutes")?.toInt() ?: return false
            val expirationTime = Calendar.getInstance().apply {
                time = serverTime
                add(Calendar.MINUTE, validMinutes)
            }

            Calendar.getInstance() < expirationTime
        } catch (e: Exception) {
            Log.d(tag, "Auth code validation failed: ${e.message}")
            false
        }
    }

    private fun fetchNewAuthCode(videoUrl: String, thumbUrl: String, link: String): String? {
        Log.d(tag, "Fetching new auth code")

        val adsUrl = getAdsUrl(videoUrl, thumbUrl, link, headers)
        val adsContent = client.newCall(GET(adsUrl)).execute().body.string()

        val body = FormBody.Builder()
            .add("category", "client")
            .add("type", "premium")
            .add("ad", adsContent)
            .add("url", videoUrl)
            .build()

        val response = client.newCall(POST(ADS_URL, headers = buildApiHeaders(), body = body))
            .execute()
            .body.string()

        val authCode = extractPublicidadeCode(response)

        return authCode.takeIf { it.startsWith("?wmsAuthSign=") }
            ?.also {
                preferences.edit().putString(PREF_AUTHCODE_KEY, it).commit()
                Log.d(tag, "Auth code fetched successfully")
            }
            ?: run {
                Log.e(tag, "Failed to fetch auth code, response: $authCode")
                preferences.edit().putString(PREF_AUTHCODE_KEY, "").commit()
                null
            }
    }

    private fun getVideoToken(videoUrl: String, authCode: String?): String {
        val token = authCode ?: "undefined"

        return try {
            val response = client.newCall(
                GET("$ADS_URL?token=$token&url=$videoUrl", headers = buildApiHeaders()),
            ).execute().body.string()

            val videoToken = extractPublicidadeCode(response)

            videoToken.takeIf { it.startsWith("?") }
                ?.also { Log.d(tag, "Video token fetched successfully") }
                ?: run {
                    Log.e(tag, "Failed to fetch video token, response: $videoToken")
                    ""
                }
        } catch (e: Exception) {
            Log.e(tag, "Error fetching video token: ${e.message}")
            ""
        }
    }

    fun getVideoList(doc: Document): List<Video> {
        val hasFHD = doc.selectFirst("div.abaItem:contains(FULLHD)") != null
        val serverUrl = doc.selectFirst("meta[itemprop=contentURL]")!!.attr("content")
        val thumbUrl = doc.selectFirst("meta[itemprop=thumbnailUrl]")!!.attr("content")
        val filename = serverUrl.split("/").last()
        val firstLink = doc.selectFirst("div.video_container > a, div.playerContainer > a")!!
            .attr("href")

        val baseVideoUrl = "$CDN_URL/ccc/$filename"
        val qualities = listOfNotNull("SD", "HD", if (hasFHD) "FULLHD" else null)
        val paths = listOf("333", "ccc", "fuh")

        val authCode = getAuthCode(baseVideoUrl, thumbUrl, firstLink)

        return qualities
            .mapIndexed { index, quality ->
                VideoInfo(
                    path = paths[index],
                    url = baseVideoUrl.replace("ccc", paths[index]),
                    quality = "$quality - Anitube",
                )
            }
            .parallelCatchingFlatMapBlocking { videoInfo ->
                val videoToken = getVideoToken(videoInfo.url, authCode)
                if (videoToken.isNotBlank()) {
                    val finalUrl = videoInfo.url + videoToken
                    listOf(Video(finalUrl, videoInfo.quality, finalUrl, headers = headers))
                } else {
                    emptyList()
                }
            }
            .reversed()
    }

    companion object {
        private const val PREF_AUTHCODE_KEY = "authcode"
        private const val CDN_URL =
            "https://c844d6af0819d9944e86e96864a9a175.r2.cloudflarestorage.com"
        private const val ADS_URL = "https://ads.anitube.vip/adblock2.php"
        private const val SITE_URL = "https://www.anitube.vip/playerricas.php"
        private val ADS_URL_REGEX = Regex("""(?:urlToFetch|ADS_URL)\s*=\s*['"]([^'"]+)['"]""")
    }
}
