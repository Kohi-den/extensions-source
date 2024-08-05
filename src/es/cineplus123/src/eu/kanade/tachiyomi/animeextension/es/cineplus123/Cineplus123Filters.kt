package eu.kanade.tachiyomi.animeextension.es.cineplus123

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Cineplus123Filters {

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
    class LanguageFilter : UriPartFilter("Idiomas", AnimesOnlineNinjaData.LANGUAGES)
    class YearFilter : UriPartFilter("Año", AnimesOnlineNinjaData.YEARS)
    class MovieFilter : UriPartFilter("Peliculas", AnimesOnlineNinjaData.MOVIES)

    class OtherOptionsGroup : AnimeFilter.Group<UriPartFilter>(
        "Otros filtros",
        listOf(
            GenreFilter(),
            LanguageFilter(),
            YearFilter(),
            MovieFilter(),
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
        val language: String = "",
        val year: String = "",
        val movie: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val others = filters.getFirst<OtherOptionsGroup>()

        return FilterSearchParams(
            filters.getFirst<InvertedResultsFilter>().state,
            filters.asUriPart<TypeFilter>(),
            others.getItemUri<GenreFilter>(),
            others.getItemUri<LanguageFilter>(),
            others.getItemUri<YearFilter>(),
            others.getItemUri<MovieFilter>(),
        )
    }

    private object AnimesOnlineNinjaData {
        val EVERY = Pair("Seleccionar", "")

        val TYPES = arrayOf(
            Pair("Todos", "todos"),
            Pair("Series", "serie"),
            Pair("Peliculas", "pelicula"),
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

        val LANGUAGES = arrayOf(
            EVERY,
            Pair("latino", "latino"),
            Pair("castellano", "castellano"),
            Pair("subtitulado", "subtitulado"),
        )

        val YEARS = arrayOf(EVERY) + (2024 downTo 1979).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val MOVIES = arrayOf(
            EVERY,
            Pair("pelicula", "pelicula"),
            Pair("series", "series de tv"),
            Pair("pelicula-de-tv", "pelicula-de-tv"),
            Pair("peliculas-cristianas", "peliculas-cristianas"),
            Pair("peliculas-de-halloween", "peliculas-de-halloween"),
            Pair("peliculas-de-navidad", "peliculas-de-navidad"),
            Pair("peliculas-para-el-dia-de-la-madre", "peliculas-para-el-dia-de-la-madre"),
            Pair("pelis-play", "pelis-play"),
            Pair("pelishouse", "pelishouse"),
            Pair("pelismart-tv", "pelismart-tv"),
            Pair("pelisnow", "pelisnow"),
            Pair("pelix-tv", "pelix-tv"),
            Pair("poseidonhd", "poseidonhd"),
            Pair("proximamente", "proximamente"),
            Pair("reality", "reality"),
            Pair("repelis-go", "repelis-go"),
            Pair("repelishd-tv", "repelishd-tv"),
            Pair("repelisplus", "repelisplus"),
        )
    }
}
