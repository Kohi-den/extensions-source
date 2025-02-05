package eu.kanade.tachiyomi.animeextension.zh.nivod

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import kotlinx.serialization.Serializable

open class QueryFilter(
    name: String,
    private val options: List<Pair<String, String>>,
    val key: String,
) :
    AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected: String
        get() = options[state].second
}

class ChannelFilter : QueryFilter(
    "分类",
    listOf(
        "动漫" to "anime",
        "电影" to "movie",
        "电视剧" to "tv",
        "综艺" to "show",
    ),
    "channel",
)

class YearFilter(options: List<Pair<String, String>>) : QueryFilter("年份", options, "year")

class TypeFilter(options: List<Pair<String, String>>) : QueryFilter("类型", options, "showtype")

class RegionFilter(options: List<Pair<String, String>>) : QueryFilter("地区", options, "region")

@Serializable
internal class FilterConfig(
    val regions: Map<String, String>,
    val types: Map<String, String>,
    val years: Map<String, String>,
)
