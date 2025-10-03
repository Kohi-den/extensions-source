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
            filters.asQueryPart<StateFilter>("estado") +
                filters.asQueryPart<TypesFilter>("tipo") +
                filters.asQueryPart<GenresFilter>("genero") +
                filters.asQueryPart<YearsFilter>("estreno") +
                filters.asQueryPart<LangFilter>("idioma"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        StateFilter(),
        TypesFilter(),
        GenresFilter(),
        YearsFilter(),
        LangFilter(),
    )

    class StateFilter : QueryPartFilter("Estado", AnimeFenixFiltersData.STATE)
    class TypesFilter : QueryPartFilter("Tipo", AnimeFenixFiltersData.TYPES)
    class GenresFilter : QueryPartFilter("Género", AnimeFenixFiltersData.GENRES)
    class YearsFilter : QueryPartFilter("Año", AnimeFenixFiltersData.YEARS)
    class LangFilter : QueryPartFilter("Idioma", AnimeFenixFiltersData.LANGUAGE)

    private object AnimeFenixFiltersData {
        val STATE = arrayOf(
            Pair("Todos", ""),
            Pair("Emisión", "2"),
            Pair("Finalizado", "1"),
            Pair("Próximamente", "3"),
        )

        val TYPES = arrayOf(
            Pair("Todos", ""),
            Pair("TV", "1"),
            Pair("Película", "2"),
            Pair("OVA", "3"),
            Pair("Especial", "4"),
            Pair("Serie", "9"),
            Pair("Dorama", "11"),
            Pair("Corto", "14"),
            Pair("Donghua", "15"),
            Pair("ONA", "16"),
            Pair("Live Action", "17"),
            Pair("Manhwa", "18"),
            Pair("Teatral", "19"),
        )

        val GENRES = arrayOf(
            Pair("Todos", ""),
            Pair("Acción", "1"),
            Pair("Escolares", "2"),
            Pair("Romance", "3"),
            Pair("Shoujo", "4"),
            Pair("Comedia", "5"),
            Pair("Drama", "6"),
            Pair("Seinen", "7"),
            Pair("Deportes", "8"),
            Pair("Shounen", "9"),
            Pair("Recuentos de la vida", "10"),
            Pair("Ecchi", "11"),
            Pair("Sobrenatural", "12"),
            Pair("Fantasía", "13"),
            Pair("Magia", "14"),
            Pair("Superpoderes", "15"),
            Pair("Demencia", "16"),
            Pair("Misterio", "17"),
            Pair("Psicológico", "18"),
            Pair("Suspenso", "19"),
            Pair("Ciencia Ficción", "20"),
            Pair("Mecha", "21"),
            Pair("Militar", "22"),
            Pair("Aventuras", "23"),
            Pair("Historico", "24"),
            Pair("Infantil", "25"),
            Pair("Artes Marciales", "26"),
            Pair("Terror", "27"),
            Pair("Harem", "28"),
            Pair("Josei", "29"),
            Pair("Parodia", "30"),
            Pair("Policía", "31"),
            Pair("Juegos", "32"),
            Pair("Carreras", "33"),
            Pair("Samurai", "34"),
            Pair("Espacial", "35"),
            Pair("Música", "36"),
            Pair("Yuri", "37"),
            Pair("Demonios", "38"),
            Pair("Vampiros", "39"),
            Pair("Yaoi", "40"),
            Pair("Humor Negro", "41"),
            Pair("Crimen", "42"),
            Pair("Hentai", "43"),
            Pair("Youtuber", "44"),
            Pair("MaiNess Random", "45"),
            Pair("Donghua", "46"),
            Pair("Horror", "47"),
            Pair("Sin Censura", "48"),
            Pair("Gore", "49"),
            Pair("Live Action", "50"),
            Pair("Isekai", "51"),
            Pair("Gourmet", "52"),
            Pair("spokon", "53"),
            Pair("Zombies", "54"),
            Pair("Idols", "55"),
        )

        val LANGUAGE = arrayOf(
            Pair("Todos", ""),
            Pair("Japonés", "1"),
            Pair("Latino", "2"),
        )

        val YEARS = arrayOf(Pair("Todos", "")) + (1967..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()
    }
}
