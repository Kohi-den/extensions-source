package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import kotlin.math.floor

object VideoFetcher {
    private fun generateSignature(time: Long, sessionToken: String?): String {
        val base = "c1{$time}{$sessionToken}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun fetchVideoListPremium(episode: SEpisode, client: OkHttpClient, headers: Headers, authCookie: String, sessionToken: String, userLicense: String): List<Video> {
        val videoId = episode.url.substringAfter("?id=")
        val time = floor(System.currentTimeMillis() / 1000.0).toLong()
        val signature = generateSignature(time, sessionToken)

        val manifestHeaders = Headers.Builder()
            .add("authority", "h.freeanimehentai.net")
            .add("accept", "application/json")
            .add("accept-language", "en-GB,en-US;q=0.9,en;q=0.8")
            .add("origin", "https://hanime.tv")
            .add("referer", "https://hanime.tv/")
            .add("cookie", authCookie)
            .add("x-session-token", sessionToken)
            .add("x-signature", signature)
            .add("x-signature-version", "web2")
            .add("x-time", time.toString())
            .add("x-user-license", userLicense)
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
            .build()

        val request = Request.Builder()
            .url("https://h.freeanimehentai.net/api/v8/member/videos/$videoId/manifest")
            .headers(manifestHeaders)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseString = response.body.string()

        return try {
            val videoModel = responseString.parseAs<VideoModel>()
            videoModel.videosManifest?.servers
                ?.flatMap { server ->
                    server.streams
                        .map { stream ->
                            Video(stream.url, "Premium - ${server.name ?: "Server"} - ${stream.height}p", stream.url)
                        }
                }?.distinctBy { it.url } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetchVideoListGuest(episode: SEpisode, client: OkHttpClient, headers: Headers): List<Video> {
        val videoId = episode.url.substringAfter("?id=")
        val time = floor(System.currentTimeMillis() / 1000.0).toLong()
        val signature = generateSignature(time, null)

        val manifestHeaders = Headers.Builder()
            .add("authority", "cached.freeanimehentai.net")
            .add("accept", "application/json")
            .add("accept-language", "en-GB,en-US;q=0.9,en;q=0.8")
            .add("origin", "https://hanime.tv")
            .add("referer", "https://hanime.tv/")
            .add("x-signature", signature)
            .add("x-signature-version", "web2")
            .add("x-time", time.toString())
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
            .build()

        val request = Request.Builder()
            .url("https://cached.freeanimehentai.net/api/v8/guest/videos/$videoId/manifest")
            .headers(manifestHeaders)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseString = response.body.string()

        return try {
            val videoModel = responseString.parseAs<VideoModel>()
            videoModel.videosManifest?.servers
                ?.flatMap { server ->
                    server.streams
                        .filter { it.isGuestAllowed == true }
                        .map { stream ->
                            Video(stream.url, "${server.name ?: "Server"} - ${stream.height}p", stream.url)
                        }
                }?.distinctBy { it.url } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
