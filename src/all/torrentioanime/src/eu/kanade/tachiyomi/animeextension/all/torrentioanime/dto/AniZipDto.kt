package eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AniZipResponse(
    val titles: Map<String, String?>? = null,
    val episodes: Map<String, AniZipEpisode?>? = null,
    val episodeCount: Int? = null,
    val specialCount: Int? = null,
    val images: List<AniZipImage?>? = null,
    val mappings: AniZipMappings? = null,
)

@Serializable
data class AniZipEpisode(
    val episode: String? = null,
    val episodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val title: Map<String, String?>? = null,
    val length: Int? = null,
    val runtime: Int? = null,
    @SerialName("airdate")
    val airDate: String? = null,
    val rating: String? = null,
    @SerialName("anidbEid")
    val aniDbEpisodeId: Long? = null,
    val tvdbShowId: Long? = null,
    val tvdbId: Long? = null,
    val overview: String? = null,
    val image: String? = null,
)

@Serializable
data class AniZipImage(
    val coverType: String? = null,
    val url: String? = null,
)

@Serializable
data class AniZipMappings(
    @SerialName("animeplanet_id")
    val animePlanetId: String? = null,
    @SerialName("kitsu_id")
    val kitsuId: Long? = null,
    @SerialName("mal_id")
    val myAnimeListId: Long? = null,
    val type: String? = null,
    @SerialName("anilist_id")
    val aniListId: Long? = null,
    @SerialName("anisearch_id")
    val aniSearchId: Long? = null,
    @SerialName("anidb_id")
    val aniDbId: Long? = null,
    @SerialName("notifymoe_id")
    val notifyMoeId: String? = null,
    @SerialName("livechart_id")
    val liveChartId: Long? = null,
    @SerialName("thetvdb_id")
    val theTvDbId: Long? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("themoviedb_id")
    val theMovieDbId: String? = null,
)
