package eu.kanade.tachiyomi.animeextension.pt.pobreflix

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors.MyStreamExtractor
import eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors.PlayerFlixExtractor
import eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors.SuperFlixExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.fireplayerextractor.FireplayerExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

class Pobreflix : DooPlay(
    "pt-BR",
    "Pobreflix",
    "https://pobreflix.global",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.featured div.poster"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/series/page/$page/", headers)

    // ============================ Video Links =============================
    private val fireplayerExtractor by lazy { FireplayerExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mystreamExtractor by lazy { MyStreamExtractor(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val playerflixExtractor by lazy { PlayerFlixExtractor(client, headers, ::genericExtractor) }
    private val superflixExtractor by lazy { SuperFlixExtractor(client, headers, ::genericExtractor) }
    private val supercdnExtractor by lazy { SuperFlixExtractor(client, headers, ::genericExtractor, "https://supercdn.org") }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        return doc.select("div.source-box > a").parallelCatchingFlatMapBlocking {
            val data = it.attr("href").trim().toHttpUrl().queryParameter("auth")
                ?.let { Base64.decode(it, Base64.DEFAULT) }
                ?.let(::String)
                ?: return@parallelCatchingFlatMapBlocking emptyList()
            val url = data.replace("\\", "").substringAfter("url\":\"").substringBefore('"')
            genericExtractor(url)
        }
    }

    private fun genericExtractor(url: String, language: String = ""): List<Video> {
        val langSubstr = if (language.isBlank()) "" else "[$language] "
        return when {
            url.contains("superflix") ->
                superflixExtractor.videosFromUrl(url)
            url.contains("supercdn") ->
                supercdnExtractor.videosFromUrl(url)
            url.contains("filemoon") ->
                filemoonExtractor.videosFromUrl(url, "${langSubstr}Filemoon - ", headers = headers)
            url.contains("watch.brplayer") || url.contains("/watch?v=") ->
                mystreamExtractor.videosFromUrl(url, language)
            url.contains("brbeast") ->
                fireplayerExtractor.videosFromUrl(url = url, videoNameGen = { "${langSubstr}BrBeast - $it" })
            url.contains("embedplayer") ->
                fireplayerExtractor.videosFromUrl(url = url, videoNameGen = { "${langSubstr}EmbedPlayer - $it" })
            url.contains("superembeds") ->
                fireplayerExtractor.videosFromUrl(url = url, videoNameGen = { "${langSubstr}SuperEmbeds - $it" })
            url.contains("streamtape") ->
                streamtapeExtractor.videosFromUrl(url, "${langSubstr}Streamtape")
            url.contains("filelions") ->
                streamwishExtractor.videosFromUrl(url, videoNameGen = { "${langSubstr}FileLions - $it" })
            url.contains("streamwish") ->
                streamwishExtractor.videosFromUrl(url, videoNameGen = { "${langSubstr}Streamwish - $it" })
            url.contains("playerflix") ->
                playerflixExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }
}
