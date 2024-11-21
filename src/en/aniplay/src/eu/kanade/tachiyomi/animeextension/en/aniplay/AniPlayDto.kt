package eu.kanade.tachiyomi.animeextension.en.aniplay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeListResponse(
    val episodes: List<Episode>,
    val providerId: String,
    val default: Boolean?,
) {
    @Serializable
    data class Episode(
        val id: String,
        val number: Float,
        val title: String?,
        val hasDub: Boolean?,
        val isFiller: Boolean?,
        val img: String?,
        val description: String?,
        val createdAt: String?,
    )
}

@Serializable
data class VideoSourceRequest(
    val source: String,

    @SerialName("episodeid")
    val episodeId: String,

    @SerialName("episodenum")
    val episodeNum: String,

    @SerialName("subtype")
    val subType: String,
)

@Serializable
data class VideoSourceResponse(
    val sources: List<Source>?,
    val subtitles: List<Subtitle>?,
) {
    @Serializable
    data class Source(
        val url: String,
        val quality: String,
    )

    @Serializable
    data class Subtitle(
        val url: String,
        val lang: String,
    )
}

@Serializable
data class EpisodeExtra(
    val source: String,
    val episodeNum: Float,
    val episodeId: String,
    val hasDub: Boolean,
)

@Serializable
data class EpisodeData(
    val source: String,
    val language: String,
    val response: VideoSourceResponse,
)
