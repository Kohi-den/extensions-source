package eu.kanade.tachiyomi.animeextension.es.zonaleros

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodesDto(
    @SerialName("paginate_url") var paginateUrl: String? = null,
    @SerialName("perpage") var perpage: Double? = null,
    @SerialName("eps") var eps: ArrayList<Eps> = arrayListOf(),
)

@Serializable
data class Eps(
    @SerialName("num") var num: Int? = null,
)

@Serializable
data class EpisodeInfoDto(
    @SerialName("default") var default: String? = null,
    @SerialName("caps") var caps: ArrayList<Caps> = arrayListOf(),
)

@Serializable
data class Caps(
    @SerialName("episodio") var episodio: Int? = null,
    @SerialName("url") var url: String? = null,
    @SerialName("thumb") var thumb: String? = null,
)
