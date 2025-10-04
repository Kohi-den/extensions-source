package eu.kanade.tachiyomi.animeextension.es.lamovie

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

internal object LaMovieFilters {
    private const val ANY = ""
    internal const val DEFAULT_ORDER_BY = "latest"
    internal const val DEFAULT_ORDER = "DESC"

    private val TYPE_OPTIONS = arrayOf(
        "Todas" to ANY,
        "Películas" to "movies",
        "Series" to "tvshows",
        "Anime" to "animes",
    )

    private val ORDER_BY_OPTIONS = arrayOf(
        "Más recientes" to DEFAULT_ORDER_BY,
        "Más populares" to "popular",
        "Mejor valorados" to "rated",
        "Más vistos" to "views",
    )

    private val ORDER_DIRECTION_OPTIONS = arrayOf(
        "Descendente" to DEFAULT_ORDER,
        "Ascendente" to "ASC",
    )

    private val GENRE_OPTIONS = arrayOf(
        "Acción" to 32,
        "Action & Adventure" to 705,
        "Animación" to 520,
        "Aventura" to 130,
        "Bélica" to 3056,
        "Ciencia ficción" to 131,
        "Comedia" to 18,
        "Crimen" to 180,
        "Documental" to 164,
        "Drama" to 17,
        "Familia" to 398,
        "Fantasía" to 229,
        "Historia" to 165,
        "Kids" to 703,
        "Misterio" to 97,
        "Música" to 8,
        "Película de TV" to 6787,
        "Reality" to 12485,
        "Romance" to 115,
        "Sci-Fi & Fantasy" to 704,
        "Soap" to 19824,
        "Suspense" to 33,
        "Terror" to 96,
        "War & Politics" to 786,
        "Western" to 674,
    )

    private val COUNTRY_OPTIONS = arrayOf(
        "Alemania" to 1431,
        "Arabia Saudí" to 22691,
        "Argentina" to 7746,
        "Australia" to 2654,
        "Austria" to 25679,
        "Brasil" to 3623,
        "Bulgaria" to 19193,
        "Bélgica" to 5210,
        "Canadá" to 787,
        "Chequia" to 6619,
        "Chile" to 15438,
        "China" to 1198,
        "Colombia" to 12155,
        "Corea del Sur" to 4601,
        "Dinamarca" to 1364,
        "Emiratos Árabes Unidos" to 19636,
        "España" to 2499,
        "Estados Unidos" to 457,
        "Filipinas" to 12619,
        "Finlandia" to 18611,
        "Francia" to 617,
        "Hungría" to 10051,
        "India" to 3416,
        "Indonesia" to 17085,
        "Irlanda" to 7483,
        "Israel" to 6596,
        "Italia" to 3912,
        "Japón" to 733,
        "Luxemburgo" to 27584,
        "México" to 5436,
        "Nigeria" to 49098,
        "Noruega" to 16399,
        "Nueva Zelanda" to 3738,
        "Países Bajos" to 6033,
        "Perú" to 27475,
        "Polonia" to 3057,
        "RAE de Hong Kong (China)" to 1199,
        "Reino Unido" to 774,
        "Rusia" to 9620,
        "Singapur" to 21223,
        "SU" to 9433,
        "Sudáfrica" to 8327,
        "Suecia" to 8300,
        "Suiza" to 9334,
        "Tailandia" to 9100,
        "Taiwán" to 9008,
        "Turquía" to 11668,
        "Ucrania" to 28796,
        "Uruguay" to 17020,
        "Venezuela" to 35098,
    )

    private val PROVIDER_OPTIONS = arrayOf(
        "Amazon Prime Video" to 563,
        "Amazon Prime Video with Ads" to 580,
        "Amazon Video" to 464,
        "Apple TV" to 461,
        "Bbox VOD" to 569,
        "Blockbuster" to 478,
        "blue TV" to 472,
        "Canal VOD" to 566,
        "Catchplay" to 578,
        "Cineplex" to 469,
        "Claro video" to 549,
        "Elisa Viihde" to 480,
        "Fandango At Home" to 494,
        "Fetch TV" to 468,
        "FILMO" to 567,
        "Freenet meinVOD" to 476,
        "Google Play Movies" to 460,
        "Hulu" to 493,
        "Kinopoisk" to 524,
        "KPN" to 489,
        "MagentaTV" to 474,
        "Max" to 675,
        "maxdome Store" to 466,
        "meJane" to 522,
        "Microsoft Store" to 463,
        "Movistar Plus+ Ficción Total" to 551,
        "MovistarTV" to 465,
        "Netflix" to 572,
        "Netflix Standard with Ads" to 574,
        "Orange VOD" to 481,
        "Pathé Thuis" to 490,
        "Player" to 523,
        "Premiere Max" to 483,
        "Premiery Canal+" to 492,
        "Rakuten TV" to 462,
        "SF Anytime" to 479,
        "Sky Store" to 467,
        "Spectrum On Demand" to 581,
        "Telia Play" to 565,
        "Timvision" to 487,
        "TV 2 Play" to 491,
        "U-NEXT" to 573,
        "Universcine" to 568,
        "Viaplay" to 477,
        "Videobuster" to 677,
        "Videoload" to 475,
        "VIVA by videofutur" to 482,
        "Watcha" to 575,
        "wavve" to 488,
        "YouTube" to 470,
    )

    private val YEAR_OPTIONS = arrayOf(
        "1970" to 60885,
        "2015" to 8694,
        "1953" to 4244,
        "1980" to 4122,
        "2010" to 3858,
        "1979" to 2881,
        "2020" to 2792,
        "1991" to 2583,
        "2023" to 2236,
        "2021" to 2169,
        "1973" to 2114,
        "2009" to 2092,
        "2014" to 2052,
        "2018" to 1926,
        "2017" to 1874,
        "2019" to 1816,
        "1988" to 1726,
        "1993" to 1707,
        "2016" to 1618,
        "2022" to 1461,
        "1985" to 1440,
        "2008" to 1395,
        "1976" to 1378,
        "2024" to 1354,
        "1986" to 1313,
        "1998" to 1279,
        "1989" to 1258,
        "1984" to 1237,
        "1981" to 1212,
        "1982" to 1165,
        "1996" to 1142,
        "2005" to 963,
        "1995" to 937,
        "2007" to 902,
        "2006" to 873,
        "1987" to 852,
        "2002" to 800,
        "2001" to 793,
        "2013" to 775,
        "2011" to 769,
        "2012" to 762,
        "1999" to 735,
        "2004" to 728,
        "1990" to 707,
        "2000" to 684,
        "1992" to 657,
        "1997" to 600,
        "1994" to 533,
        "2003" to 503,
        "2025" to 4,
    )

    open class QueryPartFilter(
        displayName: String,
        private val entries: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        entries.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(): String = entries[state].second
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBoxItem>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    class CheckBoxItem(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class TypeFilter : QueryPartFilter("Tipo", TYPE_OPTIONS)
    class OrderByFilter : QueryPartFilter("Ordenar por", ORDER_BY_OPTIONS)
    class OrderDirectionFilter : QueryPartFilter("Dirección", ORDER_DIRECTION_OPTIONS)

    class GenresFilter : CheckBoxFilterList(
        "Géneros",
        GENRE_OPTIONS.map { CheckBoxItem(it.first) },
    )

    class CountriesFilter : CheckBoxFilterList(
        "Países",
        COUNTRY_OPTIONS.map { CheckBoxItem(it.first) },
    )

    class ProvidersFilter : CheckBoxFilterList(
        "Proveedores",
        PROVIDER_OPTIONS.map { CheckBoxItem(it.first) },
    )

    class YearsFilter : CheckBoxFilterList(
        "Años",
        YEAR_OPTIONS.map { CheckBoxItem(it.first) },
    )

    data class FilterSearchParams(
        val type: String = ANY,
        val orderBy: String = DEFAULT_ORDER_BY,
        val order: String = DEFAULT_ORDER,
        val genres: List<Int> = emptyList(),
        val countries: List<Int> = emptyList(),
        val providers: List<Int> = emptyList(),
        val years: List<Int> = emptyList(),
    )

    fun createFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        OrderByFilter(),
        OrderDirectionFilter(),
        AnimeFilter.Separator(),
        GenresFilter(),
        ProvidersFilter(),
        CountriesFilter(),
        YearsFilter(),
    )

    fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<OrderByFilter>().ifBlank { DEFAULT_ORDER_BY },
            filters.asQueryPart<OrderDirectionFilter>().ifBlank { DEFAULT_ORDER },
            filters.parseCheckboxIds<GenresFilter>(GENRE_OPTIONS),
            filters.parseCheckboxIds<CountriesFilter>(COUNTRY_OPTIONS),
            filters.parseCheckboxIds<ProvidersFilter>(PROVIDER_OPTIONS),
            filters.parseCheckboxIds<YearsFilter>(YEAR_OPTIONS),
        )
    }

    private inline fun <reified R> AnimeFilterList.findInstance(): R? = firstOrNull { it is R } as? R

    private inline fun <reified R : QueryPartFilter> AnimeFilterList.asQueryPart(): String {
        return findInstance<R>()?.toQueryPart() ?: ANY
    }

    private inline fun <reified R : CheckBoxFilterList> AnimeFilterList.parseCheckboxIds(
        options: Array<Pair<String, Int>>,
    ): List<Int> {
        val group = findInstance<R>() ?: return emptyList()
        if (group.state.isEmpty()) return emptyList()

        return group.state
            .asSequence()
            .filter { it.state }
            .mapNotNull { checkbox ->
                val match = options.find { it.first == checkbox.name }
                match?.second
            }
            .distinct()
            .toList()
    }
}
