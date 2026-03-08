package eu.kanade.tachiyomi.animeextension.es.evangelionec

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object EvangelionECFilters {
    fun getFilterList(): AnimeFilterList =
        AnimeFilterList(
            AnimeFilter.Header("Los filtros se ignoran con búsqueda por texto"),
            GenreFilter(),
            StatusFilter(),
            TypeFilter(),
            LanguageFilter(),
        )

    class GenreFilter :
        SelectFilter(
            "Género",
            arrayOf(
                Pair("Todos", ""),
                Pair("Acción", "Acción"),
                Pair("Aventura", "Aventura"),
                Pair("Comedia", "Comedia"),
                Pair("Deportes", "Deportes"),
                Pair("Drama", "Drama"),
                Pair("Ecchi", "Ecchi"),
                Pair("Fantasía", "Fantasía"),
                Pair("Gore", "Gore"),
                Pair("Harem", "Harem"),
                Pair("Horror", "Horror"),
                Pair("Magia", "Magia"),
                Pair("Mechas", "Mechas"),
                Pair("Misterio", "Misterio"),
                Pair("Música", "Música"),
                Pair("Psicológico", "Psicológico"),
                Pair("Recuentos de Vida", "Recuentos de Vida"),
                Pair("Romance", "Romance"),
                Pair("Sci-Fi", "Sci-Fi"),
                Pair("Seinen", "Seinen"),
                Pair("Shoujo", "Shoujo"),
                Pair("Shoujo Ai", "Shoujo Ai"),
                Pair("Shounen", "Shounen"),
                Pair("Sobrenatural", "Sobrenatural"),
                Pair("Thriller", "Thriller"),
                Pair("Vida Escolar", "Vida Escolar"),
                Pair("Yuri", "Yuri"),
            ),
        )

    class StatusFilter :
        SelectFilter(
            "Estado",
            arrayOf(
                Pair("Todos", ""),
                Pair("En Emisión", "En Emisión"),
                Pair("Finalizado", "Finalizado"),
                Pair("Pausado", "Pausado"),
                Pair("Próximamente", "Próximamente"),
            ),
        )

    class TypeFilter :
        SelectFilter(
            "Tipo",
            arrayOf(
                Pair("Todos", ""),
                Pair("TV", "TV"),
                Pair("Películas", "Películas"),
                Pair("OVA", "Ova"),
                Pair("ONA", "Ona"),
                Pair("Especial", "Especial"),
            ),
        )

    class LanguageFilter :
        SelectFilter(
            "Idioma",
            arrayOf(
                Pair("Todos", ""),
                Pair("Sub", "Sub"),
                Pair("Dub", "Dub"),
            ),
        )

    open class SelectFilter(
        displayName: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
            displayName,
            options.map { it.first }.toTypedArray(),
        ) {
        fun selected(): String? {
            val value = options[state].second
            return value.ifBlank { null }
        }
    }
}
