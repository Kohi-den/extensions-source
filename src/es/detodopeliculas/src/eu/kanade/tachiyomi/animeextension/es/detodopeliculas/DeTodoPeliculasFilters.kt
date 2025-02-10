package eu.kanade.tachiyomi.animeextension.es.detodopeliculas

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object DeTodoPeliculasFilters {

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

    class GenreFilter : UriPartFilter("Generos", AnimesOnlineNinjaData.GENRES)
    class YearFilter : UriPartFilter("Año", AnimesOnlineNinjaData.YEARS)

    val FILTER_LIST get() = AnimeFilterList(
        GenreFilter(),
        YearFilter(),
    )

    data class FilterSearchParams(
        val isInverted: Boolean = false,
        val genre: String = "",
        val year: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            genre = filters.asUriPart<GenreFilter>(),
            year = filters.asUriPart<YearFilter>(),

        )
    }

    private object AnimesOnlineNinjaData {
        val EVERY = Pair("Seleccionar", "")

        val GENRES = arrayOf(
            EVERY,
            Pair("Acción", "accion"),
            Pair("Animación", "animacion"),
            Pair("Aventura", "aventura"),
            Pair("Bélica", "belica"),
            Pair("Ciencia ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Crimen", "crimen"),
            Pair("Documental", "documental"),
            Pair("Drama", "drama"),
            Pair("Familia", "familia"),
        )

        val YEARS = arrayOf(EVERY) + (2024 downTo 1979).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()
    }
}
