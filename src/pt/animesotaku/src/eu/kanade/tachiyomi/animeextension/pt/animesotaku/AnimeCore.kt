package eu.kanade.tachiyomi.animeextension.pt.animesotaku

import eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto.EpisodeResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto.SearchResponseDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class AnimeCore : AnimeHttpSource() {

    // AnimesOtaku -> AnimeCore
    override val id: Long = 9099608567050495800L

    override val name = "Anime Core"

    override val baseUrl = "https://animecore.to"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val animeCoreFilters by lazy { AnimeCoreFilters(baseUrl, client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = searchRequest("popular", page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        animeCoreFilters.fetchFilters()
        val results = response.parseAs<SearchResponseDto>()
        val data = results.data
        val doc = Jsoup.parseBodyFragment(data.html)
        val animes = doc.select("article.anime-card").map {
            SAnime.create().apply {
                thumbnail_url = it.selectFirst("img")?.attr("src")
                with(it.selectFirst("h3 > a.stretched-link")!!) {
                    title = attr("title").replace(" Assistir Online", "")
                    setUrlWithoutDomain(attr("href"))
                }
            }
        }

        val hasNextPage = data.currentPage < data.maxPages
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = searchRequest("updated", page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun getFilterList() = animeCoreFilters.getFilterList()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val form = FormBody.Builder().apply {
            add("s_keyword", "")
            // add("orderby", orderBy)
            add("action", "advanced_search")
            add("page", page.toString())
        }

        filters.filterIsInstance<AnimeCoreFilters.QueryParameterFilter>().forEach {
            val (name, value) = it.toQueryParameter()
            when (value) {
                is AnimeCoreFilters.QueryParameterValue.Single -> {
                    form.add(name, value.value)
                }

                is AnimeCoreFilters.QueryParameterValue.Multiple -> {
                    value.values.forEach { v ->
                        form.add(name, v)
                    }
                }
            }
        }

        return POST(
            "$baseUrl/wp-admin/admin-ajax.php",
            headers,
            form.build(),
        )
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    private fun searchRequest(orderby: String, page: Int): Request {
        val form = FormBody.Builder().apply {
            add("s_keyword", "")
            add("orderby", orderby)
            add("order", "DESC")
            add("action", "advanced_search")
            add("page", page.toString())
        }.build()

        return POST(
            "$baseUrl/wp-admin/admin-ajax.php",
            headers,
            form,
        )
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val document = getRealDoc(response.asJsoup())

        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("img.wp-post-image")?.attr("src")
        title =
            document.selectFirst("title")!!.text()
                .replace(" - Anime Core", "")
                .replace(" Assistir Online", "")
        genre =
            document.select("div.flex a.hover\\:text-white").joinToString { it.text() }
        description = document.selectFirst("section p")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = getRealDoc(response.asJsoup())
        val animeId = document.selectFirst("#seasonContent")!!.attr("data-season")

        return client
            .newCall(
                GET(
                    "$baseUrl/wp-admin/admin-ajax.php?action=get_episodes&anime_id=$animeId&page=1&order=desc",
                    headers,
                ),
            ).execute().parseAs<EpisodeResponseDto>().data.episodes.map { it.toSEpisode() }
    }

    // ============================ Video Links =============================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("div.episode-player-box iframe")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = player.attr("src")

        return when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            "proxycdn.cc" in url -> {
                listOf(
                    Video(url, "Proxy CDN", url),
                )
            }

            else -> null
        } ?: emptyList()
    }

    // ============================= Utilities ==============================

    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst("div.anime-information h4 a")
        if (menu != null) {
            val originalUrl = menu.attr("href")
            val response = client.newCall(GET(originalUrl, headers)).execute()
            return response.asJsoup()
        }

        return document
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
