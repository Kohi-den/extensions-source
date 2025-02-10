package eu.kanade.tachiyomi.animeextension.es.animejl

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AnimejlFilters {
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
                filters.parseCheckbox<StateFilter>(AnimeFlvFiltersData.STATE, "estado") +
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
            Pair("Anime", "1"),
            Pair("Ova", "2"),
            Pair("Pelicula", "3"),
            Pair("Donghua", "7"),
        )

        val STATE = arrayOf(
            Pair("En emisión", "0"),
            Pair("Finalizado", "1"),
            Pair("Próximamente", "2"),
        )

        val SORT = arrayOf(
            Pair("Por Defecto", "created"),
            Pair("Recientemente Actualizados", "updated"),
            Pair("Nombre A-Z", "titleaz"),
            Pair("Nombre Z-A", "titleza"),
            Pair("Calificación", "rating"),
            Pair("Vistas", "views"),
        )

        val GENRES = arrayOf(
            Pair("Acción", "1"),
            Pair("Artes Marciales", "2"),
            Pair("Aventuras", "3"),
            Pair("Ciencia Ficción", "33"),
            Pair("Comedia", "9"),
            Pair("Cultivación", "71"),
            Pair("Demencia", "40"),
            Pair("Demonios", "42"),
            Pair("Deportes", "27"),
            Pair("Donghua", "50"),
            Pair("Drama", "10"),
            Pair("Ecchi", "25"),
            Pair("Escolares", "22"),
            Pair("Espacial", "48"),
            Pair("Fantasia", "6"),
            Pair("Gore", "67"),
            Pair("Harem", "32"),
            Pair("Hentai", "31"),
            Pair("Historico", "43"),
            Pair("Horror", "39"),
            Pair("Isekai", "45"),
            Pair("Josei", "70"),
            Pair("Juegos", "11"),
            Pair("Latino / Castellano", "46"),
            Pair("Magia", "38"),
            Pair("Mecha", "41"),
            Pair("Militar", "44"),
            Pair("Misterio", "26"),
            Pair("Mitología", "73"),
            Pair("Musica", "28"),
            Pair("Parodia", "13"),
            Pair("Policía", "51"),
            Pair("Psicologico", "29"),
            Pair("Recuentos de la vida", "23"),
            Pair("Reencarnación", "72"),
            Pair("Romance", "12"),
            Pair("Samurai", "69"),
            Pair("Seinen", "24"),
            Pair("Shoujo", "36"),
            Pair("Shounen", "4"),
            Pair("Sin Censura", "68"),
            Pair("Sobrenatural", "7"),
            Pair("Superpoderes", "5"),
            Pair("Suspenso", "21"),
            Pair("Terror", "20"),
            Pair("Vampiros", "49"),
            Pair("Venganza", "74"),
            Pair("Yaoi", "53"),
            Pair("Yuri", "52"),

        )
    }
}
