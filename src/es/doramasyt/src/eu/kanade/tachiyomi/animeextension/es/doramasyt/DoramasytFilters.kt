package eu.kanade.tachiyomi.animeextension.es.doramasyt

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object DoramasytFilters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=${vals[state].second}" } ?: run { "" }
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private fun String.changePrefix() = this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: run { this }

    data class FilterSearchParams(val filter: String = "") {
        fun getQuery() = filter.changePrefix()
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.asQueryPart<CategoriesFilter>("categoria") +
                filters.asQueryPart<GenresFilter>("genero") +
                filters.asQueryPart<YearsFilter>("fecha") +
                filters.asQueryPart<LettersFilter>("letra"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        CategoriesFilter(),
        GenresFilter(),
        YearsFilter(),
        LettersFilter(),
    )

    class CategoriesFilter : QueryPartFilter("Categoría", DoramasytFiltersData.CATEGORIES)

    class GenresFilter : QueryPartFilter("Género", DoramasytFiltersData.GENRES)

    class YearsFilter : QueryPartFilter("Año", DoramasytFiltersData.YEARS)

    class LettersFilter : QueryPartFilter("Letra", DoramasytFiltersData.LETTER)

    private object DoramasytFiltersData {
        val CATEGORIES = arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Dorama", "dorama"),
            Pair("Live Action", "live-action"),
            Pair("Pelicula", "pelicula"),
            Pair("Series Turcas", "serie-turcas"),
        )

        val YEARS = arrayOf(Pair("<Seleccionar>", "")) + (1982..2024).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val LETTER = arrayOf(Pair("<Seleccionar>", "")) + ('A'..'Z').map { Pair("$it", "$it") }.toTypedArray()

        val GENRES = arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Policial", "policial"),
            Pair("Romance", "romance"),
            Pair("Comedia", "comedia"),
            Pair("Escolar", "escolar"),
            Pair("Acción", "accion"),
            Pair("Thriller", "thriller"),
            Pair("Drama", "drama"),
            Pair("Misterio", "misterio"),
            Pair("Fantasia", "fantasia"),
            Pair("Histórico", "historico"),
            Pair("Bélico", "belico"),
            Pair("Militar", "militar"),
            Pair("Médico", "medico"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Horror", "horror"),
            Pair("Política", "politica"),
            Pair("Familiar", "familiar"),
            Pair("Melodrama", "melodrama"),
            Pair("Deporte", "deporte"),
            Pair("Comida", "comida"),
            Pair("Supervivencia", "supervivencia"),
            Pair("Aventuras", "aventuras"),
            Pair("Artes marciales", "artes-marciales"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Amistad", "amistad"),
            Pair("Psicológico", "psicologico"),
            Pair("Yuri", "yuri"),
            Pair("K-Drama", "k-drama"),
            Pair("J-Drama", "j-drama"),
            Pair("C-Drama", "c-drama"),
            Pair("HK-Drama", "hk-drama"),
            Pair("TW-Drama", "tw-drama"),
            Pair("Thai-Drama", "thai-drama"),
            Pair("Idols", "idols"),
            Pair("Suspenso", "suspenso"),
            Pair("Negocios", "negocios"),
            Pair("Time Travel", "time-travel"),
            Pair("Crimen ", "crimen"),
            Pair("Yaoi", "yaoi"),
            Pair("Legal", "legal"),
            Pair("Juvenil", "juvenil"),
            Pair("Musical", "musical"),
            Pair("Reality Show", "reality-show"),
            Pair("Documental", "documental"),
            Pair("Turcas", "turcas"),
        )
    }
}
