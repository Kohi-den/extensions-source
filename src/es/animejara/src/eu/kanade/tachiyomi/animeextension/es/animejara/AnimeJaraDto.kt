package eu.kanade.tachiyomi.animeextension.es.animejara

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TemporadaDto(
    @SerialName("numero_temporada") val numeroTemporada: Int,
    @SerialName("poster_temporada") val posterTemporada: String = "",
    val episodios: List<EpisodioDto> = emptyList(),
)

@Serializable
data class EpisodioDto(
    @SerialName("numero_episodio") val numeroEpisodio: String,
    @SerialName("poster_episodio") val posterEpisodio: String = "",
    @SerialName("nombre_episodio") val nombreEpisodio: String = "",
    @SerialName("fecha_actualizacion") val fechaActualizacion: String = "",
    val idiomas: List<String> = emptyList(),
)

@Serializable
data class LiveSearchResponse(
    val success: Boolean,
    val data: LiveSearchData? = null,
)

@Serializable
data class LiveSearchData(
    val animes: List<LiveSearchAnime> = emptyList(),
)

@Serializable
data class LiveSearchAnime(
    val titulo: String,
    val slug: String,
    val poster: String = "",
    val rating: String = "",
    val anio: String = "",
    val vistas: String = "",
    val tipo: String = "",
)
