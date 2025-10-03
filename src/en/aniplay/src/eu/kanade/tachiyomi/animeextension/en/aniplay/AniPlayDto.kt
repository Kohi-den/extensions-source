package eu.kanade.tachiyomi.animeextension.en.aniplay

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

// Provider else
@Serializable
data class EpisodeData(
    val source: String,
    val language: String,
    val response: VideoSourceResponse,
)

@Serializable
data class VideoSourceResponse(
    val sources: List<Source>?,
    val audio: List<Audio>?,
    val intro: Timestamp?,
    val outro: Timestamp?,
    val subtitles: List<Subtitle>?,
    val headers: Headers?,
    val proxy: Boolean?,
) {
    @Serializable
    data class Source(
        val url: String,
        val quality: String?,
    )

    @Serializable
    data class Audio(
        val file: String,
        val label: String?,
        val kind: String?,
        val default: Boolean?,
    )

    @Serializable
    data class Timestamp(
        val start: Int?,
        val end: Int?,
    )

    @Serializable
    data class Subtitle(
        val url: String?,
        val lang: String?,
    )

    @Serializable
    data class Headers(
        val Referer: String?,
    )
}

// Extra
@Serializable
data class EpisodeExtra(
    val source: String,
    val episodeNum: Float,
    val episodeId: String,
    val hasDub: Boolean,
)

// Headers
@Serializable
data class DomainHeaders(
    val episodes: String,
    val sources: String,
    val time: Long,
)
