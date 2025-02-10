package eu.kanade.tachiyomi.animeextension.en.yugenanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

class YugenAnime : ParsedAnimeHttpSource() {

    override val name = "YugenAnime"

    override val baseUrl = "https://yugenanime.sx"

    override val lang = "en"

    override val supportsLatest = true

    override val client = OkHttpClient()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/discover/?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = "div.cards-grid a.anime-meta"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.attr("title").ifBlank { element.select("span.anime-name").text() }
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.selectFirst("img.lozad")?.attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.sidepanel--content > nav > ul > li:nth-child(7) > a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/discover/?page=$page&sort=Newest+Addition"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = "div.cards-grid a.anime-meta"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.attr("title").ifBlank { element.select("span.anime-name").text() }
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.selectFirst("img.lozad")?.attr("data-src")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li.next a"

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as? GenreFilter
        val sortFilter = filterList.find { it is SortFilter } as? SortFilter
        val statusFilter = filterList.find { it is StatusFilter } as? StatusFilter
        val yearFilter = filterList.find { it is YearFilter } as? YearFilter
        val languageFilter = filterList.find { it is LanguageFilter } as? LanguageFilter

        val queryString = mutableListOf<String>()

        genreFilter?.let {
            val genrePart = it.toUriPart()
            if (genrePart.isNotBlank()) {
                queryString.add(genrePart)
            }
        }

        sortFilter?.let { if (it.state != 0) queryString.add(it.toUriPart()) }
        statusFilter?.let { if (it.state != 0) queryString.add(it.toUriPart()) }
        yearFilter?.let { if (it.state != 0) queryString.add(it.toUriPart()) }
        languageFilter?.let { if (it.state != 0) queryString.add(it.toUriPart()) }

        val url = when {
            query.isNotBlank() -> "$baseUrl/discover/?page=$page&q=$query${if (queryString.isNotEmpty()) "&${queryString.joinToString("&")}" else ""}"
            queryString.isNotEmpty() -> "$baseUrl/discover/?page=$page&${queryString.joinToString("&")}"
            else -> "$baseUrl/discover/?page=$page"
        }

        return GET(url, headers)
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("Any", ""),
            Pair("Not yet aired", "status=Not+yet+aired"),
            Pair("Currently Airing", "status=Currently+Airing"),
            Pair("Finished Airing", "status=Finished+Airing"),
        ),
    )

    private class YearFilter : UriPartFilter(
        "Year",
        arrayOf(
            Pair("Any", ""),
            Pair("2024", "year=2024"),
            Pair("2023", "year=2023"),
            Pair("2022", "year=2022"),
        ),
    )

    private class LanguageFilter : UriPartFilter(
        "Language",
        arrayOf(
            Pair("Both", ""),
            Pair("Sub", "language=Sub"),
            Pair("Dub", "language=Dub"),
        ),
    )

    private class GenreFilter : CheckBoxFilterList(
        "Genres",
        arrayOf(
            Pair("Action", "genreIncluded=Action"),
            Pair("Adventure", "genreIncluded=Adventure"),
            Pair("Comedy", "genreIncluded=Comedy"),
            Pair("Drama", "genreIncluded=Drama"),
            Pair("Ecchi", "genreIncluded=Ecchi"),
            Pair("Fantasy", "genreIncluded=Fantasy"),
            Pair("Harem", "genreIncluded=Harem"),
            Pair("Historical", "genreIncluded=Historical"),
            Pair("Horror", "genreIncluded=Horror"),
            Pair("Magic", "genreIncluded=Magic"),
            Pair("Martial Arts", "genreIncluded=Martial+Arts"),
            Pair("Mecha", "genreIncluded=Mecha"),
            Pair("Military", "genreIncluded=Military"),
            Pair("Music", "genreIncluded=Music"),
            Pair("Mystery", "genreIncluded=Mystery"),
            Pair("Parody", "genreIncluded=Parody"),
            Pair("Police", "genreIncluded=Police"),
            Pair("Psychological", "genreIncluded=Psychological"),
            Pair("Romance", "genreIncluded=Romance"),
            Pair("Samurai", "genreIncluded=Samurai"),
            Pair("School", "genreIncluded=School"),
            Pair("Sci-Fi", "genreIncluded=Sci-Fi"),
            Pair("Seinen", "genreIncluded=Seinen"),
            Pair("Shoujo", "genreIncluded=Shoujo"),
            Pair("Shoujo Ai", "genreIncluded=Shoujo+Ai"),
            Pair("Shounen", "genreIncluded=Shounen"),
            Pair("Shounen Ai", "genreIncluded=Shounen+Ai"),
            Pair("Slice of Life", "genreIncluded=Slice+of+Life"),
            Pair("Space", "genreIncluded=Space"),
            Pair("Sports", "genreIncluded=Sports"),
            Pair("Super Power", "genreIncluded=Super+Power"),
            Pair("Supernatural", "genreIncluded=Supernatural"),
            Pair("Thriller", "genreIncluded=Thriller"),
            Pair("Vampire", "genreIncluded=Vampire"),
            Pair("Yaoi", "genreIncluded=Yaoi"),
            Pair("Yuri", "genreIncluded=Yuri"),
        ),
    )

    private open class CheckBoxFilterList(name: String, pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<CheckBoxFilterList.CheckBoxVal>(name, pairs.map { CheckBoxVal(it.first, false, it.second) }) {

        fun toUriPart(): String {
            return state.filter { it.state }.joinToString("&") { it.uriPart }
        }

        private class CheckBoxVal(displayName: String, defaultState: Boolean, val uriPart: String) :
            CheckBox(displayName, defaultState)
    }

    private class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Default", ""),
            Pair("Newest Addition", "sort=Newest+Addition"),
            Pair("Oldest Addition", "sort=Oldest+Addition"),
            Pair("Alphabetical", "sort=Alphabetical"),
            Pair("Rating", "sort=Rating"),
            Pair("Views", "sort=Views"),
        ),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        SortFilter(),
        StatusFilter(),
        YearFilter(),
        LanguageFilter(),
    )

    override fun searchAnimeSelector(): String {
        return "div.cards-grid a.anime-meta"
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.attr("title").ifBlank { element.select("span.anime-name").text() }
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = (element.selectFirst("img.lozad")?.attr("data-src"))
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.next a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.content h1")?.text().orEmpty()
        anime.thumbnail_url = document.selectFirst("img.cover")?.attr("src")

        val metaDetails = document.select("div.anime-metadetails div.data")
        metaDetails.forEach { data ->
            val title = data.selectFirst("div.ap--data-title")?.text()
            val description = data.selectFirst("span.description")?.text()

            when (title) {
                "Romaji" -> anime.title = description.orEmpty()
                "Studios" -> anime.author = description.orEmpty()
                "Status" -> anime.status = parseStatus(description.orEmpty())
                "Genres" -> anime.genre = description.orEmpty()
            }
        }

        anime.description = document.select("p.description").text()

        return anime
    }

    private fun parseStatus(status: String): Int {
        return when (status.lowercase()) {
            "finished airing" -> SAnime.COMPLETED
            "currently airing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "ul.ep-grid li.ep-card"

    private fun episodeListRequest(anime: SAnime, page: Int): Request {
        val url = "$baseUrl${anime.url}watch/?page=$page"
        return GET(url, headers)
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val title = element.select("a.ep-title").text()
        val link = fixUrl(element.select("a.ep-title").attr("href"))
        val dateElement = element.selectFirst("time[datetime]")
        val releaseDate = dateElement?.attr("datetime") ?: ""

        val date = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(releaseDate)
        } catch (e: Exception) {
            null
        }

        val episodeNumber = title.substringBefore(":").filter { it.isDigit() }.toIntOrNull()

        episode.setUrlWithoutDomain(link)
        episode.name = title
        episode.episode_number = episodeNumber?.toFloat() ?: 0F
        episode.date_upload = date?.time ?: 0

        return episode
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val anime = SAnime.create()
        anime.url = response.request.url.encodedPath
        return fetchAllEpisodes(anime)
    }

    private fun fixUrl(url: String?): String {
        return when {
            url == null -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> "$baseUrl/$url"
        }
    }

    private fun fetchAllEpisodes(anime: SAnime, page: Int = 1, episodes: MutableList<SEpisode> = mutableListOf()): List<SEpisode> {
        val response = client.newCall(episodeListRequest(anime, page)).execute()
        val document = response.asJsoup()
        val newEpisodes = document.select(episodeListSelector()).map { element -> episodeFromElement(element) }
        episodes.addAll(newEpisodes)

        val hasNextPage = document.select("ul.pagination li a:contains(Next)").isNotEmpty()
        return if (hasNextPage) {
            fetchAllEpisodes(anime, page + 1, episodes)
        } else {
            episodes.sortedByDescending { it.episode_number }
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val data = response.request.url.toString()
        val episode = data.removeSuffix("/").split("/").last()
        val dubData = data.substringBeforeLast("/$episode").let { "$it-dub/$episode" }
        val api = "$baseUrl/api/embed/"

        val videoList = mutableListOf<Video>()

        listOf(data, dubData).forEach { url ->
            val doc = client.newCall(GET(url)).execute().asJsoup()
            val iframe = doc.select("iframe#main-embed").attr("src") ?: return@forEach
            val id = iframe.removeSuffix("/").split("/").lastOrNull() ?: return@forEach
            val sourceResponse = client.newCall(
                POST(
                    api,
                    body = FormBody.Builder()
                        .add("id", id)
                        .add("ac", "0")
                        .build(),
                    headers = headers.newBuilder()
                        .add("Miru-Url", api)
                        .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .add("X-Requested-With", "XMLHttpRequest")
                        .add("Referer", "$baseUrl/e/$id/")
                        .build(),
                ),
            ).execute().body.string()

            val source = sourceResponse.parseAs<Sources>().hls?.distinct()?.firstOrNull() ?: return@forEach
            val isDub = if (url.contains("-dub")) "dub" else "sub"
            val sourceType = getSourceType(getBaseUrl(source))

            videoList.add(
                Video(
                    source,
                    "$sourceType [$isDub]",
                    source,
                    headers = headers,
                ),
            )
        }

        return videoList
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun getSourceType(url: String): String {
        return when {
            url.contains("cache", true) -> "Cache"
            url.contains("allanime", true) -> "Crunchyroll-AL"

            else -> Regex("\\.(\\S+)\\.").find(url)?.groupValues?.getOrNull(1)?.let { fixTitle(it) } ?: this.name
        }
    }

    private fun fixTitle(title: String): String {
        return title.replace("_", " ")
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    @Serializable
    data class Sources(
        @SerialName("hls")
        val hls: List<String>? = null,
    )
    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
