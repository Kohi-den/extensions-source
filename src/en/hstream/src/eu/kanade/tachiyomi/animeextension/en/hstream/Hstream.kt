package eu.kanade.tachiyomi.animeextension.en.hstream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.extractEpisodeNumber
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.normalizeHref
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.stripEpisodeSuffix
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.toSeriesSlug
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.toSeriesUrl
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

class Hstream :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Hstream"

    override val baseUrl = "https://hstream.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // URLs from the old extension are invalid now, so we're bumping this to
    // make aniyomi interpret it as a new source, forcing old users to migrate.
    override val versionId = 3

    private val preferences by getPreferencesLazy()

    /**
     * Centralized debug logging toggle to avoid noisy logs and overhead in production.
     *
     * Wrap all non-essential logs with this helper instead of calling HstreamLogger directly.
     */
    private inline fun logDebug(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) {
            HstreamLogger.debug(tag, message())
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        logDebug("popularAnimeRequest") { "Building request: page=$page" }
        return GET("$baseUrl/search?order=view-count&page=$page")
    }

    override fun popularAnimeSelector() = "div.items-center div.w-full > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val rawHref = element.attr("href")
        val episodeUrl = rawHref.normalizeHref()
        if (preferences.getBoolean(PREF_GROUP_BY_SERIES_KEY, PREF_GROUP_BY_SERIES_DEFAULT)) {
            setUrlWithoutDomain(episodeUrl.toSeriesUrl())
            title = element.selectFirst("img")!!.attr("alt").stripEpisodeSuffix()
            thumbnail_url = "$baseUrl/images$url/cover-ep-1.webp"
        } else {
            setUrlWithoutDomain(episodeUrl)
            title = element.selectFirst("img")!!.attr("alt")
            val episode = url.substringAfterLast("-").substringBefore("/")
            thumbnail_url = "$baseUrl/images${url.substringBeforeLast("-")}/cover-ep-$episode.webp"
        }
        logDebug("popularAnimeFromElement") { "rawHref='$rawHref', url='$url', title='$title'" }
    }

    override fun popularAnimeNextPageSelector() = "span[aria-current] + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        logDebug("latestUpdatesRequest") { "Building request: page=$page" }
        return GET("$baseUrl/search?order=recently-uploaded&page=$page")
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun getFilterList() = HstreamFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
        logDebug("getSearchAnime") { "query='$query', page=$page, startsWithPrefix=true" }
        val id = query.removePrefix(PREFIX_SEARCH)
        val url = if (preferences.getBoolean(PREF_GROUP_BY_SERIES_KEY, PREF_GROUP_BY_SERIES_DEFAULT)) {
            "$baseUrl/hentai/${id.toSeriesSlug()}"
        } else {
            "$baseUrl/hentai/$id"
        }
        client.newCall(GET(url))
            .awaitSuccess()
            .use(::searchAnimeByIdParse)
    } else {
        super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        logDebug("searchAnimeByIdParse") { "Parsing detail page: ${response.request.url}" }
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = HstreamFilters.getSearchParameters(filters)
        logDebug("searchAnimeRequest") { "Building request: query='$query', page=$page, filters=${filters.size}" }

        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("search", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("order", params.order)
            params.genres.forEachIndexed { index, genre -> addQueryParameter("tags[$index]", genre) }
            params.blacklisted.forEach { addQueryParameter("blacklist[]", it) }
            params.studios.forEach { addQueryParameter("studios[]", it) }
        }.build()

        logDebug("searchAnimeRequest") { "Search URL: $url" }
        return GET(url.toString())
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        logDebug("animeDetailsParse") { "Parsing details from: ${document.location()}" }
        status = SAnime.COMPLETED

        val detailsSection = document.selectFirst("div.relative > div.justify-between > div")
        if (detailsSection != null) {
            // Episode page: h1 is inside div.justify-between > div
            logDebug("animeDetailsParse") { "Page type: episode" }
            title = detailsSection.selectFirst("div > h1")!!.text()
            artist = detailsSection.select("div > a:nth-of-type(3)").text()
        } else {
            // Series page: h1 is a direct child of div.relative
            logDebug("animeDetailsParse") { "Page type: series" }
            title = document.selectFirst("div.relative > h1")?.text()
                ?: document.selectFirst("h1")!!.text()
            artist = ""
        }

        thumbnail_url = document.selectFirst("div.float-left > img.object-cover")?.absUrl("src")
        genre = document.select("ul.list-none > li > a").eachText().joinToString()

        description = document.selectFirst("div.relative > p.leading-tight")?.text()
        val genres = genre?.split(", ")?.size ?: 0
        logDebug("animeDetailsParse") { "Parsed: title='$title', genres=$genres, description length=${description?.length ?: 0}" }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        logDebug("getEpisodeList") { "anime.url='${anime.url}'" }

        if (preferences.getBoolean(PREF_GROUP_BY_SERIES_KEY, PREF_GROUP_BY_SERIES_DEFAULT)) {
            logDebug("getEpisodeList") { "Path: GROUPED" }

            // Fetch the series page to extract the series title
            val doc = client.newCall(animeDetailsRequest(anime)).awaitSuccess().use { it.asJsoup() }
            // Series page is JS-rendered, so episode grid isn't available in JSoup HTML.
            // Instead, use the search endpoint to find all episodes of this series.
            val seriesUrl = "$baseUrl${anime.url}".normalizeHref()

            // Get series title from the page, strip Japanese name for matching
            val h1Episode = doc.selectFirst("div.relative > div.justify-between > div > div > h1")?.text()
            val h1Series = doc.selectFirst("div.relative > h1")?.text()
            val h1Fallback = doc.selectFirst("h1")?.text()

            val seriesTitle = h1Episode ?: h1Series ?: h1Fallback ?: ""
            logDebug("getEpisodeList") { "seriesTitle: '$seriesTitle'" }

            val matchTitle = seriesTitle.substringBefore("(").trim()
            logDebug("getEpisodeList") { "matchTitle: '$matchTitle'" }

            if (matchTitle.isBlank()) {
                HstreamLogger.warn("getEpisodeList", "seriesTitle is blank — cannot search. Falling back to single episode.")
                val episodeUrl = "$seriesUrl-1"
                val fallbackEpNum = episodeUrl.extractEpisodeNumber()
                return listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(episodeUrl)
                        episode_number = fallbackEpNum?.toFloatOrNull() ?: 1F
                        name = if (fallbackEpNum != null) "Episode $fallbackEpNum" else "Episode 1"
                        date_upload = 0L
                    },
                )
            }

            val searchUrl = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("search", matchTitle)
                .build()

            val searchDoc = client.newCall(GET(searchUrl.toString())).awaitSuccess().use { it.asJsoup() }
            val searchResults = searchDoc.select(popularAnimeSelector())
            logDebug("getEpisodeList") { "searchResults: ${searchResults.size} total" }

            val urlPrefix = "$seriesUrl-"

            val episodes = searchResults.mapNotNull { element ->
                val rawHref = element.attr("href")
                val href = rawHref.normalizeHref()

                if (!href.startsWith(urlPrefix)) return@mapNotNull null

                val alt = element.selectFirst("img")?.attr("alt")
                if (alt == null) return@mapNotNull null

                if (!alt.contains(matchTitle, ignoreCase = true)) return@mapNotNull null

                val epNum = href.extractEpisodeNumber()
                if (epNum == null) return@mapNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = epNum.toFloatOrNull() ?: 1F
                    name = "Episode $epNum"
                    date_upload = 0L
                }
            }

            logDebug("getEpisodeList") { "Matched ${episodes.size} episodes from ${searchResults.size} results" }

            // Fallback for single-series entries where search found no matching episodes.
            // Without this, the framework creates a default episode named "Episode (url)".
            if (episodes.isEmpty()) {
                HstreamLogger.warn("getEpisodeList", "No episodes found via search! Falling back to single-episode from page URL.")

                val episodeUrl = "$seriesUrl-1"
                val fallbackEpNum = episodeUrl.extractEpisodeNumber()

                val fallbackEp = SEpisode.create().apply {
                    setUrlWithoutDomain(episodeUrl)
                    if (fallbackEpNum != null) {
                        episode_number = fallbackEpNum.toFloatOrNull() ?: 1F
                        name = "Episode $fallbackEpNum"
                    } else {
                        episode_number = 1F
                        name = "Episode 1"
                    }
                    date_upload = 0L
                }

                logDebug("getEpisodeList") { "Returning 1 fallback episode" }
                return listOf(fallbackEp)
            }

            val sortedEpisodes = episodes.sortedByDescending { it.episode_number }
            logDebug("getEpisodeList") { "Returning ${sortedEpisodes.size} sorted episodes" }
            return sortedEpisodes
        }

        // Original behavior: single episode from the page (grouping disabled)
        logDebug("getEpisodeList") { "Path: UNGROUPED" }

        val doc = client.newCall(animeDetailsRequest(anime)).awaitSuccess().use { it.asJsoup() }

        val uploadDateText = doc.selectFirst("a:has(i.fa-upload)")?.ownText()

        val episode = SEpisode.create().apply {
            date_upload = uploadDateText.toDate()
            setUrlWithoutDomain(doc.location())

            val num = url.substringAfterLast("-").substringBefore("/")
            episode_number = num.toFloatOrNull() ?: 1F
            name = "Episode $num"
        }

        logDebug("getEpisodeList") { "Returning 1 ungrouped episode: '${episode.name}'" }
        return listOf(episode)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Use getEpisodeList instead")

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        logDebug("videoListParse") { "Parsing video list from: ${response.request.url}" }
        val doc = response.asJsoup()

        val token = client.cookieJar.loadForRequest(response.request.url)
            .find { it.name == "XSRF-TOKEN" }?.value
            ?: throw Exception("XSRF-TOKEN cookie not found")
        logDebug("videoListParse") { "XSRF token found: ${token.take(10)}..." }

        val episodeId = doc.selectFirst("input#e_id")!!.attr("value")
        logDebug("videoListParse") { "Episode ID: $episodeId" }

        val newHeaders = headersBuilder().apply {
            set("Referer", doc.location())
            set("Origin", baseUrl)
            set("X-Requested-With", "XMLHttpRequest")
            set("X-XSRF-TOKEN", URLDecoder.decode(token, "utf-8"))
        }.build()

        val body = """{"episode_id": "$episodeId"}""".toRequestBody("application/json".toMediaType())
        val data = client.newCall(POST("$baseUrl/player/api", newHeaders, body)).execute()
            .parseAs<PlayerApiResponse>()
        logDebug("videoListParse") { "API response: legacy=${data.legacy}, resolution=${data.resolution}, domains=${data.stream_domains.size}" }

        val urlBase = data.stream_domains.random() + "/" + data.stream_url
        val subtitleList = listOf(Track("$urlBase/eng.ass", "English"))

        val resolutions = listOfNotNull("720", "1080", if (data.resolution == "4k") "2160" else null)
        val videos = resolutions.map { resolution ->
            val url = urlBase + getVideoUrlPath(data.legacy != 0, resolution)
            Video(url, "${resolution}p", url, subtitleTracks = subtitleList)
        }
        logDebug("videoListParse") { "Built ${videos.size} videos" }
        return videos
    }

    private fun getVideoUrlPath(isLegacy: Boolean, resolution: String): String {
        logDebug("getVideoUrlPath") { "legacy=$isLegacy, resolution=$resolution" }
        val path = if (isLegacy) {
            if (resolution.equals("720")) {
                "/x264.720p.mp4"
            } else {
                "/av1.$resolution.webm"
            }
        } else {
            "/$resolution/manifest.mpd"
        }
        logDebug("getVideoUrlPath") { "Result path: $path" }
        return path
    }

    @Serializable
    data class PlayerApiResponse(
        val legacy: Int = 0,
        val resolution: String = "4k",
        val stream_url: String,
        val stream_domains: List<String>,
    )

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        logDebug("setupPreferenceScreen") { "Setting up preferences" }
        screen.addSwitchPreference(
            key = PREF_GROUP_BY_SERIES_KEY,
            default = PREF_GROUP_BY_SERIES_DEFAULT,
            title = PREF_GROUP_BY_SERIES_TITLE,
            summary = PREF_GROUP_BY_SERIES_SUMMARY,
        )

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun String?.toDate(): Long = runCatching { DATE_FORMATTER.parse(orEmpty().trim(' ', '|'))?.time }
        .getOrNull() ?: 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        logDebug("sort") { "Sorting ${this.size} videos, preferred quality: $quality" }

        val sorted = sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()

        logDebug("sort") { "Sorted order: ${sorted.map { it.quality }}" }
        return sorted
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "id:"

        private const val PREF_GROUP_BY_SERIES_KEY = "pref_group_by_series_key"
        private const val PREF_GROUP_BY_SERIES_TITLE = "Group by series"
        private const val PREF_GROUP_BY_SERIES_SUMMARY = "Merge episodes of the same series into a single entry"
        private const val PREF_GROUP_BY_SERIES_DEFAULT = true

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("720p (HD)", "1080p (FULLHD)", "2160p (4K)")
        private val PREF_QUALITY_VALUES = arrayOf("720p", "1080p", "2160p")
    }
}
