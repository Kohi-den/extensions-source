package eu.kanade.tachiyomi.animeextension.es.mhdflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object MhdFlixFilters {

    data class SearchParams(
        val type: String? = null,
        val genre: String? = null,
        val year: String? = null,
    )

    private open class OptionFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        fun value(): String? = options.getOrNull(state)?.second?.takeIf { it.isNotEmpty() }
    }

    private class TypeFilter : OptionFilter("Tipo", TYPE_OPTIONS)

    private class GenreFilter : OptionFilter("Género", GENRE_OPTIONS)

    private class YearFilter : OptionFilter("Año", YEAR_OPTIONS)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            AnimeFilter.Header("La búsqueda por texto ignora los filtros"),
            TypeFilter(),
            GenreFilter(),
            YearFilter(),
        )

    fun getSearchParameters(filters: AnimeFilterList): SearchParams {
        if (filters.isEmpty()) return SearchParams()

        var type: String? = null
        var genre: String? = null
        var year: String? = null

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> type = filter.value()
                is GenreFilter -> genre = filter.value()
                is YearFilter -> year = filter.value()
                else -> {}
            }
        }

        return SearchParams(type, genre, year)
    }

    private val TYPE_OPTIONS = arrayOf(
        "Todos" to "",
        "Series" to "tv",
        "Películas" to "movie",
    )

    private val GENRE_OPTIONS = arrayOf(
        "Todos" to "",
        "Acción" to "Acción",
        "Action" to "Action",
        "Adventure" to "Adventure",
        "Animación" to "Animación",
        "Animation" to "Animation",
        "Anime" to "Anime",
        "Anime-LAT" to "Anime-LAT",
        "Aventura" to "Aventura",
        "Bélica" to "Bélica",
        "Ciencia ficción" to "Ciencia ficción",
        "Comedia" to "Comedia",
        "Comedy" to "Comedy",
        "Crime" to "Crime",
        "Crimen" to "Crimen",
        "Documental" to "Documental",
        "dorama" to "dorama",
        "Drama" to "Drama",
        "Echii" to "Echii",
        "Familia" to "Familia",
        "Fantasía" to "Fantasía",
        "Fantasy" to "Fantasy",
        "Hentai" to "Hentai",
        "Historia" to "Historia",
        "Horror" to "Horror",
        "Kids" to "Kids",
        "Misterio" to "Misterio",
        "Música" to "Música",
        "Mystery" to "Mystery",
        "Película de TV" to "Película de TV",
        "Politics" to "Politics",
        "Reality" to "Reality",
        "Romance" to "Romance",
        "Sci-Fi" to "Sci-Fi",
        "Soap" to "Soap",
        "Suspense" to "Suspense",
        "Terror" to "Terror",
        "Thriller" to "Thriller",
        "War" to "War",
        "Western" to "Western",
    )

    private val YEAR_OPTIONS: Array<Pair<String, String>> = buildYearOptions()

    private fun buildYearOptions(): Array<Pair<String, String>> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        val years = (currentYear downTo 1950).map { year ->
            year.toString() to year.toString()
        }
        return arrayOf("Todos" to "") + years.toTypedArray()
    }
}
