package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AnimeKaiFilters {
    fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            listOf(
                TypeGroup(),
                GenreGroup(),
                StatusGroup(),
                SortSelector(),
                SeasonGroup(),
                LanguageGroup(),
                CountryGroup(),
                RatingGroup(),
                YearGroup(),
            ),
        )
    }

    enum class SortOption(val displayName: String, val id: String) {
        AUTO("Auto", "auto"),
        MOST_RELEVANCE("Most Relevance", "most_relevance"),
        UPDATED_DATE("Updated Date", "updated_date"),
        RELEASE_DATE("Release Date", "release_date"),
        END_DATE("End Date", "end_date"),
        TRENDING("Trending", "trending"),
        TITLE_AZ("Name A-Z", "title_az"),
        AVG_SCORE("Average Score", "avg_score"),
        MAL_SCORE("MAL Score", "mal_score"),
        TOTAL_VIEWS("Total Views", "total_views"),
        TOTAL_BOOKMARKS("Total Bookmarks", "total_bookmarks"),
        TOTAL_EPISODES("Total Episodes", "total_episodes"),
        ;

        override fun toString(): String = displayName
    }

    class SortSelector :
        AnimeFilter.Select<SortOption>(
            name = "Sort By",
            values = SortOption.values(),
            state = SortOption.AUTO.ordinal, // Default to an auto option, which is handled in AnimeKai.kt
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            val selected = values[state]
            return listOf("sort=${selected.id}")
        }
    }

    class IdCheckBox(val id: String, name: String = id) : AnimeFilter.CheckBox(name)
    class IdTriState(val id: String, name: String = id) : AnimeFilter.TriState(name)

    // IDs pulled directly from the website's HTML source manually.
    // If the IDs change, the filters will need to be updated.
    class GenreGroup :
        AnimeFilter.Group<IdTriState>(
            "Genres",
            listOf(
                IdTriState("47", "Action"),
                IdTriState("1", "Adventure"),
                IdTriState("235", "Avant Garde"),
                IdTriState("184", "Boys Love"),
                IdTriState("7", "Comedy"),
                IdTriState("127", "Demons"),
                IdTriState("66", "Drama"),
                IdTriState("8", "Ecchi"),
                IdTriState("34", "Fantasy"),
                IdTriState("926", "Girls Love"),
                IdTriState("436", "Gourmet"),
                IdTriState("196", "Harem"),
                IdTriState("421", "Horror"),
                IdTriState("77", "Isekai"),
                IdTriState("225", "Iyashikei"),
                IdTriState("555", "Josei"),
                IdTriState("35", "Kids"),
                IdTriState("78", "Magic"),
                IdTriState("857", "Mahou Shoujo"),
                IdTriState("92", "Martial Arts"),
                IdTriState("219", "Mecha"),
                IdTriState("134", "Military"),
                IdTriState("27", "Music"),
                IdTriState("48", "Mystery"),
                IdTriState("356", "Parody"),
                IdTriState("240", "Psychological"),
                IdTriState("798", "Reverse Harem"),
                IdTriState("145", "Romance"),
                IdTriState("9", "School"),
                IdTriState("36", "Sci-Fi"),
                IdTriState("189", "Seinen"),
                IdTriState("183", "Shoujo"),
                IdTriState("37", "Shounen"),
                IdTriState("125", "Slice of Life"),
                IdTriState("220", "Space"),
                IdTriState("10", "Sports"),
                IdTriState("350", "Super Power"),
                IdTriState("49", "Supernatural"),
                IdTriState("322", "Suspense"),
                IdTriState("241", "Thriller"),
                IdTriState("126", "Vampire"),
            ),
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            val included = state.filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }.map { "genre[]=${it.id}" }
            val excluded = state.filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }.map { "genre[]=-${it.id}" }
            return included + excluded
        }
    }

    class StatusGroup :
        AnimeFilter.Group<IdCheckBox>(
            "Status",
            listOf(
                IdCheckBox("info", "Not Aired Yet"),
                IdCheckBox("releasing", "Releasing"),
                IdCheckBox("completed", "Completed"),
            ),
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            return state.mapNotNull { filter ->
                if (filter.state) "status[]=${filter.id}" else null
            }
        }
    }

    class TypeGroup :
        AnimeFilter.Group<IdCheckBox>(
            "Type",
            listOf(
                IdCheckBox("movie", "Movie"),
                IdCheckBox("tv", "TV"),
                IdCheckBox("ova", "OVA"),
                IdCheckBox("ona", "ONA"),
                IdCheckBox("special", "Special"),
                IdCheckBox("music", "Music"),
            ),
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            return state.mapNotNull { filter ->
                if (filter.state) "type[]=${filter.id}" else null
            }
        }
    }

    class SeasonGroup :
        AnimeFilter.Group<IdCheckBox>(
            "Season",
            listOf(
                IdCheckBox("fall", "Fall"),
                IdCheckBox("summer", "Summer"),
                IdCheckBox("spring", "Spring"),
                IdCheckBox("winter", "Winter"),
                IdCheckBox("unknown", "Unknown"),
            ),
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            return state.mapNotNull { filter ->
                if (filter.state) "season[]=${filter.id}" else null
            }
        }
    }

    class LanguageGroup :
        AnimeFilter.Group<IdCheckBox>(
            "Language",
            listOf(
                IdCheckBox("sub", "Hard Sub"),
                IdCheckBox("softsub", "Soft Sub"),
                IdCheckBox("dub", "Dub"),
                IdCheckBox("subdub", "Sub & Dub"),
            ),
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            return state.mapNotNull { filter ->
                if (filter.state) "language[]=${filter.id}" else null
            }
        }
    }

    class CountryGroup :
        AnimeFilter.Group<IdCheckBox>(
            "Country",
            listOf(
                IdCheckBox("11", "Japan"),
                IdCheckBox("2", "China"),
            ),
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            return state.mapNotNull { filter ->
                if (filter.state) "country[]=${filter.id}" else null
            }
        }
    }

    class RatingGroup :
        AnimeFilter.Group<IdCheckBox>(
            "Rating",
            listOf(
                IdCheckBox("g", "G - All Ages"),
                IdCheckBox("pg", "PG - Children"),
                IdCheckBox("pg_13", "PG 13 - Teens 13 or older"),
                IdCheckBox("r", "R-17+, Violence & Profanity"),
                IdCheckBox("r%2B", "R+ - Profanity & Mild Nudity"),
                IdCheckBox("rx", "Rx - Hentai"),
            ),
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            return state.mapNotNull { filter ->
                if (filter.state) "rating[]=${filter.id}" else null
            }
        }
    }

    class YearGroup :
        AnimeFilter.Group<IdCheckBox>(
            "Year",
            (Calendar.getInstance().get(Calendar.YEAR) downTo 2000).map { IdCheckBox(it.toString()) } +
                listOf(
                    IdCheckBox("1990s"),
                    IdCheckBox("1980s"),
                    IdCheckBox("1970s"),
                    IdCheckBox("1960s"),
                    IdCheckBox("1950s"),
                    IdCheckBox("1940s"),
                    IdCheckBox("1930s"),
                    IdCheckBox("1920s"),
                    IdCheckBox("1910s"),
                    IdCheckBox("1900s"),
                ),
        ),
        KaiFilter {
        override fun getParams(): List<String> {
            return state.mapNotNull { filter ->
                if (filter.state) "year[]=${filter.id}" else null
            }
        }
    }

    interface KaiFilter {
        fun getParams(): List<String>
    }
}
