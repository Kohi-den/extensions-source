package eu.kanade.tachiyomi.animeextension.pt.animesbr.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class FourNimesExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val kaken = script.substringAfter("kaken", "")
            .substringAfter('"')
            .substringBefore('"')
            .ifEmpty { null }
            ?: return emptyList()

        val now = System.currentTimeMillis()
        val apiUrl = "https://${url.toHttpUrl().host}/api?$kaken&_=$now"

        return client.newCall(GET(apiUrl)).execute().parseAs<Response>().sources.map { source ->
            val videoUrl = source.file
            val quality = source.label

            Video(videoUrl, "$prefix 4nimes - $quality ".trim(), videoUrl)
        }
    }

    @Serializable
    data class Source(
        val file: String,
        val label: String,
    )

    @Serializable
    data class Response(
        val sources: List<Source>,
    )
}
