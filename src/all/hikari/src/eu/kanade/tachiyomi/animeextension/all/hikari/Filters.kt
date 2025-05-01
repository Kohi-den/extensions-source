package eu.kanade.tachiyomi.animeextension.all.hikari

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import okhttp3.HttpUrl
import java.util.Calendar

interface UriFilter {
    fun addToUri(builder: HttpUrl.Builder)
}

sealed class UriPartFilter(
    name: String,
    private val param: String,
    private val vals: Array<Pair<String, String>>,
    defaultValue: String? = null,
) : AnimeFilter.Select<String>(
    name,
    vals.map { it.first }.toTypedArray(),
    vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val value = vals[state].second
        if (value.isNotEmpty()) {
            builder.addQueryParameter(param, value)
        }
    }
}

class UriMultiSelectOption(name: String, val value: String) : AnimeFilter.CheckBox(name)

sealed class UriMultiSelectFilter(
    name: String,
    private val param: String,
    private val vals: Array<Pair<String, String>>,
) : AnimeFilter.Group<UriMultiSelectOption>(name, vals.map { UriMultiSelectOption(it.first, it.second) }), UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val checked = state.filter { it.state }
        if (checked.isNotEmpty()) {
            builder.addQueryParameter(param, checked.joinToString(",") { it.value })
        }
    }
}

class TypeFilter : UriPartFilter(
    "Type",
    "ani_type",
    arrayOf(
        Pair("All", ""),
        Pair("TV", "1"),
        Pair("Movie", "2"),
        Pair("OVA", "3"),
        Pair("ONA", "4"),
        Pair("Special", "5"),
    ),
)

class StatusFilter : UriPartFilter(
    "Status",
    "ani_stats",
    arrayOf(
        Pair("All", ""),
        Pair("Ongoing", "1"),
        Pair("Completed", "2"),
        Pair("Upcoming", "3"),
    ),
)

class SeasonFilter : UriPartFilter(
    "Season",
    "ani_release_season",
    arrayOf(
        Pair("All", ""),
        Pair("Winter", "1"),
        Pair("Spring", "2"),
        Pair("Summer", "3"),
        Pair("Fall", "4"),
    ),
)

class YearFilter : UriPartFilter(
    "Release Year",
    "ani_release",
    YEARS,
) {
    companion object {
        private val CURRENT_YEAR by lazy {
            Calendar.getInstance()[Calendar.YEAR]
        }

        private val YEARS = buildList {
            add(Pair("Any", ""))
            addAll(
                (1990..CURRENT_YEAR).map {
                    Pair(it.toString(), it.toString())
                },
            )
        }.toTypedArray()
    }
}

class GenreFilter : UriMultiSelectFilter(
    "Genre",
    "ani_genre",
    arrayOf(
        Pair("Action", "Action"),
        Pair("Adventure", "Adventure"),
        Pair("Cars", "Cars"),
        Pair("Comedy", "Comedy"),
        Pair("Dementia", "Dementia"),
        Pair("Demons", "Demons"),
        Pair("Drama", "Drama"),
        Pair("Ecchi", "Ecchi"),
        Pair("Fantasy", "Fantasy"),
        Pair("Game", "Game"),
        Pair("Harem", "Harem"),
        Pair("Historical", "Historical"),
        Pair("Horror", "Horror"),
        Pair("Isekai", "Isekai"),
        Pair("Josei", "Josei"),
        Pair("Kids", "Kids"),
        Pair("Magic", "Magic"),
        Pair("Martial Arts", "Martial Arts"),
        Pair("Mecha", "Mecha"),
        Pair("Military", "Military"),
        Pair("Music", "Music"),
        Pair("Mystery", "Mystery"),
        Pair("Parody", "Parody"),
        Pair("Policy", "Policy"),
        Pair("Psychological", "Psychological"),
        Pair("Romance", "Romance"),
        Pair("Samurai", "Samurai"),
        Pair("School", "School"),
        Pair("Sci-Fi", "Sci-Fi"),
        Pair("Seinen", "Seinen"),
        Pair("Shoujo", "Shoujo"),
        Pair("Shoujo Ai", "Shoujo Ai"),
        Pair("Shounen", "Shounen"),
        Pair("Shounen Ai", "Shounen Ai"),
        Pair("Slice of Life", "Slice of Life"),
        Pair("Space", "Space"),
        Pair("Sports", "Sports"),
        Pair("Super Power", "Super Power"),
        Pair("Supernatural", "Supernatural"),
        Pair("Thriller", "Thriller"),
        Pair("Vampire", "Vampire"),
    ),
)

class LanguageFilter : UriPartFilter(
    "Language",
    "ani_genre",
    arrayOf(
        Pair("Any", ""),
        Pair("Portuguese", "Portuguese"),
    ),
)
