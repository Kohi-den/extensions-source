package eu.kanade.tachiyomi.animeextension.pt.animeplay

import eu.kanade.tachiyomi.animeextension.pt.animeplay.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimePlay : DooPlay(
    "pt-BR",
    "Anime Play",
    "https://animeplay.cloud",
) {
    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime", headers)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.pagination > a.arrow_pag > i.fa-caret-right"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val orderByFilter = filterList.find { it is OrderByFilter } as? OrderByFilter
        val orderFilter = filterList.find { it is OrderFilter } as? OrderFilter

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            filterList.firstOrNull { it is UriPartFilter && it.state != 0 }?.let {
                val filter = it as UriPartFilter
                addEncodedPathSegments(filter.toUriPart())
            }

            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }

            addPathSegment("")
            addQueryParameter("s", query)

            // order (optional)
            if (orderByFilter != null) addQueryParameter("orderby", orderByFilter.selected)
            if (orderFilter != null) addQueryParameter("order", orderFilter.selected)
        }.build()

        return GET(url.toString(), headers)
    }

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String {
        return select("$additionalInfoSelector p")
            .first { !it.text().contains("Título Alternativo") }
            ?.let { it.text() + "\n" }
            ?: ""
    }

    fun Document.getAlternativeTitle(): String {
        return select("$additionalInfoSelector p")
            .first { it.text().contains("Título Alternativo") }
            ?.let { it.text() + "\n" }
            ?: ""
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }.trim()
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            // description = doc.getDescription()
            doc.selectFirst("div#info")?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    append(doc.getAlternativeTitle())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text()
            .run {
                when (this.uppercase()) {
                    "SD" -> "360p"
                    "HD" -> "720p"
                    "SD/HD", "SD / HD" -> "720p"
                    "FHD", "FULLHD", "FULLHD / HLS" -> "1080p"
                    else -> this
                }
            }

        val url = getPlayerUrl(player)

        val videos = when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            "jwplayer?source=" in url -> {
                val videoUrl = url.toHttpUrl().queryParameter("source") ?: return emptyList()

                val videoHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Host", videoUrl.toHttpUrl().host)
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()

                return listOf(
                    Video(videoUrl, name, videoUrl, videoHeaders),
                )
            }

            else -> emptyList()
        }

        if (videos.isEmpty()) {
            return universalExtractor.videosFromUrl(url, headers, name)
        }
        return videos
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute().body.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    // ============================== Filters ===============================
    private var hasFetchedGenresArray = false

    override val genreFilterHeader = "Apenas um tipo de filtro por vez"
    override fun genresListRequest() =
        GET("$baseUrl/wp-json/wp/v2/genres?per_page=100&_fields[]=name&_fields[]=link")

    override fun getFilterList(): AnimeFilterList {
        return if (hasFetchedGenresArray) {
            AnimeFilterList(
                AnimeFilter.Header(genreFilterHeader),
                AudioFilter(),
                FetchedGenresFilter(genresListMessage, genresArray),
                AnimeFilter.Separator(),
                OrderByFilter(),
                OrderFilter(),
            )
        } else if (fetchGenres) {
            AnimeFilterList(AnimeFilter.Header(genresMissingWarning))
        } else {
            AnimeFilterList()
        }
    }

    override fun fetchGenresList() {
        if (hasFetchedGenresArray || !fetchGenres) return

        runCatching {
            client.newCall(genresListRequest())
                .execute()
                .parseAs<List<GenreDto>>()
                .let(::genresListParse)
                .let { items ->
                    if (items.isNotEmpty()) {
                        genresArray = items
                        hasFetchedGenresArray = true
                    }
                }
        }.onFailure { it.printStackTrace() }
    }

    fun genresListParse(genres: List<GenreDto>): Array<Pair<String, String>> {
        val items = genres.map {
            val name = it.name
            val value = it.link.substringAfter("$baseUrl/").removeSuffix("/")
            Pair(name, value)
        }.toTypedArray()

        return if (items.isEmpty()) {
            items
        } else {
            arrayOf(Pair(selectFilterText, "")) + items
        }
    }

    private class AudioFilter : UriPartFilter(
        "Áudio",
        arrayOf(
            Pair("Todos", ""),
            Pair("Dublado", "tipo/dublado"),
            Pair("Legendado", "tipo/legendado"),
        ),
    )

    private abstract class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) :
        AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected
            get() = options[state].second
    }

    private class OrderByFilter : SelectFilter(
        "Ordenar Por",
        arrayOf(
            Pair("Data de Criação", "date"),
            Pair("Data de Modificação", "modified"),
            Pair("Título", "title"),
        ),
    )

    private class OrderFilter : SelectFilter(
        "Ordem",
        arrayOf(
            Pair("Descendente", "desc"),
            Pair("Ascendente", "asc"),
        ),
    )

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    override fun Element.getImageUrl(): String {
        val url = when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }

        // Remove the "-<width>x<height>" suffix before the file extension:
        // ex: ".../file-200x300.jpg" -> ".../file.jpg"
        return url.replace(REGEX_IMAGE_SIZE_SUFFIX, "")
    }

    @Serializable
    data class GenreDto(
        val name: String,
        val link: String,
    )

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val REGEX_IMAGE_SIZE_SUFFIX by lazy {
            Regex("""-\d+x\d+(?=\.[A-Za-z0-9]+$)""")
        }
    }
}
