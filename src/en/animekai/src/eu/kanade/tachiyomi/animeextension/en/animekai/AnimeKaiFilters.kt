package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class AnimeKaiFilters {

    companion object {
        fun getFilterList(): AnimeFilterList {
            return AnimeFilterList(
                listOf(
                    TypeGroup(),
                    GenreGroup(),
                    StatusGroup(),
                    SortSelector(),
                ),
            )
        }

        // Sort Options Definition
        // These options are used in the SortSelector filter.
        enum class SortOption(val displayName: String, val id: String) {
            UPDATED_DATE("Updated Date", "updated_desc"),
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
    }

    // Sort Selector Definition
    class SortSelector() : AnimeFilter.Select<SortOption>(
        name = "Sort By",
        values = SortOption.values(),
        state = SortOption.UPDATED_DATE.ordinal,
    )

    // Filter Type Implementations
    class IdCheckBox(val id: String, name: String) : AnimeFilter.CheckBox(name)
    class IdTriState(val id: String, name: String) : AnimeFilter.TriState(name)

    // Genre Filters Definition
    class GenreGroup : AnimeFilter.Group<IdTriState>(
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
    )

    // Status Filter Definition
    class StatusGroup : AnimeFilter.Group<IdCheckBox>(
        "Status",
        listOf(
            IdCheckBox("info", "Not Aired Yet"),
            IdCheckBox("releasing", "Releasing"),
            IdCheckBox("completed", "Completed"),
        ),
    )

    class TypeGroup : AnimeFilter.Group<IdCheckBox>(
        "Type",
        listOf(
            IdCheckBox("movie", "Movie"),
            IdCheckBox("tv", "TV"),
            IdCheckBox("ova", "OVA"),
            IdCheckBox("ona", "ONA"),
            IdCheckBox("special", "Special"),
            IdCheckBox("music", "Music"),
        ),
    )
}
