package eu.kanade.tachiyomi.animeextension.all.newgrounds

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class MatchAgainstFilter : AnimeFilter.Select<String>("Match against", MATCH_AGAINST.keys.toTypedArray(), 0)

class TuningExactFilter : AnimeFilter.CheckBox("exact matches", false)
class TuningAnyFilter : AnimeFilter.CheckBox("match any words", false)
class TuningFilterGroup : AnimeFilter.Group<AnimeFilter.CheckBox>(
    "Tuning",
    listOf(
        TuningExactFilter(),
        TuningAnyFilter(),
    ),
)

class AuthorFilter : AnimeFilter.Text("Author")

class GenreFilter : AnimeFilter.Select<String>("Genre", GENRE.keys.toTypedArray())

class MinLengthFilter : AnimeFilter.Text("Min Length")
class MaxLengthFilter : AnimeFilter.Text("Max Length")
class LengthFilterGroup : AnimeFilter.Group<AnimeFilter.Text>(
    "Length (00:00:00)",
    listOf(
        MinLengthFilter(),
        MaxLengthFilter(),
    ),
)

class FrontpagedFilter : AnimeFilter.CheckBox("Frontpaged?", false)

class AfterDateFilter : AnimeFilter.Text("On, or after")
class BeforeDateFilter : AnimeFilter.Text("Before")
class DateFilterGroup : AnimeFilter.Group<AnimeFilter.Text>(
    "Dates (YYYY-MM-DD)",
    listOf(
        AfterDateFilter(),
        BeforeDateFilter(),
    ),
)

// class RatingFilter() : AnimeFilter.Select<String>("Ratings (unused)", arrayOf("Everyone", "Ages 13+", "Ages 17+", "Adults Only"), 0)

class SortingFilter() : AnimeFilter.Select<String>("Sort by", SORTING.keys.toTypedArray())

class TagsFilter() : AnimeFilter.Text("Tags (comma separated)")

// ===================================================================
val MATCH_AGAINST = mapOf(
    "Default" to "",
    "title / description / tags / author" to "tdtu",
    "title / description / tags" to "tdt",
    "title / description" to "td",
    "title" to "t",
    "description" to "d",
)

val GENRE = mapOf(
    "All" to "",
    "Action" to "45",
    "Comedy - Original" to "60",
    "Comedy - Parody" to "61",
    "Drama" to "47",
    "Experimental" to "49",
    "Informative" to "48",
    "Music Video" to "50",
    "Other" to "51",
    "Spam" to "55",
)

val SORTING = mapOf(
    "Default" to "",
    "Relevance" to "relevance",
    "Date (Descending)" to "date-desc",
    "Date (Ascending)" to "date-asc",
    "Score (Descending)" to "score-desc",
    "Score (Ascending)" to "score-asc",
    "Views (Descending)" to "views-desc",
    "Views (Ascending)" to "views-asc",
)
