package eu.kanade.tachiyomi.animeextension.zh.hanime1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class QueryFilter(name: String, val key: String, values: Array<String>) :
    AnimeFilter.Select<String>(name, values) {
    val selected: String
        get() = if (state == 0) {
            ""
        } else {
            values[state]
        }
}

open class TagFilter(val key: String, name: String, state: Boolean = false) :
    AnimeFilter.CheckBox(name, state)

class GenreFilter(values: Array<String>) :
    QueryFilter(
        "Genre",
        "genre",
        if (values.isNotEmpty()) {
            // Translate each genre
            values.map { chineseGenre ->
                Tags.GENRE_TRANSLATIONS[chineseGenre] ?: chineseGenre
            }.toTypedArray()
        } else {
            // Default fallback in English
            arrayOf("All", "H-Anime", "Short Anime", "Motion Anime")
        },
    )

class SortFilter(values: Array<String>) :
    QueryFilter(
        "Sort by",
        "sort",
        if (values.isNotEmpty()) {
            // Translate each sort option
            values.map { chineseSort ->
                Tags.SORT_TRANSLATIONS[chineseSort] ?: chineseSort
            }.toTypedArray()
        } else {
            // Default fallback in English
            arrayOf("Newest", "Latest Upload", "Today's Ranking", "Weekly Ranking", "Monthly Ranking")
        },
    )

// Change from object to class
class HotFilter : TagFilter("sort", "Weekly Ranking", true)

class YearFilter(values: Array<String>) :
    QueryFilter(
        "Release Year",
        "year",
        if (values.isNotEmpty()) {
            // Translate year options
            values.map { chineseYear ->
                Tags.YEAR_TRANSLATIONS[chineseYear] ?: chineseYear
            }.toTypedArray()
        } else {
            arrayOf("All Years")
        },
    )

class MonthFilter(values: Array<String>) :
    QueryFilter(
        "Release Month",
        "month",
        if (values.isNotEmpty()) {
            // Translate month options
            values.map { chineseMonth ->
                Tags.MONTH_TRANSLATIONS[chineseMonth] ?: chineseMonth
            }.toTypedArray()
        } else {
            arrayOf("All Months")
        },
    )

class DateFilter(yearFilter: YearFilter, monthFilter: MonthFilter) :
    AnimeFilter.Group<QueryFilter>("Release Date", listOf(yearFilter, monthFilter))

class CategoryFilter(name: String, filters: List<TagFilter>) :
    AnimeFilter.Group<TagFilter>(name, filters)

class BroadMatchFilter : TagFilter("broad", "Broad Match")

class TagsFilter(filters: List<AnimeFilter<out Any>>) :
    AnimeFilter.Group<AnimeFilter<out Any>>("Tags", filters)
