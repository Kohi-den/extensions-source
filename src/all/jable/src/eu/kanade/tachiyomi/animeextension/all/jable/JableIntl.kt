package eu.kanade.tachiyomi.animeextension.all.jable

internal interface Intl {
    val popular: String
    val latestUpdate: String
    val sortLatestUpdate: String
    val sortMostView: String
    val sortMostFavorite: String
    val sortRecentBest: String
    val hotDay: String
    val hotWeek: String
    val hotMonth: String
    val hotAll: String
    val filterPopularSortTitle: String
    val filterTagsSortTitle: String
    val filterTagTitle: String
}

internal class JableIntl private constructor(delegate: Intl) : Intl by delegate {
    constructor(lang: String) : this(
        when (lang) {
            "zh" -> ZH()
            "jp" -> JP()
            "en" -> EN()
            else -> ZH()
        },
    )
}

internal class ZH : Intl {
    override val popular: String = "熱度優先"
    override val latestUpdate: String = "新片優先"
    override val sortLatestUpdate: String = "最近更新"
    override val sortMostView: String = "最多觀看"
    override val sortMostFavorite: String = "最高收藏"
    override val sortRecentBest: String = "近期最佳"
    override val hotDay: String = "今日熱門"
    override val hotWeek: String = "本周熱門"
    override val hotMonth: String = "本月熱門"
    override val hotAll: String = "所有時間"
    override val filterPopularSortTitle: String = "熱門排序"
    override val filterTagsSortTitle: String = "通用排序"
    override val filterTagTitle: String = "標籤"
}

internal class JP : Intl {
    override val popular: String = "人気優先"
    override val latestUpdate: String = "新作優先"
    override val sortLatestUpdate: String = "最近更新"
    override val sortMostView: String = "最も見ら"
    override val sortMostFavorite: String = "最もお気に入"
    override val sortRecentBest: String = "最近ベスト"
    override val hotDay: String = "今日のヒット"
    override val hotWeek: String = "今週のヒット"
    override val hotMonth: String = "今月のヒット"
    override val hotAll: String = "全ての時間"
    override val filterPopularSortTitle: String = "人気ソート"
    override val filterTagsSortTitle: String = "一般ソート"
    override val filterTagTitle: String = "タグ"
}

internal class EN : Intl {
    override val popular: String = "Hot"
    override val latestUpdate: String = "Newest"
    override val sortLatestUpdate: String = "Recent Update"
    override val sortMostView: String = "Most Viewed"
    override val sortMostFavorite: String = "Most Favorite"
    override val sortRecentBest: String = "Best Recently"
    override val hotDay: String = "Today"
    override val hotWeek: String = "This Week"
    override val hotMonth: String = "This Month"
    override val hotAll: String = "All Time"
    override val filterPopularSortTitle: String = "Popular Sorting"
    override val filterTagsSortTitle: String = "General Sorting"
    override val filterTagTitle: String = "Tag"
}
