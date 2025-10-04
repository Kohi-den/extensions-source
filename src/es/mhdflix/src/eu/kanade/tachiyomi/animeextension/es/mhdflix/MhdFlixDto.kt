package eu.kanade.tachiyomi.animeextension.es.mhdflix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class SeoMediaListResponse(
    val status: Int? = null,
    val data: List<SeoMediaDto> = emptyList(),
)

@Serializable
internal data class SeoMediaDto(
    @SerialName("idMedia") val idMedia: Int? = null,
    val slug: String? = null,
    val type: String? = null,
    @SerialName("createad_at") val createadAt: String? = null,
)

@Serializable
internal data class MediaListResponse(
    val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("totalPage") val totalPage: Int? = null,
    @SerialName("currentPage") val currentPage: Int? = null,
    @SerialName("data") val data: JsonElement? = null,
)

@Serializable
internal data class MediaDetailResponse(
    val status: Int? = null,
    val data: MediaDto? = null,
)

@Serializable
internal data class SeasonListResponse(
    val data: List<SeasonDto> = emptyList(),
)

@Serializable
internal data class EpisodeListResponse(
    val status: Int? = null,
    @SerialName("totalPage") val totalPage: Int? = null,
    val data: List<EpisodeDto> = emptyList(),
)

@Serializable
internal data class LinksResponse(
    val status: Int? = null,
    val data: List<LinkDto> = emptyList(),
)

@Serializable
internal data class MediaDto(
    @SerialName("idMedia") val idMedia: Int? = null,
    val title: String? = null,
    val slug: String? = null,
    val type: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val content: String? = null,
    val status: String? = null,
    val runtime: Int? = null,
    val vote: Double? = null,
    val adult: Boolean? = null,
    val genders: List<String>? = null,
    val genre: List<String>? = null,
)

@Serializable
internal data class SeasonDto(
    @SerialName("idSeasson") val idSeasson: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("num") val num: Int? = null,
)

@Serializable
internal data class EpisodeDto(
    @SerialName("idEpisodios") val idEpisodios: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("numEpisode") val numEpisode: Double? = null,
    @SerialName("numSeasson") val numSeasson: Int? = null,
    @SerialName("serieId") val serieId: Int? = null,
    @SerialName("idSerie") val idSerie: Int? = null,
    @SerialName("idMedia") val idMedia: Int? = null,
)

@Serializable
internal data class LinkDto(
    @SerialName("idLink") val idLink: Int? = null,
    val link: String? = null,
    val language: LanguageDto? = null,
    val quality: QualityDto? = null,
    val server: ServerDto? = null,
)

@Serializable
internal data class LanguageDto(
    @SerialName("name") val name: String? = null,
)

@Serializable
internal data class QualityDto(
    @SerialName("name") val name: String? = null,
)

@Serializable
internal data class ServerDto(
    @SerialName("name") val name: String? = null,
)
