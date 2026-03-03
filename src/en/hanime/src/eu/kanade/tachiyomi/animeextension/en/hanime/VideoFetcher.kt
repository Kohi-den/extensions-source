package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

object VideoFetcher {
    fun fetchVideoListPremium(
        episode: SEpisode,
        client: OkHttpClient,
        headers: Headers,
        authCookie: String,
        sessionToken: String,
        userLicense: String,
        signature: String,
        timestamp: Long,
        videoId: String,
    ): List<Video> {
        val manifestHeaders = Headers.Builder()
            .add("Authority", "h.freeanimehentai.net")
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Origin", "https://hanime.tv")
            .add("Referer", "https://hanime.tv/")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "cross-site")
            .add("Cookie", authCookie)
            .add("x-session-token", sessionToken)
            .add("x-signature", signature)
            .add("x-signature-version", "web2")
            .add("x-time", timestamp.toString())
            .add("x-user-license", userLicense)
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        val request = Request.Builder()
            .url("https://h.freeanimehentai.net/api/v8/member/videos/$videoId/manifest")
            .headers(manifestHeaders)
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseString = response.body.string()

            if (responseString.isBlank() || responseString.contains("error") || responseString.contains("unauthorized")) {
                return emptyList()
            }

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

    fun fetchVideoListGuest(
        episode: SEpisode,
        client: OkHttpClient,
        headers: Headers,
        signature: String,
        timestamp: Long,
        videoId: String,
    ): List<Video> {
        val manifestHeaders = Headers.Builder()
            .add("Authority", "cached.freeanimehentai.net")
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Origin", "https://hanime.tv")
            .add("Referer", "https://hanime.tv/")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "cross-site")
            .add("x-signature", signature)
            .add("x-signature-version", "web2")
            .add("x-time", timestamp.toString())
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        val request = Request.Builder()
            .url("https://cached.freeanimehentai.net/api/v8/guest/videos/$videoId/manifest")
            .headers(manifestHeaders)
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseString = response.body.string()

            if (responseString.isBlank() || responseString.contains("error") || responseString.contains("unauthorized")) {
                return emptyList()
            }

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
