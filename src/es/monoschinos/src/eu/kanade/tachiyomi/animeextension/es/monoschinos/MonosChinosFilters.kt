package eu.kanade.tachiyomi.animeextension.es.monoschinos

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object MonosChinosFilters {
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
            filters.parseCheckbox<GenresFilter>(DoramasytFiltersData.GENRES, "genero") +
                filters.parseCheckbox<YearsFilter>(DoramasytFiltersData.YEARS, "anio") +
                filters.parseCheckbox<TypesFilter>(DoramasytFiltersData.TYPES, "tipo") +
                filters.asQueryPart<StatusFilter>("estado") +
                filters.asQueryPart<SortFilter>("orden"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenresFilter(),
        YearsFilter(),
        TypesFilter(),
        StatusFilter(),
        SortFilter(),
    )

    class GenresFilter : CheckBoxFilterList("Género", DoramasytFiltersData.GENRES.map { CheckBoxVal(it.first, false) })

    class YearsFilter : CheckBoxFilterList("Año", DoramasytFiltersData.YEARS.map { CheckBoxVal(it.first, false) })

    class TypesFilter : CheckBoxFilterList("Tipo", DoramasytFiltersData.TYPES.map { CheckBoxVal(it.first, false) })

    class StatusFilter : QueryPartFilter("Estado", DoramasytFiltersData.STATUS)

    class SortFilter : QueryPartFilter("Orden", DoramasytFiltersData.SORT)

    private object DoramasytFiltersData {
        val TYPES = arrayOf(
            Pair("Anime", "anime"),
            Pair("Audio Japonés", "audio-japones"),
            Pair("Corto", "corto"),
            Pair("Donghua", "donghua"),
            Pair("Especial", "especial"),
            Pair("Ona", "ona"),
            Pair("Ova", "ova"),
            Pair("Película", "pelicula"),
            Pair("Película 1080p", "pelicula-1080p"),
            Pair("TV", "tv"),
            Pair("Sin Censura", "sin-censura"),
        )

        val STATUS = arrayOf(
            Pair("Todos", ""),
            Pair("En emisión", "en-emision"),
            Pair("Finalizado", "finalizado"),
        )

        val YEARS = (1968..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Aenime", "aenime"),
            Pair("Anime Latino", "anime-latino"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventura", "aventura"),
            Pair("Aventuras", "aventuras"),
            Pair("Blu-ray", "blu-ray"),
            Pair("Carreras", "carreras"),
            Pair("Castellano", "castellano"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Demencia", "demencia"),
            Pair("Dementia", "dementia"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Gore", "gore"),
            Pair("Harem", "harem"),
            Pair("Historia paralela", "historia-paralela"),
            Pair("Historico", "historico"),
            Pair("Horror", "horror"),
            Pair("Infantil", "infantil"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Latino", "latino"),
            Pair("Lucha", "lucha"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Monogatari", "monogatari"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Parodias", "parodias"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Recuerdos de la vida", "recuerdos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shojo", "shojo"),
            Pair("Shonen", "shonen"),
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

        val SORT = arrayOf(
            Pair("Descendente", "desc"),
            Pair("Ascendente", "asc"),
        )
    }
}
