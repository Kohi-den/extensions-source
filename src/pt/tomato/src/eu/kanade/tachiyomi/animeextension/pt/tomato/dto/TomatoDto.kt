package eu.kanade.tachiyomi.animeextension.pt.tomato.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchAnimeItemDto(
    val id: Int,
    val name: String,
    val image: String,
    val tags: String,
)

@Serializable
data class SearchResultDto(
    val result: List<SearchAnimeItemDto>,
)

@Serializable
data class AnimeDetailsDto(
    @SerialName("anime_id")
    val animeId: Int,
    @SerialName("anime_name")
    val animeName: String,
    @SerialName("anime_description")
    val animeDescription: String,
    @SerialName("anime_cover_url")
    val animeCoverUrl: String,
    @SerialName("anime_genre")
    val animeGenre: String,
)

@Serializable
data class AnimeSeasonDto(
    @SerialName("season_id")
    val seasonId: Int,
    @SerialName("season_name")
    val seasonName: String,
    @SerialName("season_number")
    val seasonNumber: Int,
    @SerialName("season_dubbed")
    val seasonDubbed: Int,
)

@Serializable
data class AnimeResultDto(
    @SerialName("anime_details")
    val animeDetails: AnimeDetailsDto,
    @SerialName("anime_seasons")
    val animeSeasons: List<AnimeSeasonDto>,
)

@Serializable
data class EpisodesItemDto(
    @SerialName("ep_id")
    val epId: Int,
    @SerialName("ep_name")
    val epName: String,
    @SerialName("ep_number")
    val epNumber: Float,
)

@Serializable
data class EpisodesResultDto(
    val data: List<EpisodesItemDto>,
)

@Serializable
data class EpisodeStreamDto(
    val shd: String?,
    val mhd: String?,
    val fhd: String?,
)

@Serializable
data class EpisodeInfoDto(
    val streams: EpisodeStreamDto,
)
