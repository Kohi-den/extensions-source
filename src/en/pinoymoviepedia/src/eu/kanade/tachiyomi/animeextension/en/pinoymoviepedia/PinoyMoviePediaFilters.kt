package eu.kanade.tachiyomi.animeextension.en.pinoymoviepedia

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object PinoyMoviePediaFilters {

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

    class GenreFilter : UriPartFilter("Genre", AnimesOnlineNinjaData.GENRES)

    private inline fun <reified R> AnimeFilter.Group<UriPartFilter>.getItemUri(): String {
        return state.first { it is R }.toUriPart()
    }

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("These filters do not affect text searches."),
        GenreFilter(),
    )

    data class FilterSearchParams(
        val genre: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            genre = filters.asUriPart<GenreFilter>(),
        )
    }

    private object AnimesOnlineNinjaData {
        val EVERY = Pair("Select Genre", "")

        val GENRES = arrayOf(
            EVERY,
            Pair("Fantasy", "fantasy"),
            Pair("Comedy", "comedy"),
            Pair("Family", "family"),
            Pair("Action", "action"),
            Pair("Drama", "drama"),
            Pair("Romance", "romance"),
            Pair("Mystery", "mystery"),
            Pair("Music", "music"),
            Pair("Adventure", "adventure"),
            Pair("Horror", "horror"),
            Pair("Digitally Restored", "digitally-restored"),
            Pair("Science Fiction", "science-fiction"),
            Pair("Pinay Sexy Movies", "pinay-sexy-movies"),
            Pair("Crime", "crime"),
            Pair("Thriller", "thriller"),
            Pair("History", "history"),
            Pair("Documentary", "documentary"),
            Pair("Biography", "biography"),
            Pair("Animation", "animation"),
            Pair("War", "war"),
            Pair("Musical", "musical"),
            Pair("Indie", "indie"),
            Pair("LGBT", "lgbt"),
            Pair("Concert", "concert"),
            Pair("Short Movie", "short-movie"),
            Pair("2022", "2022"),
            Pair("Tagalog Dubbed", "tagalog-dubbed"),
        )
    }
}
