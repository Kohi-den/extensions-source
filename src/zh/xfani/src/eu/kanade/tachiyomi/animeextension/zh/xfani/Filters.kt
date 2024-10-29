package eu.kanade.tachiyomi.animeextension.zh.xfani

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

abstract class SelectFilter(name: String, private val options: Array<Pair<String, String>>) :
    AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected
        get() = options[state].second
}

abstract class TagFilter(name: String, values: Array<String>) :
    SelectFilter(
        name,
        values.mapIndexed { index, s ->
            if (index == 0) {
                s to ""
            } else {
                s to s
            }
        }.toTypedArray(),
    )

class TypeFilter(
    kv: Array<Pair<String, String>> = arrayOf(
        "连载新番" to "1",
        "完结旧番" to "2",
        "剧场版" to "3",
    ),
) : SelectFilter("频道", kv)

class ClassFilter(
    tags: Array<String> = arrayOf(
        "全部",
        "搞笑",
        "原创",
        "轻小说改",
        "恋爱",
        "百合",
        "漫改",
    ),
) : TagFilter("类型", tags)

class VersionFilter(
    tags: Array<String> = arrayOf(
        "全部",
        "BD",
        "OVA",
        "SP",
        "OAD",
    ),
) : TagFilter("版本", tags)

class LetterFilter(
    tags: Array<String> = "ABCDEFGHIJKLMNOPQRSTUYWXYZ".map { it.toString() }.toMutableList()
        .also {
            it.add(0, "全部")
            it.add("0-9")
        }.toTypedArray(),
) : TagFilter("字母", tags)

class SortFilter(
    kv: Array<Pair<String, String>> = arrayOf(
        "按最新" to "time",
        "按热门" to "hits",
        "按评分" to "score",
    ),
) : SelectFilter("排序", kv)

class YearFilter(tags: Array<String>) : TagFilter("年份", tags)
