package eu.kanade.tachiyomi.animeextension.en.animenosub.extractors

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MoonExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val siteUrl: String,
) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        return try {
            val userAgent = headers["User-Agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host

            val videoId = httpUrl.pathSegments
                .lastOrNull { it.isNotEmpty() } ?: return emptyList()

            val detailsHeaders = headers.newBuilder()
                .set("Referer", "$siteUrl/")
                .set("Origin", siteUrl)
                .set("User-Agent", userAgent)
                .set("Accept", "application/json, text/plain, */*")
                .build()

            // Fix: use .use{} to prevent connection leaks
            val detailsBody = client.newCall(
                GET("https://$host/api/videos/$videoId/embed/details", detailsHeaders),
            ).execute().use { it.body.string() }

            val detailsResponse = try {
                json.decodeFromString<DetailsResponse>(detailsBody)
            } catch (e: Exception) {
                Log.e("MoonExtractor", "Failed to parse details JSON: ${e.message}")
                return emptyList()
            }

            val embedUrl = detailsResponse.embedFrameUrl?.takeIf { it.isNotBlank() }
                ?: return emptyList()

            val embedHost = embedUrl.toHttpUrl().host

            val viewerId = UUID.randomUUID().toString().replace("-", "")
            val deviceId = UUID.randomUUID().toString().replace("-", "")
            val nowSec = System.currentTimeMillis() / 1000
            val expSec = nowSec + 600

            val fingerprintPayload = """{"viewer_id":"$viewerId","device_id":"$deviceId","confidence":0.93,"iat":$nowSec,"exp":$expSec}"""
            val payloadB64 = Base64.encodeToString(
                fingerprintPayload.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
            )
            val fingerprintToken = "$payloadB64.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

            val fingerprintBody = FingerprintRequest(
                fingerprint = FingerprintData(
                    token = fingerprintToken,
                    viewerId = viewerId,
                    deviceId = deviceId,
                    confidence = 0.93,
                ),
            )

            val playbackHeaders = headers.newBuilder()
                .set("Referer", embedUrl)
                .set("Origin", "https://$embedHost")
                .set("User-Agent", userAgent)
                .set("Accept", "application/json, text/plain, */*")
                .set("Content-Type", "application/json")
                .set("X-Embed-Origin", siteUrl.removePrefix("https://"))
                .set("X-Embed-Parent", "https://$host/e/$videoId")
                .set("X-Embed-Referer", "$siteUrl/")
                .set("Sec-Fetch-Dest", "empty")
                .set("Sec-Fetch-Mode", "cors")
                .set("Sec-Fetch-Site", "same-origin")
                .build()

            val requestBody = json.encodeToString(fingerprintBody)
                .toRequestBody("application/json".toMediaType())
            val playbackUrl = "https://$embedHost/api/videos/$videoId/embed/playback"

            // Fix: use .use{} to prevent connection leaks
            val playbackBodyStr = client.newCall(POST(playbackUrl, playbackHeaders, requestBody))
                .execute().use { it.body.string() }

            val response = json.decodeFromString<PlaybackResponse>(playbackBodyStr)

            val masterUrl = when {
                !response.sources.isNullOrEmpty() -> {
                    response.sources.firstOrNull()?.let { it.url ?: it.file }
                }
                response.playback != null -> {
                    val decrypted = decryptPayload(response.playback)
                    json.decodeFromString<InnerResponse>(decrypted)
                        .sources?.firstOrNull()?.let { it.url ?: it.file }
                }
                else -> null
            }?.takeIf { it.isNotBlank() } ?: run {
                Log.e("MoonExtractor", "No masterUrl found in: ${playbackBodyStr.take(300)}")
                return emptyList()
            }

            val videoHeaders = Headers.Builder()
                .set("Referer", "https://$embedHost/")
                .set("Origin", "https://$embedHost")
                .set("User-Agent", userAgent)
                .build()

            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = "https://$embedHost/",
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { quality -> "${prefix}Moon - $quality" },
            )
        } catch (e: Exception) {
            Log.e("MoonExtractor", "MoonExtractor failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun decryptPayload(pb: PlaybackData): String {
        val keyBytes = decodeB64Url(pb.keyParts[0]) + decodeB64Url(pb.keyParts[1])
        val ivBytes = decodeB64Url(pb.iv)
        val cipherBytes = decodeB64Url(pb.payload)
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ivBytes))
        // Fix: explicit UTF-8 charset to avoid system encoding issues
        return cipher.doFinal(cipherBytes).toString(Charsets.UTF_8)
    }

    // Fix: use Base64.URL_SAFE flag directly — no need to manually replace - and _
    private fun decodeB64Url(input: String): ByteArray {
        val padding = when (input.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(input + padding, Base64.URL_SAFE)
    }

    @Serializable
    data class DetailsResponse(
        @SerialName("embed_frame_url") val embedFrameUrl: String? = null,
    )

    @Serializable
    data class FingerprintRequest(val fingerprint: FingerprintData)

    @Serializable
    data class FingerprintData(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        val payload: String,
        @SerialName("key_parts") val keyParts: List<String>,
    )

    @Serializable
    data class InnerResponse(val sources: List<VideoSource>? = null)

    @Serializable
    data class VideoSource(val file: String? = null, val url: String? = null)
}
