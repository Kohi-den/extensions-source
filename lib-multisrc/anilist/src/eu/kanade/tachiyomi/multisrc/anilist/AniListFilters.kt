package eu.kanade.tachiyomi.multisrc.anilist

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
        GenreFilter(),
        YearFilter(),
        SeasonFilter(),
        FormatFilter(),
        StatusFilter(),
        SortFilter(),
    )

    class FilterSearchParams(
        val genres: List<String> = emptyList(),
        val year: String = "",
        val season: String = "",
        val format: List<String> = emptyList(),
        val status: String = "",
        val sort: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckboxList<GenreFilter>(AniListFiltersData.GENRE_LIST),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.parseCheckboxList<FormatFilter>(AniListFiltersData.FORMAT_LIST),
            filters.asQueryPart<StatusFilter>(),
            filters.getSort<SortFilter>(),
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

        val YEAR_LIST = arrayOf(
            Pair("<Select>", ""),
            Pair("2026", "2026"),
            Pair("2025", "2025"),
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1999", "1999"),
            Pair("1998", "1998"),
            Pair("1997", "1997"),
            Pair("1996", "1996"),
            Pair("1995", "1995"),
            Pair("1994", "1994"),
            Pair("1993", "1993"),
            Pair("1992", "1992"),
            Pair("1991", "1991"),
            Pair("1990", "1990"),
            Pair("1989", "1989"),
            Pair("1988", "1988"),
            Pair("1987", "1987"),
            Pair("1986", "1986"),
            Pair("1985", "1985"),
            Pair("1984", "1984"),
            Pair("1983", "1983"),
            Pair("1982", "1982"),
            Pair("1981", "1981"),
            Pair("1980", "1980"),
            Pair("1979", "1979"),
            Pair("1978", "1978"),
            Pair("1977", "1977"),
            Pair("1976", "1976"),
            Pair("1975", "1975"),
            Pair("1974", "1974"),
            Pair("1973", "1973"),
            Pair("1972", "1972"),
            Pair("1971", "1971"),
            Pair("1970", "1970"),
            Pair("1969", "1969"),
            Pair("1968", "1968"),
            Pair("1967", "1967"),
            Pair("1966", "1966"),
            Pair("1965", "1965"),
            Pair("1964", "1964"),
            Pair("1963", "1963"),
            Pair("1962", "1962"),
            Pair("1961", "1961"),
            Pair("1960", "1960"),
            Pair("1959", "1959"),
            Pair("1958", "1958"),
            Pair("1957", "1957"),
            Pair("1956", "1956"),
            Pair("1955", "1955"),
            Pair("1954", "1954"),
            Pair("1953", "1953"),
            Pair("1952", "1952"),
            Pair("1951", "1951"),
            Pair("1950", "1950"),
            Pair("1949", "1949"),
            Pair("1948", "1948"),
            Pair("1947", "1947"),
            Pair("1946", "1946"),
            Pair("1945", "1945"),
            Pair("1944", "1944"),
            Pair("1943", "1943"),
            Pair("1942", "1942"),
            Pair("1941", "1941"),
            Pair("1940", "1940"),
        )

        val SEASON_LIST = arrayOf(
            Pair("<Select>", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        val FORMAT_LIST = arrayOf(
            Pair("TV Show", "TV"),
            Pair("Movie", "MOVIE"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )

        val STATUS_LIST = arrayOf(
            Pair("<Select>", ""),
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
