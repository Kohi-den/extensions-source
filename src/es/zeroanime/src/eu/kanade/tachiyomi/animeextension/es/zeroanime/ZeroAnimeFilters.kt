package eu.kanade.tachiyomi.animeextension.es.zeroanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.CheckBox
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object ZeroAnimeFilters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=${vals[state].second}" } ?: run { "" }
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : CheckBox(name, state)

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

    private fun Array<Pair<String, String>>.toCheckBoxVal(): List<CheckBox> = map { CheckBoxVal(it.first, false) }

    private fun String.addSuffix(): String = takeIf { it.isNotBlank() } ?: this

    internal fun getSearchParameters(filters: AnimeFilterList, origen: ZeroAnimeFiltersData.ORIGEN): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val strFilter = buildString {
            when (origen) {
                ZeroAnimeFiltersData.ORIGEN.ANIME -> {
                    append(filters.parseCheckbox<AnimeGenresFilter>(ZeroAnimeFiltersData.ANIME_GENRES, "genero"))
                    append(filters.asQueryPart<YearsFilter>("years").addSuffix())
                    append(filters.asQueryPart<StatesFilter>("estado"))
                }
            }
        }

        return FilterSearchParams(strFilter)
    }

    private val ANIME_FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        AnimeGenresFilter(),
        YearsFilter(),
        StatesFilter(),
    )

    fun getFilterList(origen: ZeroAnimeFiltersData.ORIGEN) = when (origen) {
        ZeroAnimeFiltersData.ORIGEN.ANIME -> ANIME_FILTER_LIST
    }

    class AnimeGenresFilter : CheckBoxFilterList("Género", ZeroAnimeFiltersData.ANIME_GENRES.toCheckBoxVal())

    class YearsFilter : QueryPartFilter("Año", ZeroAnimeFiltersData.YEARS)

    class StatesFilter : QueryPartFilter("Estado", ZeroAnimeFiltersData.STATES)

    object ZeroAnimeFiltersData {
        val YEARS = arrayOf(Pair("Todos", "ALL")) + (1950..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()
        val ANIME_GENRES = arrayOf(
            Pair("Todos los generos", "ALL"),
            Pair("Acción", "5"),
            Pair("Artes marciales", "4"),
            Pair("Aventuras", "24"),
            Pair("Carreras", "6"),
            Pair("Ciencia ficción", "27"),
            Pair("Comedia", "7"),
            Pair("Demencia", "9"),
            Pair("Demonios", "41"),
            Pair("Deportes", "10"),
            Pair("Drama", "11"),
            Pair("Ecchi", "12"),
            Pair("Escolar", "13"),
            Pair("Espacial", "14"),
            Pair("Fantasía", "8"),
            Pair("Gore", "37"),
            Pair("Harem", "15"),
            Pair("Historico", "16"),
            Pair("Horror", "30"),
            Pair("Infantil", "17"),
            Pair("Isekai", "29"),
            Pair("Josei", "18"),
            Pair("Juegos", "19"),
            Pair("Magia", "20"),
            Pair("Mecha", "21"),
            Pair("Militar", "32"),
            Pair("Música", "38"),
            Pair("Parodia", "33"),
            Pair("Psicológico", "39"),
            Pair("Recuerdos de la vida", "42"),
            Pair("Romance", "1"),
            Pair("Samurai", "36"),
            Pair("Sci-Fi", "28"),
            Pair("Seinen", "3"),
            Pair("Shoujo", "34"),
            Pair("Shounen", "2"),
            Pair("Sobre Natural", "25"),
            Pair("SuperPoderes", "26"),
            Pair("Suspenso", "23"),
            Pair("Terror", "22"),
            Pair("Vampiros", "40"),
            Pair("Yaoi", "43"),
            Pair("Yuri", "35"),
            Pair("Zombies", "31"),
        )

        val STATES = arrayOf(
            Pair("Todos", "2"),
            Pair("En emisión", "1"),
            Pair("Finalizado", "0"),
        )

        enum class ORIGEN {
            ANIME,
        }
    }
}
