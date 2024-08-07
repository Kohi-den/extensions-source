package eu.kanade.tachiyomi.multisrc.anilist

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

abstract class AniListAnimeHttpSource : AnimeHttpSource() {
    override val supportsLatest = true
    val json by injectLazy<Json>()

    /* =============================== Mapping AniList <> Source =============================== */
    abstract fun mapAnimeDetailUrl(animeId: Int): String

    abstract fun mapAnimeId(animeDetailUrl: String): Int

    open fun getPreferredTitleLanguage(): TitleLanguage {
        return TitleLanguage.ROMAJI
    }

    /* ===================================== Popular Anime ===================================== */
    override fun popularAnimeRequest(page: Int): Request {
        return buildAnimeListRequest(
            query = ANIME_LIST_QUERY,
            variables = AnimeListVariables(
                page = page,
                sort = AnimeListVariables.MediaSort.POPULARITY_DESC,
            ),
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnimeListResponse(response)
    }

    /* ===================================== Latest Anime ===================================== */
    override fun latestUpdatesRequest(page: Int): Request {
        return buildAnimeListRequest(
            query = LATEST_ANIME_LIST_QUERY,
            variables = AnimeListVariables(
                page = page,
                sort = AnimeListVariables.MediaSort.START_DATE_DESC,
            ),
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnimeListResponse(response)
    }

    /* ===================================== Search Anime ===================================== */
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return buildAnimeListRequest(
            query = ANIME_LIST_QUERY,
            variables = AnimeListVariables(
                page = page,
                sort = AnimeListVariables.MediaSort.SEARCH_MATCH,
                search = query.ifBlank { null },
            ),
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnimeListResponse(response)
    }

    /* ===================================== Anime Details ===================================== */
    override fun animeDetailsRequest(anime: SAnime): Request {
        return buildRequest(
            query = ANIME_DETAILS_QUERY,
            variables = json.encodeToString(AnimeDetailsVariables(mapAnimeId(anime.url))),
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val media = response.parseAs<AniListAnimeDetailsResponse>().data.media

        return media.toSAnime()
    }

    override fun getAnimeUrl(anime: SAnime): String {
        return anime.url
    }

    /* ==================================== AniList Utility ==================================== */
    private fun buildAnimeListRequest(
        query: String,
        variables: AnimeListVariables,
    ): Request {
        return buildRequest(query, json.encodeToString(variables))
    }

    private fun buildRequest(query: String, variables: String): Request {
        val requestBody = FormBody.Builder()
            .add("query", query)
            .add("variables", variables)
            .build()

        return POST(url = "https://graphql.anilist.co", body = requestBody)
    }

    private fun parseAnimeListResponse(response: Response): AnimesPage {
        val page = response.parseAs<AniListAnimeListResponse>().data.page

        return AnimesPage(
            animes = page.media.map { it.toSAnime() },
            hasNextPage = page.pageInfo.hasNextPage,
        )
    }

    private fun AniListMedia.toSAnime(): SAnime {
        val otherNames = when (getPreferredTitleLanguage()) {
            TitleLanguage.ROMAJI -> listOfNotNull(title.english, title.native)
            TitleLanguage.ENGLISH -> listOfNotNull(title.romaji, title.native)
            TitleLanguage.NATIVE -> listOfNotNull(title.romaji, title.english)
        }
        val newDescription = buildString {
            append(
                description
                    ?.replace("<br>\n<br>", "\n")
                    ?.replace("<.*?>".toRegex(), ""),
            )
            if (otherNames.isNotEmpty()) {
                appendLine()
                appendLine()
                append("Other name(s): ${otherNames.joinToString(", ")}")
            }
        }
        val media = this

        return SAnime.create().apply {
            url = mapAnimeDetailUrl(media.id)
            title = parseTitle(media.title)
            author = media.studios.nodes.joinToString(", ") { it.name }
            description = newDescription
            genre = media.genres.joinToString(", ")
            status = when (media.status) {
                AniListMedia.Status.RELEASING -> SAnime.ONGOING
                AniListMedia.Status.FINISHED -> SAnime.COMPLETED
            }
            thumbnail_url = media.coverImage.large
        }
    }

    private fun parseTitle(title: AniListMedia.Title): String {
        return when (getPreferredTitleLanguage()) {
            TitleLanguage.ROMAJI -> title.romaji
            TitleLanguage.ENGLISH -> title.english ?: title.romaji
            TitleLanguage.NATIVE -> title.native ?: title.romaji
        }
    }

    enum class TitleLanguage {
        ROMAJI,
        ENGLISH,
        NATIVE,
    }
}
