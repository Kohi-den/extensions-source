package eu.kanade.tachiyomi.animeextension.es.hentaila

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object HentailaFilters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=${vals[state].second}" } ?: run { "" }
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    open class CheckBoxFilter(name: String) : AnimeFilter.CheckBox(name) {
        fun toQueryPart(name: String): String {
            return this.state.takeIf { it }?.let { "&$name=true" } ?: run { "" }
        }
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }.joinToString("&$name=").let {
                if (it.isBlank()) {
                    ""
                } else {
                    "&$name=$it"
                }
            }
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)
    }

    private inline fun <reified R> AnimeFilterList.checkAsQueryPart(name: String): String {
        return (this.getFirst<R>() as CheckBoxFilter).toQueryPart(name)
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private fun String.changePrefix() = this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: run { this }

    data class FilterSearchParams(val filter: String = "") { fun getQuery() = filter.changePrefix() }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(HentailaFiltersData.GENRES, "genre") +
                filters.asQueryPart<YearMinFilter>("minYear") +
                filters.asQueryPart<YearMaxFilter>("maxYear") +
                filters.asQueryPart<StatusFilter>("status") +
                filters.asQueryPart<SortFilter>("order") +
                filters.checkAsQueryPart<CheckBoxFilter>("uncensored"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenresFilter(),
        YearMinFilter(),
        YearMaxFilter(),
        StatusFilter(),
        AnimeFilter.Separator(),
        CensoredFilter(),
        AnimeFilter.Separator(),
        SortFilter(),
    )

    class GenresFilter : CheckBoxFilterList("Género", HentailaFiltersData.GENRES.map { CheckBoxVal(it.first, false) })
    class YearMinFilter : QueryPartFilter("Min. Año", arrayOf(Pair("Todos", "")) + HentailaFiltersData.YEARS)
    class YearMaxFilter : QueryPartFilter("Max. Año", arrayOf(Pair("Todos", "")) + HentailaFiltersData.YEARS.reversed())
    class StatusFilter : QueryPartFilter("Estado", HentailaFiltersData.STATUS)
    class SortFilter : QueryPartFilter("Ordenar por", HentailaFiltersData.SORT)
    class CensoredFilter : CheckBoxFilter("Sin censura")

    private object HentailaFiltersData {
        val GENRES = arrayOf(
            Pair("3D", "3d"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Casadas", "casadas"),
            Pair("Chikan", "chikan"),
            Pair("Ecchi", "ecchi"),
            Pair("Enfermeras", "enfermeras"),
            Pair("Escolares", "escolares"),
            Pair("Futanari", "futanari"),
            Pair("Gore", "gore"),
            Pair("Hardcore", "hardcore"),
            Pair("Harem", "harem"),
            Pair("Incesto", "incesto"),
            Pair("Juegos Sexuales", "juegos-sexuales"),
            Pair("Suspenso", "suspenso"),
            Pair("Milfs", "milfs"),
            Pair("Maids", "maids"),
            Pair("Netorare", "netorare"),
            Pair("Ninfomania", "ninfomania"),
            Pair("Ninjas", "ninjas"),
            Pair("Orgias", "orgias"),
            Pair("Romance", "romance"),
            Pair("Shota", "shota"),
            Pair("Softcore", "softcore"),
            Pair("Succubus", "succubus"),
            Pair("Teacher", "teacher"),
            Pair("Tentaculos", "tentaculos"),
            Pair("Tetonas", "tetonas"),
            Pair("Vanilla", "vanilla"),
            Pair("Violacion", "violacion"),
            Pair("Virgenes", "virgenes"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Bondage", "bondage"),
            Pair("Elfas", "elfas"),
            Pair("Petit", "petit"),
            Pair("Threesome", "threesome"),
            Pair("Paizuri", "paizuri"),
            Pair("Gal", "gal"),
            Pair("Oyakodon", "oyakodon"),
        )
        val YEARS = (1991..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.toTypedArray()
        val STATUS = arrayOf(
            Pair("Todos", ""),
            Pair("Finalizado", "finalizado"),
            Pair("Próximamente", "proximamente"),
            Pair("En emisión", "emision"),
        )
        val SORT = arrayOf(
            Pair("Predeterminado", ""),
            Pair("Puntuación", "score"),
            Pair("Populares", "popular"),
            Pair("Título", "title"),
            Pair("Últimos agregados", "latest_added"),
            Pair("Últimos estrenos", "latest_released"),
        )
    }
}
