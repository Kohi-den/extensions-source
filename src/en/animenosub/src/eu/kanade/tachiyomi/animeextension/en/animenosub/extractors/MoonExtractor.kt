package eu.kanade.tachiyomi.animeextension.en.animenosub.extractors

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
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
    private val tag = "MoonExtractor"

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        return try {
            val userAgent = headers["User-Agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host

            if (host.contains("upn.one")) {
                Log.d(tag, "Skipping Nova (unsupported): $url")
                return emptyList()
            }

            val videoId = httpUrl.pathSegments
                .lastOrNull { it.isNotEmpty() } ?: run {
                Log.e(tag, "No videoId in: $url")
                return emptyList()
            }

            Log.d(tag, "videoId=$videoId host=$host")

            val detailsHeaders = headers.newBuilder()
                .set("Referer", "$siteUrl/")
                .set("Origin", siteUrl)
                .set("User-Agent", userAgent)
                .set("Accept", "application/json, text/plain, */*")
                .build()

            val detailsBody = client.newCall(
                GET("https://$host/api/videos/$videoId/embed/details", detailsHeaders),
            ).execute().body.string()

            val embedUrl = detailsBody
                .substringAfter("embed_frame_url", "")
                .substringAfter(":")
                .substringAfter("\"")
                .substringBefore("\"")
                .takeIf { it.isNotBlank() } ?: run {
                Log.e(tag, "No embed_frame_url in: ${detailsBody.take(300)}")
                return emptyList()
            }

            val embedHost = embedUrl.toHttpUrl().host
            Log.d(tag, "embedHost=$embedHost embedUrl=$embedUrl")

            val viewerId = UUID.randomUUID().toString().replace("-", "")
            val deviceId = UUID.randomUUID().toString().replace("-", "")
            val nowSec = System.currentTimeMillis() / 1000
            val expSec = nowSec + 600

            // Build fingerprint payload (base64url encoded JSON)
            val fingerprintPayload = """{"viewer_id":"$viewerId","device_id":"$deviceId","confidence":0.93,"iat":$nowSec,"exp":$expSec}"""
            val payloadB64 = Base64.encodeToString(
                fingerprintPayload.toByteArray(),
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
            )
            // The token format the server expects: base64url(payload).dummy_sig
            // We use a fixed dummy signature since the server validates domain via x-embed-origin
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

            val bodyString = json.encodeToString(fingerprintBody)
            val requestBody = bodyString.toRequestBody("application/json".toMediaType())
            val playbackUrl = "https://$embedHost/api/videos/$videoId/embed/playback"

            Log.d(tag, "POST $playbackUrl")
            val playbackResponse = client.newCall(POST(playbackUrl, playbackHeaders, requestBody)).execute()
            Log.d(tag, "Playback code: ${playbackResponse.code}")
            val playbackBodyStr = playbackResponse.body.string()
            Log.d(tag, "Playback body: ${playbackBodyStr.take(400)}")

            val response = json.decodeFromString<PlaybackResponse>(playbackBodyStr)

            // Decrypt AES-256-GCM payload
            val masterUrl = when {
                !response.sources.isNullOrEmpty() -> {
                    response.sources.firstOrNull()?.let { it.url ?: it.file }
                }
                response.playback != null -> {
                    val decrypted = decryptPayload(response.playback)
                    Log.d(tag, "Decrypted: ${decrypted.take(300)}")
                    val inner = json.decodeFromString<InnerResponse>(decrypted)
                    inner.sources?.firstOrNull()?.let { it.url ?: it.file }
                }
                else -> null
            }?.takeIf { it.isNotBlank() } ?: run {
                Log.e(tag, "No masterUrl found in: ${playbackBodyStr.take(300)}")
                return emptyList()
            }

            Log.d(tag, "masterUrl=$masterUrl")

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
            Log.e(tag, "MoonExtractor failed: ${e.message}", e)
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
        return String(cipher.doFinal(cipherBytes))
    }

    private fun decodeB64Url(input: String): ByteArray {
        val base64 = input.replace('-', '+').replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

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
