package eu.kanade.tachiyomi.animeextension.all.torrentioanime

private fun String.toQuery() = this.trimIndent().replace("%", "$")

fun anilistQuery() = """
    query (
        %page: Int,
        %perPage: Int,
        %sort: [MediaSort],
        %search: String,
        %genres: [String],
        %year: String,
        %seasonYear: Int,
        %season: MediaSeason,
        %format: [MediaFormat],
        %status: [MediaStatus],
    ) {
        Page(page: %page, perPage: %perPage) {
            pageInfo {
                currentPage
                hasNextPage
            }
            media(
                type: ANIME,
                sort: %sort,
                search: %search,
                status_in: %status,
                genre_in: %genres,
                startDate_like: %year,
                seasonYear: %seasonYear,
                season: %season,
                format_in: %format
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
                }
                description
                status
                tags {
                    name
                }
                genres
                studios {
                    nodes {
                        name
                    }
                }
                countryOfOrigin
                isAdult
            }
        }
    }
""".toQuery()

fun anilistLatestQuery() = """
    query (%page: Int, %perPage: Int, %sort: [AiringSort]) {
        Page(page: %page, perPage: %perPage) {
            pageInfo {
                currentPage
                hasNextPage
            }
            airingSchedules(
                airingAt_greater: 0,
                airingAt_lesser: ${System.currentTimeMillis() / 1000 - 10000},
                sort: %sort
            ) {
                media {
                    id
                    title {
                        romaji
                        english
                        native
                    }
                    coverImage {
                        extraLarge
                        large
                    }
                    description
                    status
                    tags {
                        name
                    }
                    genres
                    studios {
                        nodes {
                            name
                        }
                    }
                    countryOfOrigin
                    isAdult
                }
            }
        }
    }
""".toQuery()

fun getDetailsQuery() = """
query media(%id: Int) {
  Media(id: %id) {
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
    description
    season
    seasonYear
    format
    status
    genres
    episodes
    format
    countryOfOrigin
    isAdult
    tags{
        name
    }
    studios {
        nodes {
          id
          name
        }
    }
  }
}
""".toQuery()

fun getEpisodeQuery() = """
query media(%id: Int, %type: MediaType) {
  Media(id: %id, type: %type) {
    episodes
    nextAiringEpisode {
      episode
    }
  }
}
""".toQuery()

fun getMalIdQuery() = """
query media(%id: Int, %type: MediaType) {
  Media(id: %id, type: %type) {
    idMal
    id
  }
}
""".toQuery()
