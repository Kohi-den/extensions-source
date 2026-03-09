package eu.kanade.tachiyomi.lib.mailruextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MailRuExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    fun videosFromUrl(
        url: String,
        prefix: String = "",
    ): List<Video> = runCatching { extract(url, prefix) }.getOrDefault(emptyList())

    private fun extract(
        url: String,
        prefix: String,
    ): List<Video> {
        val embedHost = url.toHttpUrl().host
        val document = client.newCall(GET(url, headers)).execute().asJsoup()

        val metaUrl =
            document
                .selectFirst("script:containsData(metadataUrl)")
                ?.data()
                ?.substringAfter("metadataUrl\":\"")
                ?.substringBefore("\"")
                ?.replace("^//".toRegex(), "https://")
                ?: return emptyList()

        val metaHeaders =
            headers
                .newBuilder()
                .removeAll("Origin")
                .set("Accept", "application/json, text/javascript, */*; q=0.01")
                .set("Referer", url)
                .build()

        val metaResponse = client.newCall(GET(metaUrl, metaHeaders)).execute()
        if (!metaResponse.isSuccessful) return emptyList()
        val metaBody = metaResponse.body.string()

        val videoKey =
            metaResponse.headers
                .firstOrNull {
                    it.first.equals("set-cookie", true) &&
                        it.second.startsWith("video_key", true)
                }?.second
                ?.substringBefore(";")
                .orEmpty()

        val videoHeaders =
            headers
                .newBuilder()
                .set("Cookie", videoKey)
                .set("Origin", "https://$embedHost")
                .set("Referer", "https://$embedHost/")
                .build()

        return VIDEO_REGEX
            .findAll(metaBody)
            .mapNotNull { match ->
                val key = match.groupValues[1]
                val rawUrl =
                    match.groupValues[2]
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")
                        .replace("^//".toRegex(), "https://")

                if (!rawUrl.startsWith("https://")) return@mapNotNull null

                Video(
                    videoTitle = "${prefix}Mail.ru $key",
                    videoUrl = rawUrl,
                    headers = videoHeaders,
                    subtitleTracks = emptyList(),
                    audioTracks = emptyList(),
                )
            }.toList()
    }

    companion object {
        private val VIDEO_REGEX =
            Regex(""""key"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""")
    }
}
