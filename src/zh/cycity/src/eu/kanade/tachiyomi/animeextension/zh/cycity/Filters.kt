package eu.kanade.tachiyomi.animeextension.zh.cycity

import eu.kanade.tachiyomi.animeextension.zh.cycity.Cycity.Companion.CALENDAR
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

class TypeFilter : AnimeFilter.Select<String>("频道", arrayOf("TV番组", "剧场番组", "4K专区")) {
    override fun toString() = arrayOf("20", "21", "26")[state]
}

class ClassFilter : AnimeFilter.Select<String>(
    "类型",
    arrayOf(
        "全部", "原创", "漫画改", "小说改", "游戏改", "特摄", "热血", "穿越",
        "奇幻", "战斗", "搞笑", "日常", "科幻", "治愈", "校园", "泡面", "恋爱",
        "后宫", "少女", "百合", "魔法", "冒险", "历史", "架空", "机战", "运动",
        "励志", "音乐", "推理", "社团", "智斗", "催泪", "美食", "偶像", "乙女",
        "职场",
    ),
) {
    override fun toString() = values[state]
}

class YearFilter : AnimeFilter.Select<String>(
    "年份",
    CALENDAR.get(Calendar.YEAR).inc().let { current ->
        Array(current - 1999) { (current - it).toString() }.also { it[0] = "全部" }
    },
) {
    override fun toString() = values[state]
}
