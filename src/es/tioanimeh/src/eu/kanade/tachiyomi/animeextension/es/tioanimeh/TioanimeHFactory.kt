package eu.kanade.tachiyomi.animeextension.es.tioanimeh

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request

class TioanimeHFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        TioAnime(),
        TioHentai(),
    )
}

class TioAnime : TioanimeH("TioAnime", "https://tioanime.com") {
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = TioAnimeHFilters.getSearchParameters(filters, TioAnimeHFilters.TioAnimeHFiltersData.ORIGEN.ANIME)

        return when {
            query.isNotBlank() -> GET("$baseUrl/directorio?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/directorio${params.getQuery()}&p=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = TioAnimeHFilters.getFilterList(TioAnimeHFilters.TioAnimeHFiltersData.ORIGEN.ANIME)
}

class TioHentai : TioanimeH("TioHentai", "https://tiohentai.com")
