package eu.kanade.tachiyomi.animeextension.es.animeav1

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeAv1Filters {
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

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private fun String.changePrefix() = this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: run { this }

    data class FilterSearchParams(val filter: String = "") { fun getQuery() = filter.changePrefix() }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<TypesFilter>(AnimeAv1FiltersData.TYPES, "category") +
                filters.parseCheckbox<GenresFilter>(AnimeAv1FiltersData.GENRES, "genre") +
                filters.asQueryPart<StateFilter>("status") +
                filters.asQueryPart<SortFilter>("order"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        TypesFilter(),
        GenresFilter(),
        StateFilter(),
        SortFilter(),
    )

    class TypesFilter : CheckBoxFilterList("Tipo", AnimeAv1FiltersData.TYPES.map { CheckBoxVal(it.first, false) })
    class GenresFilter : CheckBoxFilterList("Género", AnimeAv1FiltersData.GENRES.map { CheckBoxVal(it.first, false) })
    class StateFilter : QueryPartFilter("Estado", AnimeAv1FiltersData.STATE)
    class SortFilter : QueryPartFilter("Orden", AnimeAv1FiltersData.SORT)

    private object AnimeAv1FiltersData {
        val TYPES = arrayOf(
            Pair("Anime", "tv-anime"),
            Pair("Película", "pelicula"),
            Pair("OVA", "ova"),
            Pair("Especial", "especial"),
        )

        val GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Fantasía", "fantasia"),
            Pair("Misterio", "misterio"),
            Pair("Recuentos de la Vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Antropomórfico", "antropomorfico"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Carreras", "carreras"),
            Pair("Detectives", "detectives"),
            Pair("Ecchi", "ecchi"),
            Pair("Elenco Adulto", "elenco-adulto"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("Histórico", "historico"),
            Pair("Idols (Hombre)", "idols-hombre"),
            Pair("Idols (Mujer)", "idols-mujer"),
            Pair("Infantil", "infantil"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Juegos Estrategia", "juegos-estrategia"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mitología", "mitologia"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Psicológico", "psicologico"),
            Pair("Samurai", "samurai"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Vampiros", "vampiros"),
        )

        val STATE = arrayOf(
            Pair("Todos", ""),
            Pair("En Emisión", "emision"),
            Pair("Finalizado", "finalizado"),
            Pair("Próximamente", "proximamente"),
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
