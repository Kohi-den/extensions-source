package eu.kanade.tachiyomi.animeextension.pt.funanimetv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object FunAnimeTVFilters {
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

    class GenreFilter : QueryPartFilter("Gênero", FunAnimeTVFiltersData.GENRES_LIST)

    val FILTER_LIST
        get() = AnimeFilterList(
            GenreFilter(),
        )

    data class FilterSearchParams(
        val genre: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<GenreFilter>(),
        )
    }

    private object FunAnimeTVFiltersData {
        private val SELECT = Pair("<Selecione>", "")

        val GENRES_LIST = arrayOf(
            Pair("", "Todos"),
            Pair("aventura", "Aventura"),
            Pair("ação", "Ação"),
            Pair("comédia", "Comédia"),
            Pair("drama", "Drama"),
            Pair("ecchi", "Ecchi"),
            Pair("esportes", "Esportes"),
            Pair("fantasia", "Fantasia"),
            Pair("mecha", "Mecha"),
            Pair("mistério", "Mistério"),
            Pair("música", "Música"),
            Pair("romance", "Romance"),
            Pair("sci-fi", "Sci-Fi"),
            Pair("shoujo", "Shoujo"),
            Pair("shounen", "Shounen"),
            Pair("slice of life", "Slice of Life"),
            Pair("sobrenatural", "Sobrenatural"),
        )
    }
}
