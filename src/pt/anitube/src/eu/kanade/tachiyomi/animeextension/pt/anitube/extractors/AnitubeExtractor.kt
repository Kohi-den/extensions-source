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

    private data class PlayerInfo(
        val playerUrl: String,
        val referer: String,
    )

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

    private fun getPlayerUrl(
        link: String,
        linkHeaders: Headers,
    ): PlayerInfo {
        val finalLink = normalizeLink(link)
        val response = client.newCall(GET(finalLink, headers = linkHeaders)).execute()
        val docLink = response.asJsoup()

        // Handle meta refresh redirect
        docLink.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { refresh ->
                val newLink = refresh.substringAfter("=")
                val newHeaders = linkHeaders.newBuilder().set("Referer", finalLink).build()
                Log.d(tag, "Redirecting using meta refresh to $newLink")
                return getPlayerUrl(newLink, newHeaders)
            }

        // Handle JavaScript redirect
        docLink.data().takeIf { it.contains("window.location.href = redirectUrl") }
            ?.let { data ->
                val newLink = data
                    .substringAfter("redirectUrl = `")
                    .substringBefore("`")
                    .replace("\${token}", finalLink.toHttpUrl().queryParameter("t") ?: "")
                val newHeaders = linkHeaders.newBuilder().set("Referer", finalLink).build()
                Log.d(tag, "Redirecting using JavaScript to $newLink")
                return getPlayerUrl(newLink, newHeaders)
            }

        val referer = docLink.location() ?: link

        Log.d(tag, "Final url: $referer")

        val playerUrl = docLink.selectFirst("iframe")?.attr("src")!!

        return PlayerInfo(playerUrl = playerUrl, referer = referer)
    }

    private fun getAuthCode(playerInfo: PlayerInfo): String? {
        return try {
            // Check cached auth code
            preferences.getString(PREF_AUTHCODE_KEY, "")
                ?.takeIf { it.isNotBlank() && isValidAuthCode(it) }
                ?.also { Log.d(tag, "Using cached auth code") }
                ?: fetchNewAuthCode(playerInfo)
        } catch (e: Exception) {
            Log.e(tag, "Error getting auth code: ${e.message}")
            preferences.edit().putString(PREF_AUTHCODE_KEY, "").commit()
            null
        }
    }

    private fun isValidAuthCode(authCode: String): Boolean {
        return try {
            val authArgs = String(Base64.decode(authCode.substringAfter("="), Base64.DEFAULT))
            val url = "https://127.0.0.1?$authArgs".toHttpUrl()

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

    private fun fetchNewAuthCode(playerInfo: PlayerInfo): String? {
        Log.d(tag, "Fetching new auth code")

        val adsUrl = try {
            val newHeaders = headers.newBuilder()
                .set("Referer", "https://${playerInfo.referer.toHttpUrl().host}/")
                .build()

            val body = client.newCall(
                GET(
                    playerInfo.playerUrl,
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

        val adsContent = client.newCall(GET(adsUrl)).execute().body.string()

        val videoUrl = playerInfo.playerUrl.toHttpUrl().queryParameter("url")!!

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
        val links =
            doc
                .select("div.video_container > a, div.playerContainer > a")
                .map { it.attr("href") }

        val qualities = listOfNotNull("SD", "HD", if (hasFHD) "FULLHD" else null)

        var authCode: String? = null

        return qualities
            .mapIndexed { index, quality ->
                val playerInfo = getPlayerUrl(links[index], headers)
                authCode = authCode ?: getAuthCode(playerInfo)
                VideoInfo(
                    path = links[index],
                    url = playerInfo.playerUrl.toHttpUrl().queryParameter("url")!!,
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
        private const val ADS_URL = "https://ads.anitube.vip/adblock2.php"
        private const val SITE_URL = "https://www.anitube.vip/playerricas.php"
        private val ADS_URL_REGEX = Regex("""(?:urlToFetch|ADS_URL)\s*=\s*['"]([^'"]+)['"]""")
    }
}
