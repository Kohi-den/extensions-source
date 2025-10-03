package eu.kanade.tachiyomi.multisrc.anilist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AniListAnimeListResponse(val data: Data) {
    @Serializable
    data class Data(@SerialName("Page") val page: Page) {
        @Serializable
        data class Page(
            val pageInfo: PageInfo,
            val media: List<AniListMedia>,
        ) {
            @Serializable
            data class PageInfo(val hasNextPage: Boolean)
        }
    }
}

@Serializable
internal data class AniListAnimeDetailsResponse(val data: Data) {
    @Serializable
    data class Data(@SerialName("Media") val media: AniListMedia)
}

@Serializable
internal data class AniListMedia(
    val id: Int,
    val title: Title,
    val coverImage: CoverImage,
    val description: String?,
    val status: Status,
    val genres: List<String>,
    val studios: Studios,
) {
    @Serializable
    data class Title(
        val romaji: String,
        val english: String?,
        val native: String?,
    )

    @Serializable
    data class CoverImage(val large: String)

    enum class Status {
        RELEASING,
        FINISHED,
        NOT_YET_RELEASED,
        CANCELLED,
        HIATUS,
    }

    @Serializable
    data class Studios(val nodes: List<Node>) {
        @Serializable
        data class Node(val name: String)
    }
}
