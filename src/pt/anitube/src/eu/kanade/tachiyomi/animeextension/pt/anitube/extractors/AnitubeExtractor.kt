package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class AnitubeExtractor(
    private val headers: Headers,
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {

    private val tag by lazy { javaClass.simpleName }

    private data class PlayerInfo(
        val playerUrl: String,
        val referer: String,
        val videoUrl: String,
    )

    private data class VideoInfo(
        val path: String,
        val url: String,
        val quality: String,
    )

    private fun buildApiHeaders(referer: String): Headers {
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
            .substringAfter("\"publicidade\"", "")
            .substringAfter('"')
            .substringBefore('"')
    }

    private fun normalizeLink(link: String): String {
        return if (link.startsWith("//")) "https:$link" else link
    }

    private fun fetchPlayerInfo(
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
                return fetchPlayerInfo(newLink, newHeaders)
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
                return fetchPlayerInfo(newLink, newHeaders)
            }

        docLink.selectFirst("p:contains(Novo endereço)")?.let {
            val newLink = it.selectFirst("strong")?.text()

            if (newLink?.startsWith("http") == true) {
                preferences.edit().putString("preferred_domain", newLink).apply()
                throw Exception("Configurado novo domínio, por favor reinicie o aplicativo")
            }

            throw Exception("Configure para o novo domínio: $newLink")
        }

        val referer = docLink.location() ?: link

        val playerUrl = docLink.selectFirst("iframe")?.attr("src")!!

        Log.d(tag, "Player url: $playerUrl")
        Log.d(tag, "Referer url: $referer")

        return PlayerInfo(
            playerUrl = playerUrl,
            referer = referer,
            videoUrl = playerUrl.toHttpUrl().queryParameter("url")!!,
        )
    }

    private fun fetchVideoToken(playerInfo: PlayerInfo): String? {
        Log.d(tag, "Fetching new auth code")

        val (adsUrl, adblockUrl) = try {
            val newHeaders = headers.newBuilder()
                .set("Referer", "https://${playerInfo.referer.toHttpUrl().host}/")
                .build()

            val body = client.newCall(
                GET(
                    playerInfo.playerUrl,
                    headers = newHeaders,
                ),
            ).execute().body.string()

            val ads = ADS_URL_REGEX.find(body)?.groups?.get(1)?.value
                ?.takeIf { it.startsWith("http") }
                ?: throw IllegalStateException("No valid ADS URL found")

            val adblock = body.substringAfter("$.post", "")
                .substringAfter("'")
                .substringBefore("'")
                ?.takeIf { it.startsWith("http") }
                ?: throw IllegalStateException("No valid ADBLOCK URL found")

            ads to adblock
        } catch (e: Exception) {
            Log.e(tag, "Failed to get ADS/ADBLOCK URL: ${e.message}")
            "https://widgets.outbrain.com/outbrain.js" to "https://ads.anitube.vip/adblock2.php"
        }

        val adsContent = client.newCall(GET(adsUrl)).execute().body.string()

        val videoUrl = playerInfo.playerUrl.toHttpUrl().queryParameter("url")!!

        val body = FormBody.Builder()
            .add("category", "client")
            .add("type", "premium")
            .add("ad", adsContent)
            .add("url", videoUrl)
            .build()

        val apiHeaders = buildApiHeaders(playerInfo.referer)

        val response = client.newCall(POST(adblockUrl, headers = apiHeaders, body = body))
            .execute()
            .body.string()

        val token = extractPublicidadeCode(response).ifBlank { "undefined" }

        return try {
            val response = client.newCall(
                GET("$adblockUrl?token=$token&url=$videoUrl", headers = apiHeaders),
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

    fun getVideosFromUrl(url: String, quality: String): List<Video> {
        val playerInfo = fetchPlayerInfo(url, headers)
        val videoToken = fetchVideoToken(playerInfo)
        return if (!videoToken.isNullOrBlank()) {
            val finalUrl = playerInfo.videoUrl + videoToken
            listOf(Video(finalUrl, quality, finalUrl, headers = headers))
        } else {
            emptyList()
        }
    }

    companion object {
        private val ADS_URL_REGEX = Regex("""(?:urlToFetch|ADS_URL)\s*=\s*['"]([^'"]+)['"]""")
    }
}
