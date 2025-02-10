package eu.kanade.tachiyomi.animeextension.all.torrentioanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AniListFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.parseCheckboxList(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkBox -> options.find { it.first == checkBox.name }!!.second }
            .filter(String::isNotBlank)
    }

    private inline fun <reified R> AnimeFilterList.getSort(): String {
        val state = (getFirst<R>() as AnimeFilter.Sort).state ?: return ""
        val index = state.index
        val suffix = if (state.ascending) "" else "_DESC"
        return AniListFiltersData.SORT_LIST[index].second + suffix
    }

    class GenreFilter : CheckBoxFilterList("Genres", AniListFiltersData.GENRE_LIST)
    class YearFilter : QueryPartFilter("Year", AniListFiltersData.YEAR_LIST)
    class SeasonFilter : QueryPartFilter("Season", AniListFiltersData.SEASON_LIST)
    class FormatFilter : CheckBoxFilterList("Format", AniListFiltersData.FORMAT_LIST)
    class StatusFilter : QueryPartFilter("Airing Status", AniListFiltersData.STATUS_LIST)

    class SortFilter : AnimeFilter.Sort(
        "Sort",
        AniListFiltersData.SORT_LIST.map { it.first }.toTypedArray(),
        Selection(1, false),
    )

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        FormatFilter(),
        GenreFilter(),
        YearFilter(),
        SeasonFilter(),
        StatusFilter(),

    )

    class FilterSearchParams(
        val sort: String = "",
        val format: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        val year: String = "",
        val season: String = "",
        val status: String = "",

    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.getSort<SortFilter>(),
            filters.parseCheckboxList<FormatFilter>(AniListFiltersData.FORMAT_LIST),
            filters.parseCheckboxList<GenreFilter>(AniListFiltersData.GENRE_LIST),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.asQueryPart<StatusFilter>(),

        )
    }

    private object AniListFiltersData {
        val GENRE_LIST = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Horror", "Horror"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Mecha", "Mecha"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Sports", "Sports"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
        )

        val YEAR_LIST: Array<Pair<String, String>> = arrayOf(
            Pair("Any", ""),
        ) + (1940..2025).reversed().map { Pair(it.toString(), it.toString()) }.toTypedArray()

        val SEASON_LIST = arrayOf(
            Pair("Any", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        val FORMAT_LIST = arrayOf(
            Pair("Any", ""),
            Pair("TV Show", "TV"),
            Pair("Movie", "MOVIE"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )

        val STATUS_LIST = arrayOf(
            Pair("Any", ""),
            Pair("Airing", "RELEASING"),
            Pair("Finished", "FINISHED"),
            Pair("Not Yet Aired", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
        )

        val SORT_LIST = arrayOf(
            Pair("Title", "TITLE_ENGLISH"),
            Pair("Popularity", "POPULARITY"),
            Pair("Average Score", "SCORE"),
            Pair("Trending", "TRENDING"),
            Pair("Favorites", "FAVOURITES"),
            Pair("Date Added", "ID"),
            Pair("Release Date", "START_DATE"),
        )
    }
}
