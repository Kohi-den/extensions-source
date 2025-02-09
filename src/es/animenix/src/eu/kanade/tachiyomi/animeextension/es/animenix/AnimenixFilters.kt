package eu.kanade.tachiyomi.animeextension.es.animenix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimenixFilters {

    open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
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
    class LetterFilter : UriPartFilter("Filtrar por letra", AnimesOnlineNinjaData.LETTERS)

    class GenreFilter : UriPartFilter("Generos", AnimesOnlineNinjaData.GENRES)
    class YearFilter : UriPartFilter("Año", AnimesOnlineNinjaData.YEARS)

    class OtherOptionsGroup : AnimeFilter.Group<UriPartFilter>(
        "Otros filtros",
        listOf(
            GenreFilter(),
            YearFilter(),
        ),
    )

    private inline fun <reified R> AnimeFilter.Group<UriPartFilter>.getItemUri(): String {
        return state.first { it is R }.toUriPart()
    }

    val FILTER_LIST get() = AnimeFilterList(
        InvertedResultsFilter(),
        TypeFilter(),
        LetterFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Estos filtros no afectan a la busqueda por texto"),
        OtherOptionsGroup(),
    )

    data class FilterSearchParams(
        val isInverted: Boolean = false,
        val type: String = "",
        val letter: String = "",
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
            filters.asUriPart<LetterFilter>(),
            others.getItemUri<GenreFilter>(),
            others.getItemUri<YearFilter>(),
        )
    }

    private object AnimesOnlineNinjaData {
        val EVERY = Pair("Seleccionar", "")

        val TYPES = arrayOf(
            Pair("Seleccionar", ""),
            Pair("Anime", "anime"),
            Pair("Peliculas", "pelicula"),
        )

        val LETTERS = arrayOf(EVERY) + ('a'..'z').map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val GENRES = arrayOf(
            EVERY,
            Pair("Acción", "accion"),
            Pair("Action", "action"),
            Pair("Action-Adventure", "action-adventure"),
            Pair("Aventura", "aventura"),
            Pair("Adventure", "adventure"),
            Pair("Animación", "animacion"),
            Pair("Animation", "animation"),
            Pair("Aventura (Torrent)", "aventura-torrent"),
            Pair("Bélica", "belica"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Comedy", "comedy"),
            Pair("Crimen", "crimen"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Documental", "documental"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("En Emisión", "en-emision"),
            Pair("Escolares", "escolares"),
            Pair("Familia", "familia"),
            Pair("Fantasía", "fantasia"),
            Pair("Fantasy", "fantasy"),
            Pair("Harem", "harem"),
            Pair("Historia", "historia"),
            Pair("Histórico", "historico"),
            Pair("History", "history"),
            Pair("Horror", "horror"),
            Pair("Kids", "kids"),
            Pair("Magia", "magia"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Película de TV", "pelicula-de-tv"),
            Pair("Reality", "reality"),
            Pair("Recuerdos de la Vida", "recuerdos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi Fantasy", "sci-fi-fantasy"),
            Pair("Science Fiction", "science-fiction"),
            Pair("Seinen", "seinen"),
            Pair("Shojo", "shojo"),
            Pair("Shounen", "shounen"),
            Pair("Soap", "soap"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Suspense", "suspense"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("War & Politics", "war-politics"),
            Pair("Western", "western"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
        val YEARS = arrayOf(EVERY) + (2024 downTo 1979).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()
    }
}
