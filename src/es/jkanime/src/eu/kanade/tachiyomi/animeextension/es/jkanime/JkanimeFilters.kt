package eu.kanade.tachiyomi.animeextension.es.jkanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object JkanimeFilters {
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

    data class FilterSearchParams(val filter: String = "") {
        fun getQuery() = filter.changePrefix()
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.asQueryPart<SortByFilter>("filtro") +
                filters.asQueryPart<GenresFilter>("genero") +
                filters.asQueryPart<LettersFilter>("letra") +
                filters.asQueryPart<DemographyFilter>("demografia") +
                filters.asQueryPart<CategoryFilter>("categoria") +
                filters.asQueryPart<TypesFilter>("tipo") +
                filters.asQueryPart<StateFilter>("estado") +
                filters.asQueryPart<YearsFilter>("fecha") +
                filters.asQueryPart<SeasonsFilter>("temporada") +
                filters.asQueryPart<SortFilter>("orden"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenresFilter(),
        LettersFilter(),
        DemographyFilter(),
        CategoryFilter(),
        TypesFilter(),
        StateFilter(),
        YearsFilter(),
        SeasonsFilter(),
        SortByFilter(),
        SortFilter(),
    )

    class GenresFilter : QueryPartFilter("Género", VerAnimesFiltersData.GENRES)
    class LettersFilter : QueryPartFilter("Letra", VerAnimesFiltersData.LETTER)
    class DemographyFilter : QueryPartFilter("Demografía", VerAnimesFiltersData.DEMOGRAPHY)
    class CategoryFilter : QueryPartFilter("Categoría", VerAnimesFiltersData.CATEGORY)
    class TypesFilter : QueryPartFilter("Tipo", VerAnimesFiltersData.TYPES)
    class StateFilter : QueryPartFilter("Estado", VerAnimesFiltersData.STATE)
    class YearsFilter : QueryPartFilter("Año", VerAnimesFiltersData.YEARS)
    class SeasonsFilter : QueryPartFilter("Temporada", VerAnimesFiltersData.SEASONS)
    class SortByFilter : QueryPartFilter("Ordenar Por", VerAnimesFiltersData.SORT_BY)
    class SortFilter : QueryPartFilter("Orden", VerAnimesFiltersData.SORT)

    private object VerAnimesFiltersData {
        val GENRES = arrayOf(
            Pair("Todos", ""),
            Pair("Accion", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Autos", "autos"),
            Pair("Comedia", "comedia"),
            Pair("Dementia", "dementia"),
            Pair("Demonios", "demonios"),
            Pair("Misterio", "misterio"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasia", "fantasia"),
            Pair("Juegos", "juegos"),
            Pair("Hentai", "hentai"),
            Pair("Historico", "historico"),
            Pair("Terror", "terror"),
            Pair("Niños", "nios"),
            Pair("Magia", "magia"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Mecha", "mecha"),
            Pair("Musica", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Samurai", "samurai"),
            Pair("Romance", "romance"),
            Pair("Colegial", "colegial"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Space", "space"),
            Pair("Deportes", "deportes"),
            Pair("Super Poderes", "super-poderes"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Harem", "harem"),
            Pair("Cosas de la vida", "cosas-de-la-vida"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Militar", "militar"),
            Pair("Policial", "policial"),
            Pair("Psicologico", "psicologico"),
            Pair("Thriller", "thriller"),
            Pair("Seinen", "seinen"),
            Pair("Josei", "josei"),
            Pair("Español Latino", "latino"),
            Pair("Isekai", "isekai"),
        )

        val LETTER = arrayOf(Pair("Todos", "")) + ('A'..'Z').map { Pair("$it", "$it") }.toTypedArray()

        val DEMOGRAPHY = arrayOf(
            Pair("Todos", ""),
            Pair("Niños", "nios"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Seinen", "seinen"),
            Pair("Josei", "josei"),
        )

        val CATEGORY = arrayOf(
            Pair("Todos", ""),
            Pair("Donghua", "donghua"),
            Pair("Latino", "latino"),
        )

        val TYPES = arrayOf(
            Pair("Todos", ""),
            Pair("Animes", "animes"),
            Pair("Películas", "peliculas"),
            Pair("Especiales", "especiales"),
            Pair("Ovas", "ovas"),
            Pair("Onas", "onas"),
        )

        val STATE = arrayOf(
            Pair("Todos", ""),
            Pair("En Emisión", "emision"),
            Pair("Finalizado", "finalizados"),
            Pair("Por Estrenar", "estrenos"),
        )

        val YEARS = arrayOf(Pair("Todos", "")) + (1981..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val SEASONS = arrayOf(
            Pair("Todos", ""),
            Pair("Primavera", "primavera"),
            Pair("Verano", "verano"),
            Pair("Otoño", "otoño"),
            Pair("Invierno", "invierno"),
        )

        val SORT_BY = arrayOf(
            Pair("Por fecha", ""),
            Pair("Por nombre", "nombre"),
            Pair("Por popularidad", "popularidad"),
        )

        val SORT = arrayOf(
            Pair("Descendente", ""),
            Pair("Ascendente", "asc"),
        )
    }
}
