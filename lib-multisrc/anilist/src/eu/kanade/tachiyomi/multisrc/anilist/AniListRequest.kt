package eu.kanade.tachiyomi.multisrc.anilist

import kotlinx.serialization.Serializable

internal const val MEDIA_QUERY = """
    id
    title {
      romaji
      english
      native
    }
    coverImage {
      large
    }
    description
    status
    genres
    studios(isMain: true) {
      nodes {
        name
      }
    }
"""

internal const val ANIME_LIST_QUERY = """
    query (${"$"}page: Int, ${"$"}sort: [MediaSort], ${"$"}search: String) {
      Page(page: ${"$"}page, perPage: 30) {
        pageInfo {
          hasNextPage
        }
        media(type: ANIME, sort: ${"$"}sort, search: ${"$"}search, status_in: [RELEASING, FINISHED], countryOfOrigin: "JP", isAdult: false) {
          $MEDIA_QUERY
        }
      }
    }
"""

internal const val LATEST_ANIME_LIST_QUERY = """
    query (${"$"}page: Int, ${"$"}sort: [MediaSort], ${"$"}search: String) {
      Page(page: ${"$"}page, perPage: 30) {
        pageInfo {
          hasNextPage
        }
        media(type: ANIME, sort: ${"$"}sort, search: ${"$"}search, status_in: [RELEASING, FINISHED], countryOfOrigin: "JP", isAdult: false, startDate_greater: 1, episodes_greater: 1) {
          $MEDIA_QUERY
        }
      }
    }
"""

internal const val ANIME_DETAILS_QUERY = """
    query (${"$"}id: Int) {
      Media(id: ${"$"}id) {
        $MEDIA_QUERY
      }
    }
"""
private fun String.toQuery() = this.trimIndent().replace("%", "$")
internal val SORT_QUERY = """
    query (
        ${"$"}page: Int,
        ${"$"}perPage: Int,
        ${"$"}isAdult: Boolean,
        ${"$"}type: MediaType,
        ${"$"}sort: [MediaSort],
        ${"$"}status: MediaStatus,
        ${"$"}search: String,
        ${"$"}genres: [String],
        ${"$"}year: String,
        ${"$"}seasonYear: Int,
        ${"$"}season: MediaSeason,
        ${"$"}format: [MediaFormat]
    ) {
        Page (page: ${"$"}page, perPage: ${"$"}perPage) {
            pageInfo {
                hasNextPage
            }
            media (
                isAdult: ${"$"}isAdult,
                type: ${"$"}type,
                sort: ${"$"}sort,
                status: ${"$"}status,
                search: ${"$"}search,
                genre_in: ${"$"}genres,
                startDate_like: ${"$"}year,
                seasonYear: ${"$"}seasonYear,
                season: ${"$"}season,
                format_in: ${"$"}format
            ) {
                id
                title {
                    romaji
                    english
                    native
                }
                coverImage {
                    extraLarge
                    large
                    medium
                }
                status
                genres
                studios {
                    nodes {
                        name
                    }
                }
            }
        }
    }
""".toQuery()

@Serializable
internal data class AnimeListVariables(
    val page: Int,
    val sort: MediaSort,
    val search: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val status: String? = null,
    val format: String? = null,
    val season: String? = null,
    val seasonYear: String? = null,
    val isAdult: Boolean = false,
) {
    enum class MediaSort {
        TRENDING_DESC,
        START_DATE_DESC,
    }
}

@Serializable
internal data class AnimeDetailsVariables(val id: Int)
