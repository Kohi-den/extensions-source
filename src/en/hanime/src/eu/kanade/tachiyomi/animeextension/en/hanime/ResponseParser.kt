package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Response

object ResponseParser {
    fun parseSearchJson(response: Response, source: Hanime): AnimesPage {
        val jsonLine = response.body.string().ifEmpty { return AnimesPage(emptyList(), false) }
        val jResponse = jsonLine.parseAs<HAnimeResponse>()
        val hasNextPage = jResponse.page < jResponse.nbPages - 1
        val array = jResponse.hits.parseAs<Array<HitsModel>>()
        val animeList = array.groupBy { TitleUtils.getTitle(it.name) }
            .map { (_, items) -> items.first() }
            .map { item ->
                SAnime.create().apply {
                    title = TitleUtils.getTitle(item.name)
                    thumbnail_url = item.coverUrl
                    author = item.brand
                    description = item.description?.replace(Regex("<[^>]*>"), "")
                    status = SAnime.UNKNOWN
                    genre = item.tags.joinToString { it }
                    url = "/videos/hentai/" + item.slug
                    initialized = true
                }
            }
        return AnimesPage(animeList, hasNextPage)
    }

    fun parseAnimeDetails(response: Response, source: Hanime): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = TitleUtils.getTitle(document.select("h1.tv-title").text())
            thumbnail_url = document.select("img.hvpi-cover").attr("src")
            author = document.select("a.hvpimbc-text").text()
            description = document.select("div.hvpist-description p").joinToString("\n\n") { it.text() }
            status = SAnime.UNKNOWN
            genre = document.select("div.hvpis-text div.btn__content").joinToString { it.text() }
            url = document.location()
            initialized = true
        }
    }

    fun parseEpisodeList(response: Response, baseUrl: String): List<SEpisode> {
        val responseString = response.body.string().ifEmpty { return emptyList() }
        return responseString.parseAs<VideoModel>().hentaiFranchiseHentaiVideos
            ?.mapIndexed { idx, it ->
                SEpisode.create().apply {
                    episode_number = idx + 1f
                    name = "Episode ${idx + 1}"
                    date_upload = (it.releasedAtUnix ?: 0) * 1000
                    url = "$baseUrl/api/v8/video?id=${it.id}"
                }
            }?.reversed() ?: emptyList()
    }
}
