package eu.kanade.tachiyomi.animeextension.es.otakuverso

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.FormBody
import java.util.Calendar

object OtakuversoFilters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    )

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    data class FilterSearchParams(
        val body: FormBody,
    ) {
        fun isFiltered(): Boolean {
            return (0 until body.size).any { body.value(it) != "0" }
        }
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams(FormBody.Builder().build())

        val formBuilder = FormBody.Builder()
        filters.getFirst<GenresFilter>().let { filter ->
            formBuilder.add("search_genero", filter.vals[filter.state].second)
        }
        filters.getFirst<TypesFilter>().let { filter ->
            formBuilder.add("search_tipo", filter.vals[filter.state].second)
        }
        filters.getFirst<StatusFilter>().let { filter ->
            formBuilder.add("search_estado", filter.vals[filter.state].second)
        }
        filters.getFirst<YearsFilter>().let { filter ->
            formBuilder.add("search_anno", filter.vals[filter.state].second)
        }
        filters.getFirst<SortFilter>().let { filter ->
            formBuilder.add("search_orden", filter.vals[filter.state].second)
        }
        return FilterSearchParams(formBuilder.build())
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenresFilter(),
        TypesFilter(),
        StatusFilter(),
        YearsFilter(),
        SortFilter(),
    )

    class GenresFilter : QueryPartFilter("Género", OtakuversoFiltersData.GENRES)

    class TypesFilter : QueryPartFilter("Tipo", OtakuversoFiltersData.TYPES)

    class StatusFilter : QueryPartFilter("Estado", OtakuversoFiltersData.STATUS)

    class YearsFilter : QueryPartFilter("Año", OtakuversoFiltersData.YEARS)

    class SortFilter : QueryPartFilter("Orden", OtakuversoFiltersData.SORT)

    private object OtakuversoFiltersData {
        val GENRES = arrayOf(
            Pair("Todos", "0"),
            Pair("Aventura", "jR"),
            Pair("Misterio", "k5"),
            Pair("Shounen", "l5"),
            Pair("Acción", "mO"),
            Pair("Fantasía", "nR"),
            Pair("Demonios", "oj"),
            Pair("Histórico", "p2"),
            Pair("Sobrenatural", "q2"),
            Pair("Artes Marciales", "rE"),
            Pair("Comedia", "vm"),
            Pair("Superpoderes", "wR"),
            Pair("Magia", "x9"),
            Pair("Deportes", "y7"),
            Pair("Drama", "zY"),
            Pair("Escolares", "AO"),
            Pair("Ciencia Ficción", "BX"),
            Pair("Horror", "Dx"),
            Pair("Psicológico", "Ev"),
            Pair("Juegos", "G7"),
            Pair("Romance", "J2"),
            Pair("Seinen", "KR"),
            Pair("Recuentos de la vida", "Lw"),
            Pair("Mecha", "MA"),
            Pair("Shoujo", "N6"),
            Pair("Policía", "Op"),
            Pair("Suspenso", "Pw"),
            Pair("Música", "Ql"),
            Pair("Parodia", "Rq"),
            Pair("Ecchi", "VM"),
            Pair("Terror", "WJ"),
            Pair("Militar", "XW"),
            Pair("Vampiros", "YK"),
            Pair("Samurai", "ZJ"),
            Pair("Infantil", "1R"),
            Pair("Harem", "2K"),
            Pair("Escuela", "3M"),
            Pair("Carreras", "41"),
            Pair("Lucha", "5B"),
            Pair("Gore", "6n"),
            Pair("Latino", "7j"),
            Pair("Fútbol", "8m"),
            Pair("Espacial", "9x"),
            Pair("Josei", "0v"),
            Pair("Comida", "gJY"),
            Pair("School", "jRR"),
            Pair("Yuri", "kR5"),
            Pair("Yaoi", "lY5"),
            Pair("Shounen Ai", "mZO"),
        )

        val TYPES = arrayOf(
            Pair("Todos", "0"),
            Pair("Serie", "1"),
            Pair("Película", "2"),
            Pair("Especial", "3"),
            Pair("OVA", "4"),
        )

        val STATUS = arrayOf(
            Pair("Todos", "0"),
            Pair("Emitiendose", "1"),
            Pair("Próximo", "2"),
            Pair("Finalizado", "3"),
        )

        val YEARS = arrayOf(Pair("Todos", "0")) + (1980..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val SORT = arrayOf(
            Pair("Default", "0"),
            Pair("Ascendente", "1"),
            Pair("Descendente", "2"),
        )
    }
}
