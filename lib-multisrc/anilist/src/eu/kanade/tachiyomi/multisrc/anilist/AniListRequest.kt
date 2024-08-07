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

@Serializable
internal data class AnimeListVariables(
    val page: Int,
    val sort: MediaSort,
    val search: String? = null,
) {
    enum class MediaSort {
        POPULARITY_DESC,
        SEARCH_MATCH,
        START_DATE_DESC,
    }
}

@Serializable
internal data class AnimeDetailsVariables(val id: Int)
