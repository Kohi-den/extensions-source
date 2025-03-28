package eu.kanade.tachiyomi.animeextension.all.newgrounds

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tryParse
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

private const val SEARCH_PAGE_SIZE = 20
private val MOVIE_ID_PATTERN = Pattern.compile("""data-movie-id=\\"(\d+)\\"""", Pattern.MULTILINE)

class NewGrounds : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val lang = "all"
    override val baseUrl = "https://www.newgrounds.com"
    override val name = "Newgrounds"
    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val videoListHeaders by lazy {
        headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "https://www.newgrounds.com")
            .build()
    }

    // Latest

    private val latestSection = preferences.getString("LATEST", PREF_SECTIONS["Latest"])!!

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * SEARCH_PAGE_SIZE
        return GET("$baseUrl/$latestSection?offset=$offset", headers)
    }

    override fun latestUpdatesNextPageSelector(): String = "#load-more-items a"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        checkAdultContentFiltered(response.headers)
        return super.latestUpdatesParse(response)
    }

    override fun latestUpdatesSelector(): String = animeSelector(latestSection)

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return animeFromElement(element, latestSection)
    }

    // Browse

    private val popularSection = preferences.getString("POPULAR", PREF_SECTIONS["Popular"])!!

    override fun popularAnimeRequest(page: Int): Request {
        val offset = (page - 1) * SEARCH_PAGE_SIZE
        return GET("$baseUrl/$popularSection?offset=$offset", headers)
    }

    override fun popularAnimeNextPageSelector(): String = "#load-more-items a"

    override fun popularAnimeParse(response: Response): AnimesPage {
        checkAdultContentFiltered(response.headers)
        return super.popularAnimeParse(response)
    }

    override fun popularAnimeSelector(): String = animeSelector(popularSection)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return animeFromElement(element, popularSection)
    }

    // Search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchUrl = "$baseUrl/search/conduct/movies".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotEmpty()) searchUrl.addQueryParameter("terms", query)

        filters.findInstance<MatchAgainstFilter>().ifFilterSet {
            searchUrl.addQueryParameter("match", MATCH_AGAINST.values.elementAt(it.state))
        }
        filters.findInstance<TuningFilterGroup>()?.state
            ?.findInstance<TuningExactFilter>().ifFilterSet {
                searchUrl.addQueryParameter("exact", "1")
            }
        filters.findInstance<TuningFilterGroup>()?.state
            ?.findInstance<TuningAnyFilter>().ifFilterSet {
                searchUrl.addQueryParameter("any", "1")
            }
        filters.findInstance<AuthorFilter>().ifFilterSet {
            searchUrl.addQueryParameter("user", it.state)
        }
        filters.findInstance<GenreFilter>().ifFilterSet {
            searchUrl.addQueryParameter("genre", GENRE.values.elementAt(it.state))
        }
        filters.findInstance<LengthFilterGroup>()?.state
            ?.findInstance<MinLengthFilter>().ifFilterSet {
                searchUrl.addQueryParameter("min_length", it.state)
            }
        filters.findInstance<LengthFilterGroup>()?.state
            ?.findInstance<MaxLengthFilter>().ifFilterSet {
                searchUrl.addQueryParameter("max_length", it.state)
            }
        filters.findInstance<FrontpagedFilter>().ifFilterSet {
            searchUrl.addQueryParameter("frontpaged", "1")
        }
        filters.findInstance<DateFilterGroup>()?.state
            ?.findInstance<AfterDateFilter>().ifFilterSet {
                searchUrl.addQueryParameter("after", it.state)
            }
        filters.findInstance<DateFilterGroup>()?.state
            ?.findInstance<BeforeDateFilter>().ifFilterSet {
                searchUrl.addQueryParameter("before", it.state)
            }
//        filters.findInstance<RatingFilter>().ifFilterSet {
//            searchUrl.addQueryParameter("", "")
//        }
        filters.findInstance<SortingFilter>().ifFilterSet {
            searchUrl.addQueryParameter("sort", SORTING.values.elementAt(it.state))
        }
        filters.findInstance<TagsFilter>().ifFilterSet {
            searchUrl.addQueryParameter("tags", it.state)
        }

        return GET(searchUrl.build(), headers)
    }

    override fun searchAnimeNextPageSelector(): String = "#results-load-more"

    override fun searchAnimeParse(response: Response): AnimesPage {
        checkAdultContentFiltered(response.headers)
        return super.searchAnimeParse(response)
    }

    override fun searchAnimeSelector(): String = "ul.itemlist li:not(#results-load-more) a"

    override fun searchAnimeFromElement(element: Element): SAnime = animeFromListElement(element)

    // Etc.

    override fun animeDetailsParse(document: Document): SAnime {
        fun getStarRating(): String {
            val score: Double = document.selectFirst("#score_number")?.text()?.toDouble() ?: 0.0
            val fullStars = score.toInt()
            val hasHalfStar = (score % 1) >= 0.5
            val totalStars = if (hasHalfStar) fullStars + 1 else fullStars
            val emptyStars = 5 - totalStars

            return "✪".repeat(fullStars) + (if (hasHalfStar) "✪" else "") + "⬤".repeat(emptyStars) + " ($score)"
        }

        fun getAdultRating(): String {
            val rating = document.selectFirst("#embed_header h2")!!.className().substringAfter("rated-")
            return when (rating) {
                "e" -> "🟩 Everyone"
                "t" -> "🟦 Ages 13+"
                "m" -> "🟪 Ages 17+"
                "a" -> "🟥 Adults Only"
                else -> "❓"
            }
        }

        fun getStats(): String {
            val statsElement = document.selectFirst("#sidestats > dl:first-of-type")
            val views = statsElement?.selectFirst("dd:first-of-type")?.text() ?: "?"
            val faves = statsElement?.selectFirst("dd:nth-of-type(2)")?.text() ?: "?"
            val votes = statsElement?.selectFirst("dd:nth-of-type(3)")?.text() ?: "?"

            return "👀 $views | ❤️ $faves | 👍 $votes"
        }

        return SAnime.create().apply {
            title = document.selectFirst("h2[itemprop=\"name\"]")!!.text()
            description = """
                ${document.selectFirst("meta[itemprop=\"description\"]")?.attr("content")}

                ${getAdultRating()} | ${getStarRating()} | ${getStats()}
            """.trimIndent()
            author = document.selectFirst(".authorlinks > div:first-of-type .item-details-main")?.text()
            artist = document.select(".authorlinks > div:not(:first-of-type) .item-details-main").joinToString {
                it.text()
            }
            thumbnail_url = document.selectFirst("meta[itemprop=\"thumbnailUrl\"]")?.absUrl("content")
            genre = document.select(".tags li a").joinToString { it.text() }
            status = SAnime.COMPLETED
            setUrlWithoutDomain(document.selectFirst("meta[itemprop=\"url\"]")!!.absUrl("content"))
            update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException("Not Used")

    private fun extractEpisodeIdFromScript(element: Element?): String? {
        val scriptContent = element!!.html().toString()
        val matcher = MOVIE_ID_PATTERN.matcher(scriptContent)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeIdScript = document.selectFirst("#ng-global-video-player script")
        val episodeId = extractEpisodeIdFromScript(episodeIdScript)
        val dateString = document.selectFirst("#sidestats  > dl:nth-of-type(2) > dd:first-of-type")?.text()

        return listOf(
            SEpisode.create().apply {
                episode_number = 1f
                date_upload = dateFormat.tryParse(dateString)
                name = document.selectFirst("meta[name=\"title\"]")!!.attr("content")
                setUrlWithoutDomain("$baseUrl/portal/video/$episodeId")
            },
        )
    }

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", videoListHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val responseBody = response.body.string()
        val json = JSONObject(responseBody)
        val sources = json.getJSONObject("sources")

        val videos = mutableListOf<Video>()

        for (quality in sources.keys()) {
            val qualityArray = sources.getJSONArray(quality)
            for (i in 0 until qualityArray.length()) {
                val videoObject = qualityArray.getJSONObject(i)
                val videoUrl = videoObject.getString("src")

                videos.add(
                    Video(
                        url = videoUrl,
                        quality = quality,
                        videoUrl = videoUrl,
                        headers = headers,
                    ),
                )
            }
        }

        return videos
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not Used")

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not Used")

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not Used")

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        MatchAgainstFilter(),
        TuningFilterGroup(),
        AuthorFilter(),
        GenreFilter(),
        LengthFilterGroup(),
        FrontpagedFilter(),
        DateFilterGroup(),
        TagsFilter(),
//        RatingFilter(),
        SortingFilter(),
    )

    // ============================ Preferences =============================
    /*
        According to the labels on the website:
        Featured    -> /movies/featured
        Latest      -> /movies/browse
        Popular     -> /movies/popular
        Your Feed   -> /social/feeds/show/favorite-artists-movies
        Under Judgement -> /movies/browse?interval=all&artist-type=unjudged
     */

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "POPULAR"
            title = "Popular section content"
            entries = PREF_SECTIONS.keys.toTypedArray()
            entryValues = PREF_SECTIONS.values.toTypedArray()
            setDefaultValue(PREF_SECTIONS["Popular"])
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                Toast.makeText(screen.context, "Restart app to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "LATEST"
            title = "Latest section content"
            entries = PREF_SECTIONS.keys.toTypedArray()
            entryValues = PREF_SECTIONS.values.toTypedArray()
            setDefaultValue(PREF_SECTIONS["Latest"])
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                Toast.makeText(screen.context, "Restart app to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)
    }

    // ========================== Helper Functions ==========================

    /**
     * Chooses an extraction technique for anime information, based on section selected in Preferences
     */
    private fun animeFromElement(element: Element, section: String): SAnime {
        return if (section == PREF_SECTIONS["Your Feed"]) {
            animeFromFeedElement(element)
        } else {
            animeFromGridElement(element)
        }
    }

    /**
     * Extracts anime information from element of list typical for /popular, /browse or /featured
     */
    private fun animeFromGridElement(element: Element): SAnime = SAnime.create().apply {
        title = element.selectFirst(".card-title h4")!!.text()
        author = element.selectFirst(".card-title span")?.text()?.replace("By ", "")
        description = element.selectFirst("a")?.attr("title")
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
    }

    /**
     * Extracts anime information from element of list returned in Your Feed
     */
    private fun animeFromFeedElement(element: Element): SAnime = SAnime.create().apply {
        title = element.selectFirst(".detail-title h4")!!.text()
        author = element.selectFirst(".detail-title strong")?.text()
        description = element.selectFirst(".detail-description")?.text()
        thumbnail_url = element.selectFirst(".item-icon img")?.absUrl("src")
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
    }

    /**
     * Extracts anime information from element of list typical for /search or /collection
     */
    private fun animeFromListElement(element: Element): SAnime = SAnime.create().apply {
        title = element.selectFirst(".detail-title > h4")!!.text()
        author = element.selectFirst(".detail-title > span > strong")?.text()
        description = element.selectFirst(".detail-description")?.text()
        thumbnail_url = element.selectFirst(".item-icon img")?.absUrl("src")
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
    }

    /**
     * Returns CSS selector for anime, based on the section selected in Preferences
     */
    private fun animeSelector(section: String): String {
        return if (section == PREF_SECTIONS["Your Feed"]) {
            "a.item-portalsubmission"
        } else {
            "a.inline-card-portalsubmission"
        }
    }

    /**
     * Checks if cookie with username is present in response headers.
     * If cookie is missing: displays a toast with information
     */
    private fun checkAdultContentFiltered(headers: Headers) {
        val usernameCookie = headers.values("Set-Cookie").any { it.startsWith("NG_GG_username=") }

        if (!usernameCookie) {
            handler.post {
                context.let {
                    Toast.makeText(it, "Adult content was filtered. Log in via WebView.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    private inline fun <T> T?.ifFilterSet(action: (T) -> Unit) where T : AnimeFilter<*> {
        val state = this?.state
        if (this != null && state != null && state != "" && state != 0 && state != false) {
            action(this)
        }
    }

    companion object {
        private val PREF_SECTIONS = mapOf(
            "Featured" to "movies/featured",
            "Latest" to "movies/browse",
            "Popular" to "movies/popular",
            "Your Feed" to "social/feeds/show/favorite-artists-movies",
//            "Under Judgment" to "movies/browse?interval=all&artist-type=unjudged",
        )
    }
}
