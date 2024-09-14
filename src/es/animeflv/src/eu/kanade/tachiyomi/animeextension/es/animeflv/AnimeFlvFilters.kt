package eu.kanade.tachiyomi.animeextension.es.animeflv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AnimeFlvFilters {
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
            }.joinToString("&$name[]=").let {
                if (it.isBlank()) {
                    ""
                } else {
                    "&$name[]=$it"
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
            filters.parseCheckbox<GenresFilter>(AnimeFlvFiltersData.GENRES, "genre") +
                filters.parseCheckbox<YearsFilter>(AnimeFlvFiltersData.YEARS, "year") +
                filters.parseCheckbox<TypesFilter>(AnimeFlvFiltersData.TYPES, "type") +
                filters.parseCheckbox<StateFilter>(AnimeFlvFiltersData.STATE, "status") +
                filters.asQueryPart<SortFilter>("order"),
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

    class GenresFilter : CheckBoxFilterList("Género", AnimeFlvFiltersData.GENRES.map { CheckBoxVal(it.first, false) })

    class YearsFilter : CheckBoxFilterList("Año", AnimeFlvFiltersData.YEARS.map { CheckBoxVal(it.first, false) })

    class TypesFilter : CheckBoxFilterList("Tipo", AnimeFlvFiltersData.TYPES.map { CheckBoxVal(it.first, false) })

    class StateFilter : CheckBoxFilterList("Estado", AnimeFlvFiltersData.STATE.map { CheckBoxVal(it.first, false) })

    class SortFilter : QueryPartFilter("Orden", AnimeFlvFiltersData.SORT)

    private object AnimeFlvFiltersData {
        val YEARS = (1990..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val TYPES = arrayOf(
            Pair("TV", "tv"),
            Pair("Película", "movie"),
            Pair("Especial", "special"),
            Pair("OVA", "ova"),
        )

        val STATE = arrayOf(
            Pair("En emisión", "1"),
            Pair("Finalizado", "2"),
            Pair("Próximamente", "3"),
        )

        val SORT = arrayOf(
            Pair("Por Defecto", "default"),
            Pair("Recientemente Actualizados", "updated"),
            Pair("Recientemente Agregados", "added"),
            Pair("Nombre A-Z", "title"),
            Pair("Calificación", "rating"),
        )

        val GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventuras", "aventura"),
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
