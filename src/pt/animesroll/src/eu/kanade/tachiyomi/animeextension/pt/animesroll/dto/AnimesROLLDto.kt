package eu.kanade.tachiyomi.animeextension.pt.animesroll.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class PagePropDto<T>(val pageProps: DataPropDto<T>) {
    val data by lazy { pageProps.data }
}

@Serializable
data class DataPropDto<T>(val data: T)

@Serializable
data class LatestAnimeDto(
    @SerialName("data_releases")
    val releases: List<ReleaseDto>,
) {
    @Serializable
    data class ReleaseDto(
        @SerialName("id_lancamento")
        val idLancamento: Int,
        @SerialName("id_episodio")
        val idEpisodio: Int? = null,
        @SerialName("id_movie")
        val idMovie: Int? = null,
        val fixed: Int,
        val movie: MovieReleaseDto? = null,
        val episode: EpisodeReleaseDto? = null,
    )

    @Serializable
    data class MovieReleaseDto(
        @SerialName("id_filme")
        val idFilme: Int,
        @SerialName("nome_filme")
        val nomeFilme: String,
        @SerialName("slug_filme")
        val slugFilme: String,
        @SerialName("generate_id")
        val generateId: String,
    )

    @Serializable
    data class EpisodeReleaseDto(
        @SerialName("premiere_last_ep")
        val premiereLastEp: Int? = null,
        @SerialName("n_episodio")
        val nEpisodio: String? = null,
        @SerialName("generate_id")
        val generateId: String? = null,
        val anime: AnimeReleaseDto? = null,
    )

    @Serializable
    data class AnimeReleaseDto(
        @SerialName("id_serie")
        val idSerie: Int,
        val dub: Int,
        val titulo: String,
        @SerialName("slug_serie")
        val slugSerie: String,
    )
}

@Serializable
data class MovieInfoDto(
    @SerialName("data_movie")
    val movieData: AnimeDataDto,
)

@Serializable
data class AnimeDataDto(
    @SerialName("diretor")
    val director: String = "",
    @JsonNames("nome_filme", "titulo")
    val anititle: String,
    @JsonNames("sinopse", "sinopse_filme")
    val description: String = "",
    @SerialName("slug_serie")
    val slug: String = "",
    @SerialName("slug_filme")
    val slug_movie: String = "",
    @SerialName("duracao")
    val duration: String = "",
    @SerialName("generate_id")
    val id: String = "",
    val animeCalendar: String? = null,
    val od: String = "",
)

@Serializable
data class EpisodeListDto(
    @SerialName("data")
    val episodes: List<EpisodeDto>,
    val meta: MetadataDto,
) {
    @Serializable
    data class MetadataDto(val totalOfPages: Int)
}

@Serializable
data class EpisodeDto(
    @SerialName("n_episodio")
    val episodeNumber: String,
    val anime: AnimeDataDto? = null,
    @SerialName("se_pgad")
    val sePgad: Int? = null,
    @SerialName("data_registro")
    val dataRegistro: String? = null,
)

@Serializable
data class SearchResultsDto(
    @SerialName("data_anime")
    val animes: List<AnimeDataDto>,
    @SerialName("data_filme")
    val movies: List<AnimeDataDto>,
)
