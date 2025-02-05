package eu.kanade.tachiyomi.animeextension.zh.iyf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
class CommonResponse<T>(
    val data: Data<T>,
) {
    @Serializable
    class Data<T>(
        val info: List<T>,
    )
}

@Serializable
class SearchResult(
    val result: List<SearchResultItem>,
)

@Serializable
class SearchResultItem(
    @JsonNames("imgPath")
    val image: String,
    val key: String?,
    val title: String,
    val videoClassID: String,
    val contxt: String,
) {
    val vid: String
        get() = key ?: contxt
}

@Serializable
class VideoDetail(
    @SerialName("add_date")
    val addDate: String,
    @SerialName("contxt")
    val context: String,
    @SerialName("updateweekly")
    val updateWeekly: String,
    val imgPath: String,
    val title: String,
    val directors: List<String>,
    val stars: List<String>,
    val keyWord: String,
    val serialCount: Int,
)

@Serializable
class PlayList(
    val playList: List<PlayListItem>,
)

@Serializable
class PlayListItem(
    val key: String,
    val name: String,
    val updateDate: String,
)

@Serializable
class Play(
    val clarity: List<PlayClarity>,
)

@Serializable
class PlayClarity(
    val title: String,
    val path: Path?,
) {
    @Serializable
    class Path(
        val rtmp: String,
    )
}
