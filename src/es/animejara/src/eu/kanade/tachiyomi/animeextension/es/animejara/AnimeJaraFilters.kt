package eu.kanade.tachiyomi.animeextension.es.animejara

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeJaraFilters {
    fun getFilterList(): AnimeFilterList =
        AnimeFilterList(
            AnimeFilter.Header("Los filtros se ignoran con búsqueda por texto"),
            TypeFilter(),
            StatusFilter(),
            LanguageFilter(),
            YearFilter(),
            GenreFilter(),
        )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
            displayName,
            vals.map { it.first }.toTypedArray(),
        ) {
        fun toUriPart(): String? {
            val value = vals[state].second
            return value.ifBlank { null }
        }
    }

    class TypeFilter :
        UriPartFilter(
            "Tipo",
            arrayOf(
                Pair("Todos", ""),
                Pair("Serie", "serie"),
                Pair("Película", "pelicula"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Estado",
            arrayOf(
                Pair("Todos", ""),
                Pair("En Emisión", "Emision"),
                Pair("Finalizado", "Finalizado"),
            ),
        )

    class LanguageFilter :
        UriPartFilter(
            "Idioma",
            arrayOf(
                Pair("Todos", ""),
                Pair("Latino", "latino"),
                Pair("Japonés", "japones"),
                Pair("Castellano", "castellano"),
            ),
        )

    class YearFilter :
        UriPartFilter(
            "Año",
            arrayOf(
                Pair("Todos", ""),
                Pair("2026", "2026"),
                Pair("2025", "2025"),
                Pair("2024", "2024"),
                Pair("2023", "2023"),
                Pair("2022", "2022"),
                Pair("2021", "2021"),
                Pair("2020", "2020"),
                Pair("2019", "2019"),
                Pair("2018", "2018"),
                Pair("2017", "2017"),
                Pair("2016", "2016"),
                Pair("2015", "2015"),
                Pair("2014", "2014"),
                Pair("2013", "2013"),
                Pair("2012", "2012"),
                Pair("2011", "2011"),
                Pair("2010", "2010"),
                Pair("2009", "2009"),
                Pair("2008", "2008"),
                Pair("2007", "2007"),
                Pair("2006", "2006"),
                Pair("2005", "2005"),
                Pair("2004", "2004"),
                Pair("2003", "2003"),
                Pair("2002", "2002"),
                Pair("2001", "2001"),
                Pair("2000", "2000"),
                Pair("1999", "1999"),
                Pair("1998", "1998"),
                Pair("1996", "1996"),
                Pair("1993", "1993"),
                Pair("1989", "1989"),
                Pair("1988", "1988"),
                Pair("1986", "1986"),
                Pair("1983", "1983"),
            ),
        )

    class GenreFilter :
        UriPartFilter(
            "Género",
            arrayOf(
                Pair("Todos", ""),
                Pair("Acción", "Accion"),
                Pair("Amor", "Amor"),
                Pair("Artes Marciales", "Artes marciales"),
                Pair("Aventura", "Aventura"),
                Pair("Carreras", "Carreras"),
                Pair("Ciencia Ficción", "Ciencia ficcion"),
                Pair("Comedia", "Comedia"),
                Pair("Comidas", "Comidas"),
                Pair("Demonios", "Demonios"),
                Pair("Deportes", "Deportes"),
                Pair("Drama", "Drama"),
                Pair("Ecchi", "Ecchi"),
                Pair("Escolar", "Escolar"),
                Pair("Espacial", "Espacial"),
                Pair("Espadachín", "Espadachin"),
                Pair("Familia", "Familia"),
                Pair("Fantasía", "Fantasia"),
                Pair("Gore", "Gore"),
                Pair("Harem", "Harem"),
                Pair("Histórico", "Historico"),
                Pair("Isekai", "Isekai"),
                Pair("Josei", "Josei"),
                Pair("Juegos", "Juegos"),
                Pair("Magia", "Magia"),
                Pair("Mecha", "Mecha"),
                Pair("Militar", "Militar"),
                Pair("Misterio", "Misterio"),
                Pair("Música", "Musica"),
                Pair("Parodia", "Parodia"),
                Pair("Psicológico", "Psicologico"),
                Pair("Recuerdos", "Recuerdos"),
                Pair("Robots", "Robots"),
                Pair("Romance", "Romance"),
                Pair("Samurái", "Samurai"),
                Pair("Seinen", "Seinen"),
                Pair("Shoujo", "Shoujo"),
                Pair("Shounen", "Shounen"),
                Pair("Sobrenatural", "Sobrenatural"),
                Pair("Studio Ghibli", "Studio ghibli"),
                Pair("Superpoderes", "Superpoderes"),
                Pair("Suspenso", "Suspenso"),
                Pair("Terror", "Terror"),
                Pair("Vampiros", "Vampiros"),
                Pair("Yaoi", "Yaoi"),
                Pair("Yuri", "Yuri"),
                Pair("Zombies", "Zombies"),
            ),
        )
}
