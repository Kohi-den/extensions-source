package eu.kanade.tachiyomi.animeextension.pt.darkmahou

import android.util.Log
import eu.kanade.tachiyomi.animeextension.pt.darkmahou.extractors.DarkMahouExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Element

class DarkMahou : AnimeStream(
    "pt-BR",
    "DarkMahou (Torrent)",
    "https://darkmahou.org",
) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    override val animeListUrl = "$baseUrl/animes"

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            title = element.selectFirst("div.tt span.ntitle")!!.ownText()
            thumbnail_url = element.selectFirst("img")?.getImageUrl()
        }
    }

    // ============================== Filters ===============================
    override val fetchFilters = false

    override val filtersSelector = "form.filters  > div.filter > ul"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.mctnx div.soraddl"

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            element.selectFirst(".sorattl h3")!!.text().let {
                name = it
                episode_number = it.substringAfterLast(" ").toFloatOrNull() ?: 0F
                setUrlWithoutDomain(
                    element.ownerDocument()!!.location()
                        .toHttpUrl()
                        .newBuilder()
                        .fragment(it)
                        .toString(),
                )
            }
        }
    }

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    override val prefQualityEntries = prefQualityValues

    private val darkmahouExtractor by lazy { DarkMahouExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        Log.i(name, "getVideoList -> URL => ${episode.url} || Name => ${episode.name}")
        return darkmahouExtractor.videosFromUrl(episode.url)
    }
}
