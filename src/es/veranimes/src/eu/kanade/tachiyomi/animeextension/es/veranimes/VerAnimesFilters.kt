package eu.kanade.tachiyomi.animeextension.es.veranimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object VerAnimesFilters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=${vals[state].second}" } ?: run { "" }
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

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
            }.joinToString(",").let {
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

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private fun String.changePrefix() = this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: run { this }

    data class FilterSearchParams(val filter: String = "") { fun getQuery() = filter.changePrefix() }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(VerAnimesFiltersData.GENRES, "genero") +
                filters.parseCheckbox<YearsFilter>(VerAnimesFiltersData.YEARS, "anio") +
                filters.parseCheckbox<TypesFilter>(VerAnimesFiltersData.TYPES, "tipo") +
                filters.asQueryPart<StateFilter>("estado") +
                filters.asQueryPart<SortFilter>("orden"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenresFilter(),
        YearsFilter(),
        TypesFilter(),
        StateFilter(),
        SortFilter(),
    )

    class GenresFilter : CheckBoxFilterList("Género", VerAnimesFiltersData.GENRES.map { CheckBoxVal(it.first, false) })

    class YearsFilter : CheckBoxFilterList("Año", VerAnimesFiltersData.YEARS.map { CheckBoxVal(it.first, false) })

    class TypesFilter : CheckBoxFilterList("Tipo", VerAnimesFiltersData.TYPES.map { CheckBoxVal(it.first, false) })

    class StateFilter : QueryPartFilter("Estado", VerAnimesFiltersData.STATE)

    class SortFilter : QueryPartFilter("Orden", VerAnimesFiltersData.SORT)

    private object VerAnimesFiltersData {

        val YEARS = (1967..2024).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val TYPES = arrayOf(
            Pair("Tv", "tv"),
            Pair("Película", "pelicula"),
            Pair("Especial", "especial"),
            Pair("Ova", "ova"),
        )

        val STATE = arrayOf(
            Pair("Todos", ""),
            Pair("En Emisión", "en-emision"),
            Pair("Finalizado", "finalizado"),
            Pair("Próximamente", "proximamente"),
        )

        val SORT = arrayOf(
            Pair("Descendente", "desc"),
            Pair("Ascendente", "asc"),
        )

        val GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventuras", "aventuras"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Demencia", "demencia"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Harem", "harem"),
            Pair("Historico", "historico"),
            Pair("Infantil", "infantil"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }
}
