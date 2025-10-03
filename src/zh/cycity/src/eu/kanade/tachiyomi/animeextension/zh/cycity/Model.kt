package eu.kanade.tachiyomi.animeextension.zh.cycity

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VodResponse(
    val page: Int,
    val pagecount: Int,
    val limit: Int,
    val total: Int,
    val list: List<VodInfo>,
)

@Serializable
data class VodInfo(
    @SerialName("vod_id") val id: Int,
    @SerialName("vod_name") val name: String,
    @SerialName("vod_pic") val pic: String,
    @SerialName("vod_actor") val actor: String,
    @SerialName("vod_remarks") val remarks: String,
) {
    fun toSAnime() = SAnime.create().apply {
        url = "/bangumi/$id.html"
        thumbnail_url = pic
        title = name
        author = actor.replace(",,,", "")
    }
}
