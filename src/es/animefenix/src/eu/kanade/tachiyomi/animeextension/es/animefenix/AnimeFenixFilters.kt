package eu.kanade.tachiyomi.animeextension.es.animefenix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AnimeFenixFilters {
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
            filters.parseCheckbox<GenresFilter>(AnimeFenixFiltersData.GENRES, "genero") +
                filters.parseCheckbox<YearsFilter>(AnimeFenixFiltersData.YEARS, "year") +
                filters.parseCheckbox<TypesFilter>(AnimeFenixFiltersData.TYPES, "type") +
                filters.parseCheckbox<StateFilter>(AnimeFenixFiltersData.STATE, "estado") +
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

    class GenresFilter : CheckBoxFilterList("Género", AnimeFenixFiltersData.GENRES.map { CheckBoxVal(it.first, false) })

    class YearsFilter : CheckBoxFilterList("Año", AnimeFenixFiltersData.YEARS.map { CheckBoxVal(it.first, false) })

    class TypesFilter : CheckBoxFilterList("Tipo", AnimeFenixFiltersData.TYPES.map { CheckBoxVal(it.first, false) })

    class StateFilter : CheckBoxFilterList("Estado", AnimeFenixFiltersData.STATE.map { CheckBoxVal(it.first, false) })

    class SortFilter : QueryPartFilter("Orden", AnimeFenixFiltersData.SORT)

    object AnimeFenixFiltersData {
        val YEARS = (1990..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val TYPES = arrayOf(
            Pair("TV", "tv"),
            Pair("Película", "movie"),
            Pair("Especial", "special"),
            Pair("OVA", "ova"),
            Pair("DONGHUA", "donghua"),
        )

        val STATE = arrayOf(
            Pair("Emisión", "1"),
            Pair("Finalizado", "2"),
            Pair("Próximamente", "3"),
            Pair("En Cuarentena", "4"),
        )

        val SORT = arrayOf(
            Pair("Por Defecto", "default"),
            Pair("Recientemente Actualizados", "updated"),
            Pair("Recientemente Agregados", "added"),
            Pair("Nombre A-Z", "title"),
            Pair("Calificación", "likes"),
            Pair("Más Vistos", "visits"),
        )

        val GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Ángeles", "angeles"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventura", "aventura"),
            Pair("Ciencia Ficción", "Ciencia Ficción"),
            Pair("Comedia", "comedia"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Dragones", "dragones"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Fantasía", "fantasia"),
            Pair("Gore", "gore"),
            Pair("Harem", "harem"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Infantil", "infantil"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "Musica"),
            Pair("Ninjas", "ninjas"),
            Pair("Parodia", "parodia"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuerdos de la vida", "Recuerdos de la vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Slice of life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Space", "space"),
            Pair("Spokon", "spokon"),
            Pair("Steampunk", "steampunk"),
            Pair("Superpoder", "superpoder"),
            Pair("Thriller", "thriller"),
            Pair("Vampiro", "vampiro"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Zombies", "zombies"),
        )
    }
}
