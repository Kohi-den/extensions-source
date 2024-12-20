package eu.kanade.tachiyomi.animeextension.pt.darkmahou.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class DarkMahouExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()

        val fragment = url.toHttpUrl().fragment

        val soraurls = doc.select("div.mctnx div.soraddl .sorattl h3").find {
            it.text() == fragment
        }?.closest(".soraddl")?.select(".soraurl") ?: return emptyList()

        return soraurls.flatMap {
            val prefix = if (it.text().lowercase().contains("dublado")) {
                "Dublado"
            } else {
                "Legendado"
            }
            it.select(".slink a").map {
                val videoUrl = it.attr("href")
                val quality = it.text().trim()
                Video(videoUrl, "$prefix - $quality", videoUrl)
            }
        }
    }
}
