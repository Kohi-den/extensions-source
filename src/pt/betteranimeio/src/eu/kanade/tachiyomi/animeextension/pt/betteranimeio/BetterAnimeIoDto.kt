package eu.kanade.tachiyomi.animeextension.pt.betteranimeio

import kotlinx.serialization.Serializable

@Serializable
data class VideoApiResponse(
    val status: String,
    val message: String? = null,
    val size: String? = null,
    val duration: String? = null,
    val play: List<VideoSource> = emptyList(),
)

@Serializable
data class VideoSource(
    val src: String,
    val type: String? = null,
    val size: Int = 0,
    val sizeText: String = "Default",
)
