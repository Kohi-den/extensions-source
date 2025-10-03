package eu.kanade.tachiyomi.animeextension.es.flixlatam

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object FlixLatamFilters {

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

    class GenreFilter : UriPartFilter("Generos", FlixLatamFiltersData.GENRES)

    val FILTER_LIST get() = AnimeFilterList(
        GenreFilter(),
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

        )
    }

    private object FlixLatamFiltersData {
        val EVERY = Pair("Seleccionar", "")

        val GENRES = arrayOf(
            EVERY,
            Pair("Acción", "accion"),
            Pair("Action & Adventure", "action-adventure"),
            Pair("Adolescencia", "adolescencia"),
            Pair("Animación", "animacion"),
            Pair("Anime", "anime"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventura", "aventura"),
            Pair("Aventuras", "aventuras"),
            Pair("Bélica", "belica"),
            Pair("Bélico", "belico"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Comedia de Situación", "comedia-de-situacion"),
            Pair("Crimen", "crimen"),
            Pair("Deporte", "deporte"),
            Pair("Dibujo Animado", "dibujo-animado"),
            Pair("Documental", "documental"),
            Pair("Drama", "drama"),
            Pair("Drama Adolescente", "drama-adolescente"),
            Pair("Drama Médico", "drama-medico"),
            Pair("Familia", "familia"),
            Pair("Fantasía", "fantasia"),
            Pair("Ficción Histórica", "ficcion-historica"),
            Pair("Hentai", "hentai"),
            Pair("Historia", "historia"),
            Pair("Humor Negro", "humor-negro"),
            Pair("Infantil", "infantil"),
            Pair("Kids", "kids"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Película de TV", "pelicula-de-tv"),
            Pair("Política", "politica"),
            Pair("Psicológico", "psicologico"),
            Pair("Reality", "reality"),
            Pair("Romance", "romance"),
            Pair("Sátira", "satira"),
            Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
            Pair("Sexualidad y Pornografía", "sexualidad-y-pornografia"),
            Pair("Soap", "soap"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superhéroes", "superheroes"),
            Pair("Suspense", "suspense"),
            Pair("Suspenso", "suspenso"),
            Pair("Talk", "talk"),
            Pair("Telecomedia", "telecomedia"),
            Pair("Telenovela", "telenovela"),
            Pair("Terror", "terror"),
            Pair("TV Asiática", "tv-asiatica"),
            Pair("TV Latina", "tv-latina"),
            Pair("War & Politics", "war-politics"),
            Pair("Western", "western"),
        )
    }
}
