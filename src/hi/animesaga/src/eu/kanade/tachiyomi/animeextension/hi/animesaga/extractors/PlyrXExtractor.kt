package eu.kanade.tachiyomi.animeextension.hi.anisaga.extractors

import eu.kanade.tachiyomi.animesource.model.SubtitleFile
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Headers

class PlyrXExtractor(
    private val network: NetworkHelper,
    private val headers: Headers,
) {
    private val client = network.client

    fun videosFromUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<Video> {
        val response = client.newCall(
            GET(url, headers.newBuilder().set("Referer", referer).build()),
        ).execute()

        val document = response.body?.string() ?: return emptyList()

        val masterUrl = Regex("sources:\\s*\\\{\\s*file:\\s*\"(https[^\"]+\\.m3u8)\"")
            .find(document)
            ?.groupValues?.get(1)
            ?: return emptyList()

        val subtitleRegex = Regex("tracks:\\s*\(.*?)\")
        val subtitleMatch = subtitleRegex.find(document)?.groupValues?.get(1)
        subtitleMatch?.let { subText ->
            Regex("""\{file:"(.*?)",label:"(.*?)"\}""")
                .findAll(subText)
                .forEach { match ->
                    val subUrl = match.groupValues[1]
                    val label = match.groupValues[2]
                    subtitleCallback(SubtitleFile(label, subUrl))
                }
        }

        return listOf(
            Video(
                url = masterUrl,
                quality = "HLS",
                videoUrl = masterUrl,
                headers = mapOf("Referer" to referer),
            ),
        )
    }
}
