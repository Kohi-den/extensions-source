package eu.kanade.tachiyomi.animeextension.es.sololatino

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object SoloLatinoFilters {

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {

        fun toUriPart() = vals[state].second
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.asUriPart(): String {
        return getFirst<R>().let {
            (it as UriPartFilter).toUriPart()
        }
    }

    class InvertedResultsFilter : AnimeFilter.CheckBox("Invertir resultados", false)
    class TypeFilter : UriPartFilter("Tipo", AnimesOnlineNinjaData.TYPES)

    class GenreFilter : UriPartFilter("Generos", AnimesOnlineNinjaData.GENRES)
    class PlatformFilter : UriPartFilter("Plataformas", AnimesOnlineNinjaData.PLATFORMS)
    class YearFilter : UriPartFilter("Año", AnimesOnlineNinjaData.YEARS)

    class OtherOptionsGroup : AnimeFilter.Group<UriPartFilter>(
        "Otros filtros",
        listOf(
            GenreFilter(),
            PlatformFilter(),
            YearFilter(),
        ),
    )

    private inline fun <reified R> AnimeFilter.Group<UriPartFilter>.getItemUri(): String {
        return state.first { it is R }.toUriPart()
    }

    val FILTER_LIST get() = AnimeFilterList(
        InvertedResultsFilter(),
        TypeFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Estos filtros no afectan a la busqueda por texto"),
        OtherOptionsGroup(),
    )

    data class FilterSearchParams(
        val isInverted: Boolean = false,
        val type: String = "",
        val genre: String = "",
        val platform: String = "",
        val year: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val others = filters.getFirst<OtherOptionsGroup>()

        return FilterSearchParams(
            filters.getFirst<InvertedResultsFilter>().state,
            filters.asUriPart<TypeFilter>(),
            others.getItemUri<GenreFilter>(),
            others.getItemUri<PlatformFilter>(),
            others.getItemUri<YearFilter>(),
        )
    }

    private object AnimesOnlineNinjaData {
        val EVERY = Pair("Seleccionar", "")

        val TYPES = arrayOf(
            Pair("Todos", "todos"),
            Pair("Series", "serie"),
            Pair("Peliculas", "pelicula"),
            Pair("Animes", "anime"),
            Pair("Toons", "toon"),
        )

        val GENRES = arrayOf(
            EVERY,
            Pair("accion", "accion"),
            Pair("action-adventure", "action-adventure"),
            Pair("animacion", "animacion"),
            Pair("aventura", "aventura"),
            Pair("bajalogratis", "bajalogratis"),
            Pair("belica", "belica"),
            Pair("ciencia-ficcion", "ciencia-ficcion"),
            Pair("comedia", "comedia"),
            Pair("crimen", "crimen"),
            Pair("disney", "disney"),
            Pair("documental", "documental"),
            Pair("don-torrent", "don-torrent"),
            Pair("drama", "drama"),
            Pair("familia", "familia"),
            Pair("fantasia", "fantasia"),
            Pair("gran-torrent", "gran-torrent"),
            Pair("hbo", "hbo"),
            Pair("historia", "historia"),
            Pair("kids", "kids"),
            Pair("misterio", "misterio"),
            Pair("musica", "musica"),
            Pair("romance", "romance"),
            Pair("sci-fi-fantasy", "sci-fi-fantasy"),
            Pair("series-de-amazon-prime-video", "series-de-amazon-prime-video"),
            Pair("soap", "soap"),
            Pair("suspense", "suspense"),
            Pair("talk", "talk"),
            Pair("terror", "terror"),
            Pair("war-politics", "war-politics"),
            Pair("western", "western"),
        )

        val PLATFORMS = arrayOf(
            EVERY,
            Pair("Amazon-Prime-Video", "amazon"),
            Pair("Netflix", "netflix"),
            Pair("Apple-TV", "apple"),
            Pair("Disney-Plus", "disney"),
            Pair("HBO", "hbo"),
            Pair("Hulu", "hulu"),
            Pair("HBO-Max", "hbo-max"),

        )

        val YEARS = arrayOf(EVERY) + (2024 downTo 1979).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()
    }
}
