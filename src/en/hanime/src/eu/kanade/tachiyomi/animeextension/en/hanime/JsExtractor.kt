package eu.kanade.tachiyomi.animeextension.en.hanime

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

object JsExtractor {
    private val CLIENT = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun extractVideoData(pageUrl: String): Triple<String, Long, String> {
        return try {
            val request = Request.Builder()
                .url(pageUrl)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                )
                .build()

            val response = CLIENT.newCall(request).execute()
            val html = response.body.string()

            val videoId = extractVideoId(html) ?: ""

            val scriptUrl = extractScriptUrl(html)
            if (scriptUrl != null) {
                val scriptContent = fetchScriptContent(scriptUrl)
                val signature = extractSignature(scriptContent)
                val timestamp = extractTimestamp(scriptContent)

                Triple(signature, timestamp, videoId)
            } else {
                Triple("", 0L, videoId)
            }
        } catch (e: Exception) {
            Triple("", 0L, "")
        }
    }

    private fun extractVideoId(html: String): String? {
        val patterns = listOf(
            Pattern.compile("/api/v8/video\\?id=([^\"'&\\s]+)"),
            Pattern.compile("video_id[:\"']\\s*[\"']([^\"']+)[\"']"),
            Pattern.compile("data-video-id=[\"']([^\"']+)[\"']"),
        )

        patterns.forEach { pattern ->
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun extractScriptUrl(html: String): String? {
        val pattern = Pattern.compile("<script[^>]*src=\"([^\"]*vhtv[^\"]*\\.js)\"[^>]*>")
        val matcher = pattern.matcher(html)

        return if (matcher.find()) {
            var url = matcher.group(1)
            if (url.startsWith("//")) {
                url = "https:$url"
            } else if (url.startsWith("/")) {
                url = "https://hanime.tv$url"
            }
            url
        } else {
            null
        }
    }

    private fun fetchScriptContent(scriptUrl: String): String {
        return try {
            val request = Request.Builder()
                .url(scriptUrl)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                )
                .build()

            val response = CLIENT.newCall(request).execute()
            response.body.string()
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractSignature(scriptContent: String): String {
        val patterns = listOf(
            Pattern.compile("window\\.ssignature\\s*=\\s*['\"]([^'\"]+)['\"]"),
            Pattern.compile("ssignature[:\"]\\s*['\"]([^'\"]+)['\"]"),
            Pattern.compile("signature[:\"]\\s*['\"]([^'\"]+)['\"]"),
        )

        patterns.forEach { pattern ->
            val matcher = pattern.matcher(scriptContent)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return ""
    }

    private fun extractTimestamp(scriptContent: String): Long {
        val patterns = listOf(
            Pattern.compile("window\\.stime\\s*=\\s*(\\d+)"),
            Pattern.compile("stime[:\"]\\s*(\\d+)"),
            Pattern.compile("timestamp[:\"]\\s*(\\d+)"),
        )

        patterns.forEach { pattern ->
            val matcher = pattern.matcher(scriptContent)
            if (matcher.find()) {
                return matcher.group(1).toLongOrNull() ?: 0L
            }
        }
        return 0L
    }
}
