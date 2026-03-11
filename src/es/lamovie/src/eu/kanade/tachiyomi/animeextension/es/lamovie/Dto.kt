package eu.kanade.tachiyomi.animeextension.es.lamovie

import eu.kanade.tachiyomi.animeextension.es.lamovie.LaMovie.Companion.DEFAULT_LISTING_TYPE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

data class AnimeContext(
    val type: String,
    val slug: String,
    val id: Long?,
)

data class EpisodeContext(val postId: Long)

@Serializable
data class ListingDataDto(
    val posts: List<PostDto> = emptyList(),
    val pagination: PaginationDto? = null,
)

@Serializable
data class PaginationDto(
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("last_page") val lastPage: Int? = null,
)

@Serializable
data class PostDto(
    @SerialName("_id") val id: Long,
    val title: String = "",
    val overview: String? = null,
    val slug: String = "",
    val images: ImagesDto? = null,
    val type: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val gallery: String? = null,
) {
    val postType: String
        get() = type?.takeIf { it.isNotBlank() } ?: DEFAULT_LISTING_TYPE
}

@Serializable
data class ImagesDto(
    val poster: String? = null,
    val backdrop: String? = null,
)

@Serializable
data class EpisodeDto(
    @SerialName("_id") val id: Long,
    val name: String? = null,
    val title: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val date: String? = null,
)

@Serializable
data class EpisodesListDto(
    val posts: List<EpisodeDto> = emptyList(),
    val pagination: PaginationDto? = null,
    val seasons: List<Int>? = null,
)

@Serializable
data class PlayerDataDto(
    val embeds: JsonElement? = null,
)

data class EmbedItem(
    val server: String,
    val url: String,
    val quality: String? = null,
    val language: String? = null,
)
