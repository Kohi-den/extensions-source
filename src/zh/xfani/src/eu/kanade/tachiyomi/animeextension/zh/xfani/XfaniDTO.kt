package eu.kanade.tachiyomi.animeextension.zh.xfani

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VodInfo(
    @SerialName("vod_id")
    val vodId: Int,
    @SerialName("vod_level")
    val vodLevel: Int = 0,
    @SerialName("vod_name")
    val vodName: String,
    @SerialName("vod_pic")
    val vodPic: String,
    @SerialName("vod_pic_thumb")
    val vodPicThumb: String = "",
    @SerialName("vod_tag")
    val vodTag: String = "",
    @SerialName("vod_class")
    val vodClass: String,
    @SerialName("vod_remarks")
    val vodRemarks: String,
    @SerialName("vod_serial")
    val vodSerial: String,
    @SerialName("vod_sub")
    val vodSub: String,
    @SerialName("vod_actor")
    val vodActor: String,
    @SerialName("vod_blurb")
    val vodBlurb: String,
)

@Serializable
data class VodResponse(
    val page: Int,
    @SerialName("pagecount")
    val pageCount: Int,
    val limit: Int,
    val total: Int,
    val list: List<VodInfo>,
)
