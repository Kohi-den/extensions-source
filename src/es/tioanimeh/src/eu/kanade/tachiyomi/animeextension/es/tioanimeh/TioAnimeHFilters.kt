package eu.kanade.tachiyomi.animeextension.es.tioanimeh

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.CheckBox
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object TioAnimeHFilters {
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

    private fun Array<Pair<String, String>>.toCheckBoxVal(): List<CheckBox> = map { CheckBoxVal(it.first, false) }

    private fun String.addSuffix(): String = takeIf { it.isNotBlank() }?.let { "$it,${Calendar.getInstance().get(Calendar.YEAR)}" } ?: this

    internal fun getSearchParameters(filters: AnimeFilterList, origen: TioAnimeHFiltersData.ORIGEN): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val strFilter = buildString {
            when (origen) {
                TioAnimeHFiltersData.ORIGEN.ANIME -> {
                    append(filters.parseCheckbox<TypesFilter>(TioAnimeHFiltersData.TYPES, "type"))
                    append(filters.parseCheckbox<AnimeGenresFilter>(TioAnimeHFiltersData.ANIME_GENRES, "genero"))
                    append(filters.asQueryPart<YearsFilter>("year").addSuffix())
                    append(filters.asQueryPart<StatesFilter>("status"))
                    append(filters.asQueryPart<SortFilter>("sort"))
                }
                else -> {
                    append(filters.asQueryPart<HentaiGenresFilter>("genero"))
                }
            }
        }

        return FilterSearchParams(strFilter)
    }

    private val ANIME_FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        TypesFilter(),
        AnimeGenresFilter(),
        YearsFilter(),
        StatesFilter(),
        SortFilter(),
    )

    private val HENTAI_FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        HentaiGenresFilter(),
    )

    fun getFilterList(origen: TioAnimeHFiltersData.ORIGEN) = when (origen) {
        TioAnimeHFiltersData.ORIGEN.ANIME -> ANIME_FILTER_LIST
        else -> HENTAI_FILTER_LIST
    }

    class TypesFilter : CheckBoxFilterList("Tipo", TioAnimeHFiltersData.TYPES.toCheckBoxVal())

    class AnimeGenresFilter : CheckBoxFilterList("Género", TioAnimeHFiltersData.ANIME_GENRES.toCheckBoxVal())

    class HentaiGenresFilter : QueryPartFilter("Género", TioAnimeHFiltersData.HENTAI_GENRES)

    class YearsFilter : QueryPartFilter("Año", TioAnimeHFiltersData.YEARS)

    class StatesFilter : QueryPartFilter("Estado", TioAnimeHFiltersData.STATES)

    class SortFilter : QueryPartFilter("Orden", TioAnimeHFiltersData.SORT)

    object TioAnimeHFiltersData {
        val YEARS = arrayOf(Pair("Todos", "")) + (1950..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()

        val TYPES = arrayOf(
            Pair("TV", "0"),
            Pair("Película", "1"),
            Pair("OVA", "2"),
            Pair("Especial", "3"),
        )

        val ANIME_GENRES = arrayOf(
            Pair("Acción", "accion"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventuras", "aventura"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Demencia", "demencia"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Harem", "harem"),
            Pair("Historico", "historico"),
            Pair("Infantil", "infantil"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
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

        val HENTAI_GENRES = arrayOf(
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Bestialidad", "bestialidad"),
            Pair("Bondage", "bondage"),
            Pair("Chikan", "chikan"),
            Pair("Comedia", "comedia"),
            Pair("Enfermeras", "enfermeras"),
            Pair("Escolar", "escolar"),
            Pair("Fantasia", "fantasia"),
            Pair("Futanari", "futanari"),
            Pair("Gangbang", "gangbang"),
            Pair("Harem", "harem"),
            Pair("Incesto", "incesto"),
            Pair("Vamp", "vamp"),
            Pair("Maids", "maids"),
            Pair("Milf", "milf"),
            Pair("Netorare", "netorare"),
            Pair("Masturbacion", "masturbacion"),
            Pair("Romance", "romance"),
            Pair("Shota", "shota"),
            Pair("Tentaculos", "tentaculos"),
            Pair("Tetonas", "tetonas"),
            Pair("Violacion", "violacion"),
            Pair("Virgenes", "virgenes"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Demonios", "demonios"),
            Pair("Felacion", "felacion"),
        )

        val STATES = arrayOf(
            Pair("Finalizado", "2"),
            Pair("En emisión", "1"),
            Pair("Próximamente", "3"),
        )

        val SORT = arrayOf(
            Pair("Mas Reciente", "recent"),
            Pair("Menos Reciente", "-recent"),
        )

        enum class ORIGEN {
            ANIME,
            HENTAI,
        }
    }
}
