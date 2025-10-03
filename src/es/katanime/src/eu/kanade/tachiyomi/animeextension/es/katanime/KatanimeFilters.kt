package eu.kanade.tachiyomi.animeextension.es.katanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object KatanimeFilters {
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
            filters.asQueryPart<TypesFilter>("categoria") +
                filters.asQueryPart<LanguageFilter>("idioma") +
                filters.parseCheckbox<GenresFilter>(KatanimeFiltersData.GENRES, "genero") +
                filters.asQueryPart<YearFilter>("fecha"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        TypesFilter(),
        LanguageFilter(),
        GenresFilter(),
        YearFilter(),
    )

    class TypesFilter : QueryPartFilter("Categoria", KatanimeFiltersData.TYPES)

    class LanguageFilter : QueryPartFilter("Idioma", KatanimeFiltersData.LANGUAGE)

    class GenresFilter : CheckBoxFilterList("Género", KatanimeFiltersData.GENRES.map { CheckBoxVal(it.first, false) })

    class YearFilter : QueryPartFilter("Año", KatanimeFiltersData.YEARS)

    private object KatanimeFiltersData {
        val TYPES = arrayOf(
            Pair("Todos", ""),
            Pair("Anime", "anime"),
            Pair("Ova", "ova"),
            Pair("Película", "pelicula"),
            Pair("Especial", "especial"),
            Pair("Ona", "ona"),
            Pair("Musical", "musical"),
        )

        val LANGUAGE = arrayOf(
            Pair("Todos", ""),
            Pair("Japones subtitulado", "Japones subtitulado"),
            Pair("Audio latino", "Audio latino"),
        )

        val GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Coches", "coches"),
            Pair("Comedia", "comedia"),
            Pair("Avant Garde", "avant-garde"),
            Pair("Demonios", "demonios"),
            Pair("Misterio", "misterio"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasía", "fantasia"),
            Pair("Juego", "juego"),
            Pair("Hentai", "hentai"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Infantil", "Infantil"),
            Pair("Magia", "magia"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Mecha", "mecha"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Samurái", "samurai"),
            Pair("Romance", "romance"),
            Pair("Escolar", "escolar"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Shoujo", "shoujo"),
            Pair("Yuri", "yuri"),
            Pair("Shônen", "shonen"),
            Pair("Yaoi", "yaoi"),
            Pair("Espacial", "espacial"),
            Pair("Deportes", "deportes"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Vampiros", "vampiros"),
            Pair("Criuoi", "criuoi"),
            Pair("Yurii", "yurii"),
            Pair("Harem", "harem"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Militar", "militar"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Suspenso", "suspenso"),
            Pair("Seinen", "seinen"),
            Pair("Josei", "josei"),
            Pair("Gore", "gore"),
        )

        val YEARS = arrayOf(Pair("Todos", "")) + (1982..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()
    }
}
