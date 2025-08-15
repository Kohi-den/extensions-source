package eu.kanade.tachiyomi.animeextension.es.katanime.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeList(
    @SerialName("ep") var ep: Ep? = Ep(),
    @SerialName("last") var last: Last? = Last(),
)

@Serializable
data class Data(
    @SerialName("numero") var numero: String? = null,
    @SerialName("idserie") var idserie: Int? = null,
    @SerialName("thumb") var thumb: String? = null,
    @SerialName("created_at") var createdAt: String? = null,
    @SerialName("url") var url: String? = null,
)

@Serializable
data class Links(
    @SerialName("url") var url: String? = null,
    @SerialName("label") var label: String? = null,
    @SerialName("active") var active: Boolean? = null,
)

@Serializable
data class Ep(
    @SerialName("current_page") var currentPage: Int? = null,
    @SerialName("data") var data: ArrayList<Data> = arrayListOf(),
    @SerialName("first_page_url") var firstPageUrl: String? = null,
    @SerialName("from") var from: Int? = null,
    @SerialName("last_page") var lastPage: Int? = null,
    @SerialName("last_page_url") var lastPageUrl: String? = null,
    @SerialName("links") var links: ArrayList<Links> = arrayListOf(),
    @SerialName("next_page_url") var nextPageUrl: String? = null,
    @SerialName("path") var path: String? = null,
    @SerialName("per_page") var perPage: Int? = null,
    @SerialName("prev_page_url") var prevPageUrl: String? = null,
    @SerialName("to") var to: Int? = null,
    @SerialName("total") var total: Int? = null,
)

@Serializable
data class Last(
    @SerialName("numero") var numero: String? = null,
)

// ===========================================================

@Serializable
data class CryptoDto(
    @SerialName("ct") var ct: String? = null,
    @SerialName("iv") var iv: String? = null,
    @SerialName("s") var s: String? = null,
)
