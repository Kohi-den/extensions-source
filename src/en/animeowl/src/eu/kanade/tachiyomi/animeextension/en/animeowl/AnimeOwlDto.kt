package eu.kanade.tachiyomi.animeextension.en.animeowl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val total: Int,
    val results: List<Result>,
) {
    @Serializable
    data class Result(
        @SerialName("mal_id")
        val malId: Int,
        @SerialName("anime_name")
        val animeName: String,
        @SerialName("anime_slug")
        val animeSlug: String,
        val image: String,
    )
}

@Serializable
data class EpisodeResponse(
    val sub: List<Episode>,
    val dub: List<Episode>,
    @SerialName("sub_slug")
    val subSlug: String,
    @SerialName("dub_slug")
    val dubSlug: String,
) {
    @Serializable
    data class Episode(
        val id: Double? = null,
        val name: String,
        val lang: String? = null,
        @SerialName("episode_index")
        val episodeIndex: String,
        val href: String? = null,
    )
}

@Serializable
data class LinkData(
    val links: List<Link>,
)

@Serializable
data class Link(
    val url: String,
    val lang: String,
)

@Serializable
data class OwlServers(
    val kaido: List<Stream>? = null,
    val luffy: List<Stream>? = null,
    val zoro: List<Stream>? = null,
)

@Serializable
data class Stream(
    val url: String,
    val resolution: String?,
)
