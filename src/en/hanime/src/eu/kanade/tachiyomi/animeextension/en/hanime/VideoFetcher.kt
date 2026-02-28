package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import kotlin.math.floor

object VideoFetcher {
    private fun generateSignature(time: Long, sessionToken: String?): String {
        val base = if (sessionToken != null) {
            "c1{$time}{$sessionToken}"
        } else {
            "c1{$time}{}"
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun fetchVideoList(slug: String, sessionToken: String?, userLicense: String?, client: OkHttpClient, headers: Headers): List<Video> {
        val metaRequest = Request.Builder()
            .url("https://hanime.tv/api/v8/video?id=$slug")
            .addHeader("accept", "application/json")
            .addHeader("referer", "https://hanime.tv/")
            .addHeader("origin", "https://hanime.tv")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        val metaResponse = client.newCall(metaRequest).execute()
        if (!metaResponse.isSuccessful) {
            return emptyList()
        }

        val metaJson = metaResponse.body.string()
        val videoModel = metaJson.parseAs<VideoModel>()
        val videoId = videoModel.hentaiVideo?.id ?: return emptyList()
        val baseApi = videoModel.playerBaseUrl?.trimEnd('/') ?: "https://hanime.tv"

        val route = if (sessionToken != null) "member" else "guest"
        val manifestUrl = "$baseApi/api/v8/$route/videos/$videoId/manifest"

        val time = floor(System.currentTimeMillis() / 1000.0).toLong()
        val signature = generateSignature(time, sessionToken)

        val manifestHeadersBuilder = Headers.Builder()
            .add("authority", "h.freeanimehentai.net")
            .add("accept", "application/json")
            .add("accept-language", "en-GB,en-US;q=0.9,en;q=0.8")
            .add("origin", "https://hanime.tv")
            .add("referer", "https://hanime.tv/")
            .add("sec-ch-ua", "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"")
            .add("sec-ch-ua-mobile", "?1")
            .add("sec-ch-ua-platform", "\"Android\"")
            .add("sec-fetch-dest", "empty")
            .add("sec-fetch-mode", "cors")
            .add("sec-fetch-site", "cross-site")
            .add("x-signature", signature)
            .add("x-signature-version", "web2")
            .add("x-time", time.toString())
            .add("x-csrf-token", "")
            .add("x-license", "")
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")

        if (sessionToken != null) {
            manifestHeadersBuilder.add("x-session-token", sessionToken)
        }
        if (userLicense != null) {
            manifestHeadersBuilder.add("x-user-license", userLicense)
        }

        val manifestRequest = Request.Builder()
            .url(manifestUrl)
            .headers(manifestHeadersBuilder.build())
            .get()
            .build()

        return try {
            val manifestResponse = client.newCall(manifestRequest).execute()
            if (!manifestResponse.isSuccessful) {
                return emptyList()
            }
            val manifestString = manifestResponse.body.string()
            val manifestData = manifestString.parseAs<VideosManifest>()

            manifestData.servers
                ?.flatMap { server ->
                    server.streams.mapNotNull { stream ->
                        try {
                            val allowed = if (sessionToken != null) {
                                stream.isMemberAllowed == true || stream.isPremiumAllowed == true
                            } else {
                                stream.isGuestAllowed == true
                            }
                            if (!allowed) return@mapNotNull null
                            val quality = when {
                                stream.height?.contains("1080") == true -> "1080p"
                                stream.height?.contains("720") == true -> "720p"
                                stream.height?.contains("480") == true -> "480p"
                                stream.height?.contains("360") == true -> "360p"
                                else -> (stream.height ?: "auto").replace("p", "") + "p"
                            }
                            val serverName = server.name ?: "Server"
                            val label = if (sessionToken != null) "Premium - $serverName - $quality" else "$serverName - $quality"
                            Video(stream.url, label, stream.url)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }?.distinctBy { it.url } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
