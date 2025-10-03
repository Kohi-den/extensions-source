package eu.kanade.tachiyomi.animeextension.es.zonaleros

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object ZonalerosFilters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=${vals[state].second}" } ?: run { "" }

        fun toUriPart() = vals[state].second.takeIf { it.isNotEmpty() }?.let { vals[state].second } ?: run { "" }
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

    private inline fun <reified R> AnimeFilterList.asUriPart(): String {
        return (this.getFirst<R>() as QueryPartFilter).toUriPart()
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
            filters.asUriPart<TypesFilter>() +
                filters.parseCheckbox<GenresFilter>(ZonalerosFiltersData.GENRES, "generos") +
                filters.parseCheckbox<YearsFilter>(ZonalerosFiltersData.YEARS, "year") +
                filters.parseCheckbox<StatusFilter>(ZonalerosFiltersData.STATUS, "estado") +
                filters.asQueryPart<SortFilter>("order"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        TypesFilter(),
        GenresFilter(),
        YearsFilter(),
        StatusFilter(),
        SortFilter(),
    )

    class TypesFilter : QueryPartFilter("Tipo", ZonalerosFiltersData.TYPES)

    class GenresFilter : CheckBoxFilterList("Género", ZonalerosFiltersData.GENRES.map { CheckBoxVal(it.first, false) })

    class YearsFilter : CheckBoxFilterList("Año", ZonalerosFiltersData.YEARS.map { CheckBoxVal(it.first, false) })

    class StatusFilter : CheckBoxFilterList("Estado", ZonalerosFiltersData.STATUS.map { CheckBoxVal(it.first, false) })

    class SortFilter : QueryPartFilter("Orden", ZonalerosFiltersData.SORT)

    private object ZonalerosFiltersData {
        val TYPES = arrayOf(
            Pair("Películas", "peliculas-hd-online-lat"),
            Pair("Series", "series-h"),
        )

        val GENRES = arrayOf(
            Pair("Accíon", "1"),
            Pair("Animación", "15"),
            Pair("Anime", "22"),
            Pair("Artes marciales", "25"),
            Pair("Aventura", "2"),
            Pair("Biográfica", "24"),
            Pair("Ciencia Ficcion", "3"),
            Pair("Comedia", "4"),
            Pair("Crimen", "14"),
            Pair("Deportes", "19"),
            Pair("Documental", "27"),
            Pair("Drama", "5"),
            Pair("Eroticos", "32"),
            Pair("Familiar", "26"),
            Pair("Fantasia", "6"),
            Pair("Fantasía", "16"),
            Pair("Guerra", "20"),
            Pair("Hechos Reales", "23"),
            Pair("Humor negro", "29"),
            Pair("Infantil", "7"),
            Pair("Misterio", "10"),
            Pair("Musical", "18"),
            Pair("Navidad", "21"),
            Pair("Romance", "12"),
            Pair("Sci-Fi", "30"),
            Pair("Superhéroes", "28"),
            Pair("Suspenso", "8"),
            Pair("Terror", "9"),
            Pair("Wéstern", "17"),
            Pair("Zombies", "13"),
        )

        val YEARS = (1990..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val STATUS = arrayOf(
            Pair("Estreno", "Estreno"),
            Pair("Resubido", "Resubido"),
            Pair("Actualizado", "Actualizado"),
            Pair("Nueva Calidad", "Nueva Calidad"),
        )

        val SORT = arrayOf(
            Pair("Por Defecto", "created"),
            Pair("Recientemente Actualizados", "updated"),
            Pair("Fecha de lanzamiento", "published"),
            Pair("Nombre A-Z", "titleaz"),
            Pair("Nombre Z-A", "titleza"),
            Pair("Calificación", "rating"),
            Pair("Vistas", "views"),
        )
    }
}
