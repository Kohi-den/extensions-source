package eu.kanade.tachiyomi.animeextension.pt.sushianimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SushiAnimes : ParsedAnimeHttpSource() {

    override val name = "Sushi Animes"

    override val baseUrl = "https://sushianimes.com.br"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trends", headers)

    override fun popularAnimeSelector() = "a.list-trend"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst(".list-title")!!.text()
        thumbnail_url = element.selectFirst(".media-cover")?.attr("data-src")
        description = element.selectFirst(".list-description")?.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/episodios?page=$page", headers)

    override fun latestUpdatesSelector() = ".episode-grid a.list-movie:not(:has(.hentai-list-media))"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst(".list-caption")!!.text()
        thumbnail_url = element.selectFirst(".media-episode")?.attr("data-src")
    }

    override fun latestUpdatesNextPageSelector(): String = "a.btn.btn-theme.ml-2"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
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
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addPathSegment(query)
            .build()

        return GET(url, headers = headers)
    }

    override fun searchAnimeSelector() = "div.list-movie"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst(".list-title")!!.text()
        thumbnail_url = element.selectFirst(".media-cover")?.attr("data-src")
    }

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = doc.selectFirst("#title")!!.text()
            thumbnail_url = doc.selectFirst(".media-cover img")?.attr("src")
            description =
                doc.selectFirst(".detail-attr:contains(Sinopse) .text,.detail-attr:contains(Descrição) .text")
                    ?.text()
            genre = doc.select(".category-list a, .categories a").eachText().joinToString(", ")
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = getRealDoc(response.asJsoup())
        val script = document.selectFirst("script[type=\"application/ld+json\"]")
            ?: return emptyList()

        val btnMovie = document.selectFirst("a.btn:contains(Assistir)")
        if (btnMovie !== null) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(btnMovie.attr("href"))
                    name = "Filme"
                    episode_number = 1F
                },
            )
        }

        val jsonString = script.data().trim()
            .let(::sanitizeLdJsonNames)

        val anime = json.decodeFromString<AnimeDto>(jsonString)

        val episodes = anime.containsSeason.flatMap { season ->
            season.episode.map { episode ->
                SEpisode.create().apply {
                    setUrlWithoutDomain(episode.url)
                    name =
                        "Temporada ${season.seasonNumber} x ${episode.episodeNumber} - ${episode.name}"
                    episode_number = episode.episodeNumber.toFloatOrNull() ?: 0F
                }
            }
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val playlistUtils by lazy { PlaylistUtils(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val id = document.selectFirst("[data-embed]")?.attr("data-embed") ?: return emptyList()

        val formBody = FormBody.Builder()
            .add("id", id)
            .build()

        val request = POST("$baseUrl/ajax/embed", headers, formBody)
        val body = client.newCall(request).execute().body.string()

        val videoUrl = body.substringAfterLast("playerEmbed", "")
            .substringAfter("\"")
            .substringBefore("\"")

        return listOf(Video(videoUrl, "Sushi Animes", videoUrl))
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst(".episode-nav .home-list a")
        if (menu != null) {
            val originalUrl = menu.attr("href")
            val response = client.newCall(GET(originalUrl, headers)).execute()
            return response.asJsoup()
        }

        return document
    }

    /**
     * Usa regex para achar o valor de `"name": "..."` e escapar apenas as aspas
     * internas não escapadas dentro desse valor.
     */
    private fun sanitizeLdJsonNames(input: String): String {
        return NAME_VALUE_REGEX.replace(input) { match ->
            val value = match.groupValues[1]
            val escaped = value.replace(Regex("(?<!\\\\)\""), "\\\\\"")
            "\"name\": \"$escaped\""
        }
    }

    companion object {
        const val PREFIX_SEARCH = "path:"

        private val NAME_VALUE_REGEX = "\"name\"\\s*:\\s*\"(.*?)\",".toRegex()
    }
}
