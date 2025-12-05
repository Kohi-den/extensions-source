package eu.kanade.tachiyomi.lib.googledriveplayerextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Extractor for Google Drive player streaming URLs.
 *
 * Unlike the standard Google Drive extractor that attempts to get download URLs,
 * this extractor focuses on extracting streaming player URLs with multiple quality options.
 *
 * It uses a WebView to intercept Google Drive's player API requests and extracts
 * streaming URLs with various quality options, supporting both progressive (video + audio)
 * and adaptive (separated video and audio tracks) streaming formats.
 *
 * @param client The OkHttpClient instance for making HTTP requests
 * @param headers The HTTP headers to use for requests
 */
class GoogleDrivePlayerExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val tag by lazy { javaClass.simpleName }
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val json: Json by injectLazy()

    /**
     * Extracts video URLs from a Google Drive URL.
     *
     * This method loads the Google Drive URL in a WebView, intercepts the playback API request,
     * extracts cookies, and then fetches the streaming data to extract video URLs with quality information.
     *
     * @param origRequestUrl The Google Drive URL to extract videos from
     * @return A list of [Video] objects with streaming URLs and quality information
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(origRequestUrl: String): List<Video> {
        Log.d(tag, "Fetching videos from: $origRequestUrl")
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var playbackUrl: String? = null

        handler.post {
            val newView = WebView(context)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = USER_AGENT // Emulate a desktop request
            }
            newView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(tag, "Page loaded")
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    Log.d(tag, "Intercepted URL: $url")
                    if (VIDEO_REGEX.containsMatchIn(url) && playbackUrl == null) {
                        playbackUrl = url
                        Log.d(tag, "Found playback URL: $url")
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val prefix = "Google Drive Player"
        val playbackUrlFinal = playbackUrl ?: return emptyList()

        // Extract cookies from WebView CookieManager
        val cookies = CookieManager.getInstance()
            ?.getCookie(origRequestUrl)
            ?.split(";")
            ?.mapNotNull { Cookie.parse(playbackUrlFinal.toHttpUrl(), it) }
            ?: emptyList()

        val cookieString = cookies.joinToString("; ") { "${it.name}=${it.value}" }

        // Make GET request to playback URL
        val requestHeaders = headers.newBuilder().apply {
            if (cookieString.isNotEmpty()) {
                set("Cookie", cookieString)
                set("Accept", "*/*")
                set("Referer", origRequestUrl)
            }
        }.build()

        return try {
            val response = client.newCall(GET(playbackUrlFinal, requestHeaders)).execute()
            val responseBody = response.body.string()
            Log.d(tag, "Response body: ${responseBody.take(200)}...")

            val streamingData = json.decodeFromString<GoogleDriveStreamingResponse>(responseBody)
            val videos = mutableListOf<Video>()

            // Process progressive transcodes
            streamingData.mediaStreamingData.formatStreamingData.progressiveTranscodes.forEach { transcode ->
                val quality = getQualityFromItag(transcode.itag, transcode.transcodeMetadata.height)
                videos.add(Video(transcode.url, "$prefix: $quality", transcode.url, requestHeaders))
            }

            // Process adaptive transcodes (separated video + audio)
            val audioTracks =
                streamingData.mediaStreamingData.formatStreamingData.adaptiveTranscodes
                    .filter { it.transcodeMetadata.mimeType.startsWith("audio/") }
                    .map { transcode ->
                        Track(transcode.url, getAudioQualityLabel(transcode))
                    }

            streamingData.mediaStreamingData.formatStreamingData.adaptiveTranscodes.forEach { transcode ->
                // Process only video streams
                if (transcode.transcodeMetadata.mimeType.startsWith("video/")) {
                    val quality =
                        getQualityFromItag(transcode.itag, transcode.transcodeMetadata.height)
                    videos.add(
                        Video(
                            transcode.url,
                            "$prefix Adaptive: $quality",
                            transcode.url,
                            requestHeaders,
                            audioTracks = audioTracks,
                        ),
                    )
                }
            }

            Log.d(tag, "Found ${videos.size} video(s)")
            videos
        } catch (e: Exception) {
            Log.e(tag, "Error fetching streaming data", e)
            emptyList()
        }
    }

    /**
     * Maps video itag to quality label.
     *
     * @param itag The video itag identifier
     * @param height The video height in pixels (used as fallback)
     * @return Quality label string (e.g., "360p", "720p", "1080p")
     */
    private fun getQualityFromItag(itag: Int, height: Int): String {
        return when (itag) {
            18, 43, 82, 134 -> "360p"
            22, 45, 84, 136 -> "720p"
            37, 46, 85, 137 -> "1080p"
            59, 44, 135 -> "480p"
            83, 133 -> "240p"
            298 -> "720p"
            299 -> "1080p"
            else -> {
                // Try to infer from height if itag is not recognized
                when {
                    height >= 1080 -> "1080p"
                    height >= 720 -> "720p"
                    height >= 480 -> "480p"
                    height >= 360 -> "360p"
                    height >= 240 -> "240p"
                    else -> "Unknown"
                }
            }
        }
    }

    /**
     * Generates a quality label for audio tracks based on bitrate.
     *
     * @param transcode The adaptive transcode containing audio metadata
     * @return Quality label string (e.g., "High Quality", "Medium Quality", "Standard Quality")
     */
    private fun getAudioQualityLabel(transcode: AdaptiveTranscode): String {
        val metadata = transcode.transcodeMetadata
        return when {
            metadata.audioCodecString != null -> {
                val bitrate = metadata.maxContainerBitrate
                when {
                    bitrate >= 192000 -> "High Quality"
                    bitrate >= 128000 -> "Medium Quality"
                    else -> "Standard Quality"
                }
            }

            else -> "Audio"
        }
    }

    companion object {
        /** Timeout in seconds for WebView loading. */
        const val TIMEOUT_SEC: Long = 10

        /** Regex pattern to match playback URLs. */
        private val VIDEO_REGEX by lazy {
            Regex(".*/playback.*")
        }

        /** User agent string to emulate a desktop browser. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    }
}

