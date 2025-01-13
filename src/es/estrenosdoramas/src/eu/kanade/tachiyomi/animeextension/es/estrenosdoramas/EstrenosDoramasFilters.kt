package eu.kanade.tachiyomi.animeextension.es.estrenosdoramas

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object EstrenosDoramasFilters {
    open class QueryPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = vals[state].second.takeIf { it.isNotEmpty() }?.let { "&$name=${vals[state].second}" } ?: run { "" }
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }.joinToString("&$name[]=").let {
                if (it.isBlank()) {
                    ""
                } else {
                    "&$name[]=$it"
                }
            }
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private fun String.changePrefix() = this.takeIf { it.startsWith("&") }?.let { this.replaceFirst("&", "?") } ?: run { this }

    data class FilterSearchParams(val filter: String = "") { fun getQuery() = filter.changePrefix() }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(EstrenosDoramasFiltersData.GENRES, "genre") +
                filters.parseCheckbox<SeasonsFilter>(EstrenosDoramasFiltersData.SEASONS, "season") +
                filters.parseCheckbox<StudiosFilter>(EstrenosDoramasFiltersData.STUDIOS, "studio") +
                filters.parseCheckbox<CountriesFilter>(EstrenosDoramasFiltersData.COUNTRIES, "country") +
                filters.parseCheckbox<NetworksFilter>(EstrenosDoramasFiltersData.NETWORKS, "network") +
                filters.asQueryPart<StatusFilter>("status") +
                filters.asQueryPart<TypesFilter>("type") +
                filters.asQueryPart<SortFilter>("order"),
        )
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenresFilter(),
        SeasonsFilter(),
        StudiosFilter(),
        CountriesFilter(),
        NetworksFilter(),
        StatusFilter(),
        TypesFilter(),
        SortFilter(),
    )

    class GenresFilter : CheckBoxFilterList("Géneros", EstrenosDoramasFiltersData.GENRES.map { CheckBoxVal(it.first, false) })
    class SeasonsFilter : CheckBoxFilterList("Temporadas", EstrenosDoramasFiltersData.SEASONS.map { CheckBoxVal(it.first, false) })
    class StudiosFilter : CheckBoxFilterList("Estudio", EstrenosDoramasFiltersData.STUDIOS.map { CheckBoxVal(it.first, false) })
    class CountriesFilter : CheckBoxFilterList("País", EstrenosDoramasFiltersData.COUNTRIES.map { CheckBoxVal(it.first, false) })
    class NetworksFilter : CheckBoxFilterList("Networks", EstrenosDoramasFiltersData.NETWORKS.map { CheckBoxVal(it.first, false) })
    class StatusFilter : QueryPartFilter("Estatus", EstrenosDoramasFiltersData.STATUS)
    class TypesFilter : QueryPartFilter("Tipo", EstrenosDoramasFiltersData.TYPES)
    class SortFilter : QueryPartFilter("Orden", EstrenosDoramasFiltersData.SORT)

    private object EstrenosDoramasFiltersData {
        val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Adult Cast", "adult-cast"),
            Pair("Adventure", "adventure"),
            Pair("Business", "business"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Documentary", "documentary"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Food", "food"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Law", "law"),
            Pair("Life", "life"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mature", "mature"),
            Pair("Medical", "medical"),
            Pair("Melodrama", "melodrama"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Mythology", "mythology"),
            Pair("Political", "political"),
            Pair("Psychological", "psychological"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Sitcom", "sitcom"),
            Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
            Pair("War", "war"),
            Pair("Workplace", "workplace"),
            Pair("Wuxia", "wuxia"),
            Pair("Youth", "youth"),
        )

        val SEASONS = arrayOf(
            Pair("Winter 2024", "winter-2024"),
        )

        val STUDIOS = arrayOf(
            Pair("Connect", "connect"),
            Pair("Drive", "drive"),
            Pair("HORNETS", "hornets"),
            Pair("Okuruto Noboru", "okuruto-noboru"),
            Pair("Seven Arcs", "seven-arcs"),
            Pair("Shin-Ei Animation", "shin-ei-animation"),
            Pair("SILVER LINK.", "silver-link"),
            Pair("Studio Add", "studio-add"),
            Pair("Studio Flad", "studio-flad"),
            Pair("Studio Kai", "studio-kai"),
            Pair("Studio PuYUKAI", "studio-puyukai"),
            Pair("SynergySP", "synergysp"),
            Pair("Trigger", "trigger"),
        )

        val COUNTRIES = arrayOf(
            Pair("China", "china"),
            Pair("Japan", "japan"),
            Pair("South Korea", "south-korea"),
            Pair("Taiwan", "taiwan"),
            Pair("Thailand", "thailand"),
        )

        val NETWORKS = arrayOf(
            Pair("Amazon Prime", "amazon-prime"),
            Pair("BS Asahi", "bs-asahi"),
            Pair("CCTV", "cctv"),
            Pair("Channel 3", "channel-3"),
            Pair("Channel 9", "channel-9"),
            Pair("Channel A", "channel-a"),
            Pair("COUPANG TV", "coupang-tv"),
            Pair("Disney+", "disney"),
            Pair("ENA", "ena"),
            Pair("Fuji TV", "fuji-tv"),
            Pair("Genie TV", "genie-tv"),
            Pair("GMM 25", "gmm-25"),
            Pair("GMM One", "gmm-one"),
            Pair("GTV", "gtv"),
            Pair("Hulu", "hulu"),
            Pair("Hunan TV", "hunan-tv"),
            Pair("iQiyi", "iqiyi"),
            Pair("JSTV", "jstv"),
            Pair("jTBC", "jtbc"),
            Pair("KBS2", "kbs2"),
            Pair("Mango TV", "mango-tv"),
            Pair("MBC", "mbc"),
            Pair("MBN", "mbn"),
            Pair("MBS", "mbs"),
            Pair("Mnet", "mnet"),
            Pair("Naver TV Cast", "naver-tv-cast"),
            Pair("Netflix", "netflix"),
            Pair("One 31", "one-31"),
            Pair("oneD", "oned"),
            Pair("SBS", "sbs"),
            Pair("SBS Plus", "sbs-plus"),
            Pair("SET TV", "set-tv"),
            Pair("Sohu TV", "sohu-tv"),
            Pair("TBS", "tbs"),
            Pair("Telasa", "telasa"),
            Pair("Tencent Video", "tencent-video"),
            Pair("Tokyo MX", "tokyo-mx"),
            Pair("TV Chosun", "tv-chosun"),
            Pair("TV Tokyo", "tv-tokyo"),
            Pair("TVING", "tving"),
            Pair("TVK", "tvk"),
            Pair("tvN", "tvn"),
            Pair("Viki", "viki"),
            Pair("ViuTV", "viutv"),
            Pair("vLive", "vlive"),
            Pair("Wavve", "wavve"),
            Pair("WeTV", "wetv"),
            Pair("Workpoint TV", "workpoint-tv"),
            Pair("Youku", "youku"),
            Pair("ZJTV", "zjtv"),
        )

        val STATUS = arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "Ongoing"),
            Pair("Completed", "Completed"),
        )

        val TYPES = arrayOf(
            Pair("All", ""),
            Pair("Dorama", "Drama"),
            Pair("TV Show", "TV Show"),
            Pair("Anime", "Anime"),
            Pair("Película", "Movie"),
            Pair("Special", "Special"),
        )

        val SORT = arrayOf(
            Pair("Latest Update", "update"),
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular"),
            Pair("Rating", "rating"),
        )
    }
}
