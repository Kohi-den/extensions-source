package eu.kanade.tachiyomi.animeextension.en.animekai

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class Extractor(private val client: OkHttpClient) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun extractVideosFromUrl(
        url: String,
        prefix: String = "",
        onResult: (List<Video>) -> Unit,
    ) {
        val videoResults = mutableListOf<Video>()
        val subtitleResults = mutableListOf<Track>()

        var masterPlaylistFound = false

        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val reqUrl = request?.url.toString()
                if (reqUrl.endsWith(".m3u8")) {
                    // If master playlist already found, ignore further .m3u8 requests
                    if (masterPlaylistFound) return super.shouldInterceptRequest(view, request)
                    try {
                        val response = client.newCall(okhttp3.Request.Builder().url(reqUrl).build()).execute()
                        val playlistContent = response.body.string()
                        if (playlistContent.contains("#EXT-X-STREAM-INF")) {
                            // Master playlist: parse qualities
                            masterPlaylistFound = true
                            videoResults.clear() // Discard any previously added unknowns
                            val lines = playlistContent.lines()
                            var currentQuality: String? = null
                            val pattern = Regex("RESOLUTION=(\\d+)x(\\d+)")
                            for (i in lines.indices) {
                                val line = lines[i]
                                if (line.startsWith("#EXT-X-STREAM-INF")) {
                                    val match = pattern.find(line)
                                    if (match != null) {
                                        val height = match.groupValues[2]
                                        currentQuality = "${height}p"
                                    }
                                    val streamUrl = lines.getOrNull(i + 1)?.trim()
                                    if (!streamUrl.isNullOrEmpty() && currentQuality != null) {
                                        val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else reqUrl.substringBeforeLast("/") + "/" + streamUrl
                                        videoResults.add(Video(reqUrl, prefix + currentQuality, absoluteUrl))
                                    }
                                }
                            }
                        } else {
                            // Not a master playlist, fallback
                            videoResults.add(Video(url, prefix + "Unknown quality", reqUrl))
                        }
                    } catch (e: Exception) {
                        videoResults.add(Video(url, prefix + "Unknown quality", reqUrl))
                    }
                } else if (reqUrl.endsWith(".vtt") && !reqUrl.contains("thumbnails", ignoreCase = true)) {
                    // Subtitle file
                    val lang = extractLabelFromUrl(reqUrl)
                    Log.d("AnimeKai", "Found subtitle: $lang -> $reqUrl")
                    subtitleResults.add(Track(reqUrl, lang))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Inject JS to click play button
                val js = """
                    (function() {
                        var btn = document.querySelector('button, .vjs-big-play-button');
                        if (btn) btn.click();
                    })();
                """
                view?.evaluateJavascript(js, null)
                handler.postDelayed({
                    if (subtitleResults.isNotEmpty()) {
                        // Attach a copy of subtitles to each video by constructing new Video objects
                        for (i in videoResults.indices) {
                            val v = videoResults[i]
                            videoResults[i] = Video(
                                url = v.url,
                                quality = v.quality,
                                videoUrl = v.videoUrl,
                                headers = v.headers,
                                subtitleTracks = subtitleResults.toList(),
                                audioTracks = v.audioTracks,
                            )
                        }
                    }
                    onResult(videoResults)
                    view?.destroy()
                }, 2000)
            }
        }

        // Instead of loading the URL directly, embed it in an iframe with the correct base URL
        val html = """
            <html style='height:100%;margin:0;padding:0;'>
              <body style='height:100%;margin:0;padding:0;'>
                <iframe src='$url' style='width:100vw;height:100vh;border:none;'></iframe>
              </body>
            </html>
        """
        webView.loadDataWithBaseURL(
            "https://animekai.to", // base URL (referer)
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    // Helper to extract language label from subtitle URL
    private fun extractLabelFromUrl(url: String): String {
        val file = url.substringAfterLast("/")
        return when (val code = file.substringBefore("_").lowercase()) {
            "eng" -> "English"
            "ger", "deu" -> "German"
            "spa" -> "Spanish"
            "fre", "fra" -> "French"
            "ita" -> "Italian"
            "jpn" -> "Japanese"
            "chi", "zho" -> "Chinese"
            "kor" -> "Korean"
            "rus" -> "Russian"
            "ara" -> "Arabic"
            "hin" -> "Hindi"
            "por" -> "Portuguese"
            "vie" -> "Vietnamese"
            "pol" -> "Polish"
            "ukr" -> "Ukrainian"
            "swe" -> "Swedish"
            "ron", "rum" -> "Romanian"
            "ell", "gre" -> "Greek"
            "hun" -> "Hungarian"
            "fas", "per" -> "Persian"
            "tha" -> "Thai"
            else -> code.uppercase()
        }
    }
}
