package eu.kanade.tachiyomi.animeextension.pt.sushianimes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeDto(
    val episodio: String,
    val link: String,
    @SerialName("episode_name")
    val episodeName: String,
    val audio: String?,
    val update: String,
)

@Serializable
data class SearchResponseDto(
    val data: List<SearchAnimeDto>,
)

@Serializable
data class SearchAnimeDto(
    val id: Int,
    val name: String,
    val image: String,
    val url: String,
    val type: String,
)

@Serializable
data class AnimeDto(
    @SerialName("@context")
    val context: String? = null,
    @SerialName("@type")
    val type: List<String>? = null,
    val name: String,
    val genre: List<String> = emptyList(),
    val status: String? = null,
    val contentRating: String? = null,
    val additionalProperty: List<AdditionalPropertyDto> = emptyList(),
    val image: String? = null,
    val datePublished: String? = null,
    val description: String? = null,
    val potentialAction: PotentialActionDto? = null,
    val trailer: TrailerDto? = null,
    val timeRequired: String? = null,
    val containsSeason: List<SeasonDto> = emptyList(),
    val aggregateRating: AggregateRatingDto? = null,
    val director: PersonDto? = null,
    val review: ReviewDto? = null,
)

@Serializable
data class AdditionalPropertyDto(
    @SerialName("@type")
    val type: String? = null,
    val name: String,
    val value: String? = null,
)

@Serializable
data class PotentialActionDto(
    @SerialName("@type")
    val type: String? = null,
    val target: String? = null,
)

@Serializable
data class TrailerDto(
    @SerialName("@type")
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val thumbnail: ImageObjectDto? = null,
    val uploadDate: String? = null,
    val embedUrl: String? = null,
    val duration: String? = null,
    val timeRequired: String? = null,
    val publisher: OrganizationDto? = null,
    val interactionCount: String? = null,
)

@Serializable
data class ImageObjectDto(
    @SerialName("@type")
    val type: String? = null,
    val contentUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class OrganizationDto(
    @SerialName("@type")
    val type: String? = null,
    val name: String? = null,
    val logo: ImageObjectDto? = null,
)

@Serializable
data class SeasonDto(
    @SerialName("@type")
    val type: String? = null,
    val seasonNumber: String? = null,
    val numberOfEpisodes: String? = null,
    val episode: List<EpisodeSchemaDto> = emptyList(),
)

@Serializable
data class EpisodeSchemaDto(
    @SerialName("@type")
    val type: String? = null,
    val episodeNumber: String,
    val name: String? = null,
    val datePublished: String,
    val url: String,
)

@Serializable
data class AggregateRatingDto(
    @SerialName("@type")
    val type: String? = null,
    val ratingValue: String? = null,
    val bestRating: String? = null,
    val worstRating: String? = null,
    val ratingCount: String? = null,
)

@Serializable
data class PersonDto(
    @SerialName("@type")
    val type: String? = null,
    val name: String? = null,
)

@Serializable
data class ReviewDto(
    @SerialName("@type")
    val type: String? = null,
    val author: ReviewAuthorDto? = null,
    val datePublished: String? = null,
    val reviewBody: String? = null,
)

@Serializable
data class ReviewAuthorDto(
    @SerialName("@type")
    val type: String? = null,
    val name: String? = null,
)
