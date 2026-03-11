package eu.kanade.tachiyomi.animeextension.es.jkanime.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Links(
    @SerialName("url") var url: String? = null,
    @SerialName("label") var label: String? = null,
    @SerialName("active") var active: Boolean? = null,
)

@Serializable
data class Data(
    @SerialName("id") var id: Int? = null,
    @SerialName("number") var number: Int? = null,
    @SerialName("title") var title: String? = null,
    @SerialName("synopsis") var synopsis: String? = null,
    @SerialName("image") var image: String? = null,
    @SerialName("studios") var studios: String? = null,
    @SerialName("slug") var slug: String? = null,
    @SerialName("type") var type: String? = null,
    @SerialName("status") var status: String? = null,
    @SerialName("url") var url: String? = null,
    @SerialName("estado") var estado: String? = null,
    @SerialName("tipo") var tipo: String? = null,
    @SerialName("base64_id") var base64Id: String? = null,
    @SerialName("short_title") var shortTitle: String? = null,
    @SerialName("timestamp") var timestamp: String? = null,
)

@Serializable
data class PopularAnimeModel(
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

/*================================================================================================*/

@Serializable
data class EpisodeAnimeModel(
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

/*================================================================================================*/

@Serializable
data class ServerAnimeModel(
    @SerialName("remote") var remote: String? = null,
    @SerialName("slug") var slug: String? = null,
    @SerialName("server") var server: String? = null,
    @SerialName("lang") var lang: Int? = null,
    @SerialName("size") var size: String? = null,
    @SerialName("append") var append: Int? = null,
)
