package eu.kanade.tachiyomi.animeextension.en.sakuhentai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object SakuhentaiFilters {

    open class SelectFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun getValue() = vals[state].second
    }

    private val SERIES_PAIRS = arrayOf(
        Pair("None", ""),
        Pair("Naruto", "naruto-hentai"),
        Pair("One Piece", "one-piece"),
        Pair("Bleach", "bleach"),
        Pair("Genshin Impact", "genshin-impact"),
        Pair("Spy x Family", "spy-x-family"),
    )

    class SeriesFilter : SelectFilter("Series", SERIES_PAIRS)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("Note: Series filter overrides text search"),
        SeriesFilter(),
    )

    data class FilterSearchParams(
        val series: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        var series = ""

        filters.forEach { filter ->
            when (filter) {
                is SeriesFilter -> series = filter.getValue()
                else -> {}
            }
        }

        return FilterSearchParams(series)
    }
}
