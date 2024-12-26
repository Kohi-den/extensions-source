package eu.kanade.tachiyomi.animeextension.pt.tomato

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object TomatoFilters {
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

    private class CheckBoxVal(name: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (getFirst<R>() as CheckBoxFilterList).state
            .asSequence()
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .toList()
    }

    class GenresFilter : CheckBoxFilterList("Gêneros", TomatoFiltersData.GENRES_LIST)

    val FILTER_LIST
        get() = AnimeFilterList(
            GenresFilter(),
        )

    data class FilterSearchParams(
        val genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(TomatoFiltersData.GENRES_LIST),
        )
    }

    private object TomatoFiltersData {
        private val SELECT = Pair("<Selecione>", "")

        val GENRES_LIST = arrayOf(
            Pair("Aventura", "Aventura"),
            Pair("Ação", "Ação"),
            Pair("Comédia", "Comédia"),
            Pair("Dublado", "Dublado"),
            Pair("Drama", "Drama"),
            Pair("Escolar", "Escolar"),
            Pair("Fantasia", "Fantasia"),
            Pair("Romance", "Romance"),
            Pair("Slice Of Life", "Slice Of Life"),
            Pair("Sobrenatural", "Sobrenatural"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Shounen", "Shounen"),
        )
    }
}
