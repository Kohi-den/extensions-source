package eu.kanade.tachiyomi.animeextension.es.mhdflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object MhdFlixFilters {
    open class QueryPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second.takeIf { it.isNotEmpty() } ?: run { "" }
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private fun String.changePrefix() = this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: run { this }

    data class FilterSearchParams(val path: String = "", val queryParams: String = "") {
        fun getFullUrl() = path.changePrefix()
        fun query() = queryParams
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        // Obtiene el path de la URL para el género y el año
        val genrePath = filters.getFirst<GenresFilter>().toQueryPart()
        val generalPath = filters.getFirst<GeneralFilter>().toQueryPart()

        // Si hay año, añadimos "release" a la ruta
        val path = when {
            genrePath.isNotEmpty() -> "/category/$genrePath"
            generalPath.isNotEmpty() -> "/category/$generalPath"
            else -> "/"
        }

        // Parámetros de consulta (type=movies/series, order)
        val queryParams = filters.getFirst<TypesFilter>().toQueryPart()

        return FilterSearchParams(path, queryParams)
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro"),
        GenresFilter(),
        GeneralFilter(),
        TypesFilter(),
    )

    class GenresFilter : QueryPartFilter("Género", AnimeFlvFiltersData.GENRES)
    class GeneralFilter : QueryPartFilter("Plataformas", AnimeFlvFiltersData.GENERAL)
    class TypesFilter : QueryPartFilter("Tipo", AnimeFlvFiltersData.TYPES)

    private object AnimeFlvFiltersData {
        val TYPES = arrayOf(
            Pair("Seleccione un tipo", ""),
            Pair("Películas", "/?type=movies"),
            Pair("Series", "/?type=series"),
        )
        val GENERAL = arrayOf(
            Pair("Seleccione un tipo", ""),
            Pair("Netflix", "netflix"),
            Pair("Disney Plus", "disney-plus"),
            Pair("Amazon Prime Video", "amazon-prime-video"),
            Pair("HBO Max", "hbo-max"),
            Pair("Apple TV Plus", "apple-tv-plus"),
            Pair("Movistar Plus", "movistar-plus"),
            Pair("AMC Amazon Channel", "amc-amazon-channel"),
            Pair("MGM Amazon Channel", "mgm-amazon-channel"),
            Pair("Netflix Basic with Ads", "netflix-basic-with-ads"),
            Pair("Crunchyroll", "crunchyroll"),
            Pair("Max", "max"),
            Pair("MGM Plus Amazon Channel", "mgm-plus-amazon-channel"),

        )
        val GENRES = arrayOf(
            Pair("Seleccione un género", ""),
            Pair("Acción", "accion"),
            Pair("Comedia", "comedia"),
            Pair("Crimen", "crimen"),
            Pair("Suspense", "suspense"),
            Pair("Animación", "animacion"),
            Pair("Familia", "familia"),
            Pair("Aventura", "aventura"),
            Pair("Fantasía", "fantasia"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Drama", "drama"),
            Pair("Disney", "disney"),
            Pair("Romance", "romance"),
            Pair("Película de TV", "pelicula-de-tv"),
            Pair("Misterio", "misterio"),
            Pair("Action Adventure", "action-adventure"),
            Pair("Terror", "terror"),
            Pair("Kids", "kids"),
            Pair("Sci-Fi Fantasy", "sci-fi-fantasy"),
            Pair("Documental", "documental"),
            Pair("Marvel", "marvel"),
            Pair("DC", "dc"),
            Pair("Música", "musica"),
            Pair("Flixole", "flixole"),
            Pair("Western", "western"),
            Pair("Bélica", "belica"),
            Pair("Historia", "historia"),
            Pair("Filmin", "filmin"),
            Pair("Anime", "anime"),
            Pair("Planet Horror Amazon Channel", "planet-horror-amazon-channel"),
            Pair("Dorama", "dorama"),
            Pair("Tivify", "tivify"),
            Pair("Acontra Plus", "acontra-plus"),
            Pair("FuboTV", "fubotv"),
            Pair("Reality", "reality"),
            Pair("War Politics", "war-politics"),
            Pair("Mubi", "mubi"),
            Pair("SkyShowtime", "skyshowtime"),
            Pair("Soap", "soap"),
            Pair("Filmbox", "filmbox"),
            Pair("Kocowa", "kocowa"),
            Pair("YouTube Premium", "youtube-premium"),
            Pair("Hentai", "hentai"),
        )
    }
}
