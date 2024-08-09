package eu.kanade.tachiyomi.lib.vidmolyextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.internal.EMPTY_HEADERS

class VidMolyExtractor(private val client: OkHttpClient, headers: Headers = EMPTY_HEADERS) {

    private val baseUrl = "https://vidmoly.to"

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val headers: Headers = headers.newBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")
        .build()

    private val sourcesRegex = Regex("sources: (.*?]),")
    private val urlsRegex = Regex("""file:"(.*?)"""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(
            GET(url, headers.newBuilder().set("Sec-Fetch-Dest", "iframe").build())
        ).execute().asJsoup()
        val script = document.selectFirst("script:containsData(sources)")!!.data()
        val sources = sourcesRegex.find(script)!!.groupValues[1]
        val urls = urlsRegex.findAll(sources).map { it.groupValues[1] }.toList()
        return urls.flatMap {
            playlistUtils.extractFromHls(it,
                videoNameGen = { quality -> "${prefix}VidMoly - $quality" },
                masterHeaders = headers,
                videoHeaders = headers,
            )
        }
    }
}
