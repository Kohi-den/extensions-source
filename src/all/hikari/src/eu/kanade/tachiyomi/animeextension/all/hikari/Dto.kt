package eu.kanade.tachiyomi.animeextension.all.hikari

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CatalogResponseDto<T>(
    val next: String? = null,
    val results: List<T>,
)

@Serializable
data class AnimeDto(
    val uid: String,

    @SerialName("ani_ename")
    val aniEName: String? = null,
    @SerialName("ani_name")
    val aniName: String,
    @SerialName("ani_poster")
    val aniPoster: String? = null,
    @SerialName("ani_synopsis")
    val aniSynopsis: String? = null,
    @SerialName("ani_synonyms")
    val aniSynonyms: String? = null,
    @SerialName("ani_genre")
    val aniGenre: String? = null,
    @SerialName("ani_studio")
    val aniStudio: String? = null,
    @SerialName("ani_stats")
    val aniStats: Int? = null,
) {
    fun toSAnime(preferEnglish: Boolean): SAnime = SAnime.create().apply {
        url = uid
        title = if (preferEnglish) aniEName?.takeUnless(String::isBlank) ?: aniName else aniName
        thumbnail_url = aniPoster
        genre = aniGenre?.split(",")?.joinToString(transform = String::trim)
        author = aniStudio
        description = buildString {
            aniSynopsis?.trim()?.let(::append)
            append("\n\n")
            aniSynonyms?.let { append("Synonyms: $it") }
        }.trim()

        status = when (aniStats) {
            1 -> SAnime.ONGOING
            2 -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }
}

@Serializable
data class LatestEpisodeDto(
    val uid: Int,
    val title: String,
    @SerialName("title_en")
    val titleEn: String? = null,
    val imageUrl: String,
) {
    fun toSAnime(preferEnglish: Boolean): SAnime = SAnime.create().apply {
        url = uid.toString()
        title = if (preferEnglish) titleEn?.takeUnless(String::isBlank) ?: this@LatestEpisodeDto.title else this@LatestEpisodeDto.title
        thumbnail_url = imageUrl
    }
}

@Serializable
data class EpisodeDto(
    @SerialName("ep_id_name")
    val epId: String,
    @SerialName("ep_name")
    val epName: String? = null,
) {
    fun toSEpisode(uid: String): SEpisode = SEpisode.create().apply {
        url = "$uid-$epId"
        name = epName?.let { "Ep. $epId - $it" } ?: "Episode $epId"
        episode_number = epId.toFloatOrNull() ?: 1f
    }
}

@Serializable
data class EmbedDto(
    @SerialName("embed_type")
    val embedType: String,
    @SerialName("embed_name")
    val embedName: String,
    @SerialName("embed_frame")
    val embedFrame: String,
)
