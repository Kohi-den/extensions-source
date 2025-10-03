@file:Suppress("LocalVariableName", "PropertyName")

package eu.kanade.tachiyomi.animeextension.zh.xiaoxintv

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREF_KEY_FILTER_CONFIG_PREFIX = "STORED_SEARCH_CONFIG"

open class PathFilter(name: String, private val beans: Array<out SearchBean>) :
    AnimeFilter.Select<String>(name, beans.map { it.name }.toTypedArray()) {
    val selected
        get() = beans[state]
}

class GroupFilter(name: String, filters: List<PathFilter>) :
    AnimeFilter.Group<PathFilter>(name, filters)

internal enum class FilterType(val title: String) {
    TYPE("类型"),
    CLASS("分类"),
    YEAR("年份"),
    LANG("语言"),
    SORT("排序"),
    REGION("地区"),
}

interface SearchBean {
    val name: String
    val ignore: Boolean
    fun toPath(): String
}

@Serializable
data class SearchType(
    override val name: String,
    val id: String,
    override val ignore: Boolean = false,
) : SearchBean {
    constructor(name: String, id: Int) : this(name, "$id")

    override fun toPath() = "/id/$id"
}

internal fun SearchType.toFilter(): PathFilter {
    return PathFilter(name, arrayOf(this))
}

@Serializable
data class SearchSort(
    override val name: String,
    val by: String,
    override val ignore: Boolean = false,
) : SearchBean {
    override fun toPath() = "/by/$by"
}

@Serializable
data class SearchYear(override val name: String, override val ignore: Boolean = false) :
    SearchBean {
    override fun toPath() = "/year/$name"
}

@Serializable
data class SearchLang(override val name: String, override val ignore: Boolean = false) :
    SearchBean {
    override fun toPath() = "/lang/$name"
}

@Serializable
data class SearchClass(override val name: String, override val ignore: Boolean = false) :
    SearchBean {
    override fun toPath() = "/class/$name"
}

@Serializable
data class SearchRegion(override val name: String, override val ignore: Boolean = false) :
    SearchBean {
    override fun toPath() = "/area/$name"
}

@Serializable
data class SearchFilterConfig(
    val type: List<SearchType>,
    val category: List<SearchClass> = emptyList(),
    val year: List<SearchYear> = emptyList(),
    val lang: List<SearchLang> = emptyList(),
    val region: List<SearchRegion> = emptyList(),
) {
    fun isEmpty() =
        type.isEmpty() && category.isEmpty() && year.isEmpty() && lang.isEmpty() && region.isEmpty()
}

private inline fun <reified T> c(): Class<T> {
    return T::class.java
}

private val searchPriority = arrayOf(
    c<SearchRegion>(),
    c<SearchSort>(),
    c<SearchClass>(),
    c<SearchType>(),
    c<SearchLang>(),
    c<SearchYear>(),
)

internal fun Iterable<SearchBean>.toPath(): String {
    return this.asSequence().filterNot { it.ignore }
        .groupBy { it::class.java }.flatMap { it.value.subList(it.value.size - 1, it.value.size) }
        .sortedBy {
            searchPriority.indexOf(it::class.java)
        }
        .joinToString(separator = "") { it.toPath() }.removePrefix("/")
}

private val defaultLangList =
    listOf(
        SearchLang("全部", ignore = true),
        SearchLang("国语"),
        SearchLang("粤语"),
        SearchLang("英语"),
        SearchLang("其他"),
    )

private val typeAll = SearchType("全部", "-1", ignore = true)
private val categoryAll = SearchClass("全部", ignore = true)
private val yearAll = SearchYear("全部", ignore = true)
private val regionAll = SearchRegion("全部", ignore = true)

private val defaultSearchFilterConfig = mapOf(
    // anime
    "5" to SearchFilterConfig(
        type = listOf(typeAll, SearchType("国产动漫", 51), SearchType("日本动漫", 52)),
        category = listOf(
            categoryAll,
            SearchClass("热血"),
            SearchClass("格斗"),
            SearchClass("其他"),
        ),
        year = listOf(yearAll),
        lang = defaultLangList,
    ),
    // movie
    "7" to SearchFilterConfig(
        type = listOf(typeAll),
        region = listOf(regionAll),
        year = listOf(yearAll),
        lang = defaultLangList,
    ),
    // tv
    "6" to SearchFilterConfig(
        type = listOf(typeAll),
        category = listOf(categoryAll),
        year = listOf(yearAll),
        lang = defaultLangList,
    ),
    // variety show
    "3" to SearchFilterConfig(
        type = listOf(typeAll),
        category = listOf(categoryAll),
        year = listOf(yearAll),
        lang = defaultLangList,
    ),
    // documentary
    "21" to SearchFilterConfig(
        type = emptyList(),
        region = listOf(regionAll),
        year = listOf(yearAll),
        lang = defaultLangList,
    ),
    // short show
    "64" to SearchFilterConfig(
        type = listOf(typeAll),
    ),
)

private fun findDefaultSearchFilterConfig(majorTypeId: String): SearchFilterConfig {
    return defaultSearchFilterConfig.getOrElse(majorTypeId) {
        SearchFilterConfig(
            listOf(typeAll),
        )
    }
}

private fun genFilterConfigKey(majorTypeId: String): String {
    return PREF_KEY_FILTER_CONFIG_PREFIX + "_$majorTypeId"
}

internal val defaultMajorSearchTypeSet = arrayOf(
    SearchType("动漫", 5),
    SearchType("电影", 7),
    SearchType("电视剧", 6),
    SearchType("综艺", 3),
    SearchType("纪录片", 21),
    SearchType("短剧", 64),
)

internal val defaultSortTypeSet =
    arrayOf(
        SearchSort("时间", "time", ignore = true),
        SearchSort("人气", "hits"),
        SearchSort("评分", "score"),
    )

fun SharedPreferences.findSearchFilterConfig(majorTypeId: String, json: Json): SearchFilterConfig {
    // check shared preferences
    return getString(genFilterConfigKey(majorTypeId), null)?.let { json.decodeFromString(it) }
        ?: findDefaultSearchFilterConfig(majorTypeId)
}

fun SharedPreferences.saveSearchFilterConfig(
    majorTypeId: String,
    searchFilterConfig: SearchFilterConfig,
    json: Json,
) {
    edit().putString(genFilterConfigKey(majorTypeId), json.encodeToString(searchFilterConfig))
        .apply()
}
