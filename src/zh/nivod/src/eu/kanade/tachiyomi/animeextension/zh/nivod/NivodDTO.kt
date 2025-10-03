package eu.kanade.tachiyomi.animeextension.zh.nivod

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class RouteInfo(
    @SerialName("playurl")
    val playUrl: String,
    val from: String,
    val name: String,
)

@Serializable
class PlayInfo(
    val pdatas: List<RouteInfo>,
)
