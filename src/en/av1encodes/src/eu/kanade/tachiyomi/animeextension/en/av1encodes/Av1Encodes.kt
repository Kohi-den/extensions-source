package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

@Suppress("SpellCheckingInspection")
class Av1Encodes :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AV1Encodes"
    override val baseUrl = "https://av1encodes.com"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    // ─── Client Optimization ─────────────────────────────────────────────────
    override val client: OkHttpClient = network.client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .build()

    // ─── Headers ─────────────────────────────────────────────────────────────

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
        .add("Sec-Ch-Ua-Mobile", "?0")
        .add("Sec-Ch-Ua-Platform", "\"Windows\"")

    // ══════════════════════════════════════════════════════════════════════════
    // STANDARD REQUESTS
    // ══════════════════════════════════════════════════════════════════════════

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    // ══════════════════════════════════════════════════════════════════════════
    // POPULAR (Stats page)
    // ══════════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/stats#top-downloads", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = parseStatsPage(doc)
        return AnimesPage(animes, false)
    }

    private fun parseStatsPage(doc: Document): List<SAnime> {
        val seen = mutableSetOf<String>()
        val animes = mutableListOf<SAnime>()

        var searchContext: Element = doc
        val header = doc.select("h1, h2, h3, h4, h5, h6").firstOrNull {
            it.text().contains("Top Downloads", ignoreCase = true)
        }

        if (header != null) {
            val sibling = header.nextElementSibling()
            searchContext = if (sibling != null && sibling.text().length > 20) {
                sibling
            } else {
                header.parent() ?: doc
            }
        }

        val items =
            searchContext.select("a[href*='/anime/'], div[class*='card'], div[class*='item'], li")
                .filter { el ->
                    val text = el.text().trim()
                    text.contains(Regex("""\[S\d""")) || text.length in 10..200
                }

        items.forEach { el ->
            val link = el.selectFirst("a[href*='/anime/']")
                ?: el.takeIf { it.tagName() == "a" && it.attr("href").contains("/anime/") }
            if (link != null) {
                val url = link.attr("href").let {
                    if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                }
                if (url.startsWith("/anime/") && seen.add(url)) {
                    animes.add(
                        SAnime.create().apply {
                            this.url = url
                            title = extractCleanTitle(el.text())
                            thumbnail_url = getListImageUrl(el)
                        },
                    )
                }
                return@forEach
            }
            val rawText = el.text().trim()
            val animeName = extractCleanTitle(rawText)
            val slug = animeName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')

            if (slug.length < 3 || !seen.add("/anime/$slug")) return@forEach

            animes.add(
                SAnime.create().apply {
                    url = "/anime/$slug"
                    title = animeName
                    thumbnail_url = null
                },
            )
        }

        if (animes.isEmpty()) {
            Regex("""\[S\d{1,2}(?:-E\d+)?]\s*([^\[]+?)\s*\[""").findAll(searchContext.text())
                .map { it.groupValues[1].trim() }
                .distinct()
                .take(20)
                .forEach { animeName ->
                    val slug =
                        animeName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    if (slug.length >= 3 && seen.add("/anime/$slug")) {
                        animes.add(
                            SAnime.create().apply {
                                url = "/anime/$slug"
                                title = extractCleanTitle(animeName)
                            },
                        )
                    }
                }
        }

        fetchMissingCovers(animes)
        return animes
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LATEST & SEARCH
    // ══════════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/airing/sub?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseUniversalList(response.asJsoup())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url.toString(), headers)
        }

        var sortValue = ""
        var typeValue = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortValue = SORT_VALUES.getOrElse(filter.state) { "" }
                is TypeFilter -> typeValue = TYPE_VALUES.getOrElse(filter.state) { "" }
                else -> {}
            }
        }

        return when (typeValue) {
            "sub" -> GET("$baseUrl/airing/sub?page=$page", headers)
            "dual" -> GET("$baseUrl/airing/dual?page=$page", headers)
            else -> {
                val url = "$baseUrl/anime".toHttpUrl().newBuilder()
                    .addQueryParameter("page", page.toString())
                if (sortValue.isNotBlank()) url.addQueryParameter("sort", sortValue)
                GET(url.build().toString(), headers)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseUniversalList(response.asJsoup())

    // ══════════════════════════════════════════════════════════════════════════
    // UNIFIED LIST PARSER  (FIX: robust title extraction for airing pages)
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseUniversalList(doc: Document): AnimesPage {
        val animesMap = mutableMapOf<String, SAnime>()
        val elements = doc.select("a[href*='/anime/']")

        for (a in elements) {
            val href =
                a.attr("href").let { if (it.startsWith("http")) it.removePrefix(baseUrl) else it }
            if (href == "/anime" || href == "/anime/") continue

            val slug = href.substringAfter("/anime/").substringBefore("?").substringBefore("/")
            if (slug.length < 2) continue

            val anime = animesMap.getOrPut(slug) {
                SAnime.create().apply {
                    url = "/anime/$slug"
                    title = ""
                }
            }

            var titleText = a.text().trim()

            // Reject episode-button link text ("Episode 5", "Ep.3", "Watch Episode 2", etc.)
            val isEpisodeButton = titleText.matches(
                Regex(
                    """^(?:Watch\s+)?(?:Episode|Ep\.?)\s*\d+.*""",
                    RegexOption.IGNORE_CASE,
                ),
            )
            if (isEpisodeButton) titleText = ""

            // Try the anchor's own title attribute
            if (titleText.isBlank()) titleText = a.attr("title").trim()
// Walk up the DOM to find the nearest card/article/list-item that
            // contains a heading or image-alt we can use as the anime title.
            if (titleText.isBlank()) {
                // Walk up a maximum of 6 levels looking for a real container
                val container = generateSequence(a.parent()) { it.parent() }
                    .take(6)
                    .firstOrNull { el ->
                        el.tagName() == "article" ||
                            el.tagName() == "li" ||
                            el.className().contains("card", ignoreCase = true) ||
                            el.className().contains("item", ignoreCase = true) ||
                            el.className().contains("anime", ignoreCase = true) ||
                            // Any div that directly owns a heading is a good container
                            (el.tagName() == "div" && el.selectFirst("h1,h2,h3,h4,h5") != null)
                    } ?: a.parent()?.parent() ?: a.parent()

                // Prefer explicit heading elements
                titleText =
                    container?.selectFirst(
                        "h1, h2, h3, h4, h5, .title, .anime-title, .name, [class*='title'], [class*='name']",
                    )?.text()?.trim() ?: ""

                // Fall back to any img[alt] inside the container
                if (titleText.isBlank()) {
                    titleText = container?.selectFirst("img[alt]")
                        ?.attr("alt")?.trim()
                        ?.takeIf { it.length > 2 } ?: ""
                }
            }

            titleText = extractCleanTitle(titleText)

            if (titleText.isNotBlank() && anime.title.isBlank()) {
                anime.title = titleText
            }

            if (anime.thumbnail_url == null) {
                anime.thumbnail_url = getListImageUrl(a)
                    ?: a.parent()?.let { extractBg(it) }
                    ?: a.parent()?.parent()?.let { extractBg(it) }
            }
        }

        val animes = animesMap.values.filter { it.title.isNotBlank() }.toList()

        fetchMissingCovers(animes)

        val hasNextPage =
            doc.selectFirst("a[rel=next], .pagination .next, a.next-page, .nav-links .next, [aria-label='Next page']") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PARALLEL N+1 COVER FETCHER
    // ══════════════════════════════════════════════════════════════════════════

    private fun fetchMissingCovers(animes: List<SAnime>) {
        val missingCovers = animes.filter { it.thumbnail_url == null }
        if (missingCovers.isEmpty()) return

        runBlocking {
            missingCovers.map { anime ->
                async(Dispatchers.IO) {
                    try {
                        val detailUrl = baseUrl + anime.url
                        client.newCall(GET(detailUrl, headers)).execute().use { detailResp ->
                            if (detailResp.isSuccessful) {
                                val detailHtml = detailResp.body.string()
                                val ogMatch =
                                    Regex("""<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""").find(
                                        detailHtml,
                                    )
                                if (ogMatch != null) {
                                    anime.thumbnail_url = ogMatch.groupValues[1]
                                } else {
                                    val detailDoc = Jsoup.parse(detailHtml)
                                    val img =
                                        detailDoc.selectFirst("img.anime-poster, img.poster, .anime-hero img")
                                    anime.thumbnail_url =
                                        img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }.awaitAll()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXTRACTION HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun extractCleanTitle(raw: String): String {
        var cleaned =
            raw.replace(Regex("""\s*·\s*\d+\s*downloads?.*""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""^\[[a-zA-Z0-9_\-]+]\s*"""), "")
        cleaned = cleaned.replace(Regex("""\s*\[\d{3,4}p].*""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""\.(mkv|mp4)$""", RegexOption.IGNORE_CASE), "")
        return cleaned.trim()
    }

    private fun getListImageUrl(anchor: Element): String? {
        val img = anchor.selectFirst("img")
        if (img != null) {
            val url = img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }
                .ifBlank { img.attr("abs:src") }
            if (url.isNotBlank()) return url
        }
var bg = extractBg(anchor)
        if (bg != null) return bg

        for (child in anchor.allElements) {
            bg = extractBg(child)
            if (bg != null) return bg
        }
        return null
    }

    private fun extractBg(el: Element): String? {
        val style = el.attr("style")
        if (style.contains("background", ignoreCase = true)) {
            val match = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)
            if (match != null && match.groupValues[1].isNotBlank()) {
                val url = match.groupValues[1]
                return if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
            }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ANIME DETAIL
    // ══════════════════════════════════════════════════════════════════════════

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title =
                doc.selectFirst(".anime-hero h1, h1.anime-title, [class*='anime-hero'] h1, [class*='detail'] h1, main h1, h1")
                    ?.text()?.trim() ?: ""

            val img =
                doc.selectFirst("img.anime-poster, img.poster, .anime-hero img, [class*='poster'] img, [class*='hero'] img, .detail-page img, main img")
            thumbnail_url = img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractBg(
                    doc.selectFirst(".anime-poster, .poster, .anime-hero, [class*='poster'], [class*='hero']")
                        ?: doc,
                )

            description =
                doc.selectFirst(".anime-synopsis, .synopsis, .description, [class*='synopsis'], [class*='description'], [class*='overview'], .desc")
                    ?.text()?.trim()
            genre =
                doc.select(".genre-tag, .tag, a[href*='/genre/'], a[href*='/tag/'], [class*='genre'] a, [class*='tag']:not(script):not(style)")
                    .joinToString { it.text().trim() }.ifBlank { null }
            author = doc.selectFirst(".studio, .studio-name, [class*='studio']")?.text()?.trim()
            status =
                if (doc.selectFirst("[class*='airing'], .status-airing, .airing-badge") != null) SAnime.ONGOING else SAnime.COMPLETED
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EPISODE LIST  (FIX: extract full URLs with tokens directly from page)
    // ══════════════════════════════════════════════════════════════════════════

    override fun episodeListParse(response: Response): List<SEpisode> {
        val rawHtml = response.body.string()
        val doc = Jsoup.parse(rawHtml)

        val urlPath = response.request.url.encodedPath
        val slug = urlPath.split("/").last { it.isNotBlank() }

        val seasons =
            doc.select(".season-tab[data-season], .season-option[data-season], [data-season]")
                .map { it.attr("data-season") }
                .distinct()
                .ifEmpty { listOf("1") }

        val encodedRes = prefQuality.replace(" ", "%20")
        val allEpisodes = mutableListOf<SEpisode>()

        val sortedSeasons = seasons.sortedByDescending { it.toIntOrNull() ?: 0 }

        for (season in sortedSeasons) {
            val epPageUrl = "$baseUrl/episodes/$slug/$season/$encodedRes"

            val epResponse = try {
                client.newCall(GET(epPageUrl, headers)).execute()
            } catch (_: Exception) {
                continue
            }

            if (!epResponse.isSuccessful) {
                epResponse.close()
                continue
            }

            val epHtml = epResponse.body.string()
            val epDoc = Jsoup.parse(epHtml)
            val seasonEpisodes = mutableListOf<SEpisode>()

            // ── NEW: read the token-bearing download links directly from the page ──
            val downloadAnchors = epDoc.select("a[href*='/download/']")

            if (downloadAnchors.isNotEmpty()) {
                for (anchor in downloadAnchors) {
                    val href = anchor.attr("href")
                    // Make relative (strip baseUrl prefix if present)
                    val relativeUrl =
                        if (href.startsWith("http")) href.removePrefix(baseUrl) else href
            // Decode the filename portion (before any '?token=…') for labels
                    val encodedFn = relativeUrl.substringAfterLast("/").substringBefore("?")
                    val filename = try {
                        URLDecoder.decode(encodedFn, "UTF-8")
                    } catch (_: Exception) {
                        encodedFn
                    }
                    if (filename.isBlank() || filename.contains("/")) continue

                    seasonEpisodes.add(
                        SEpisode.create().apply {
                            // Store full relative URL including ?token=… for direct playback
                            url = relativeUrl
                            name = buildEpisodeLabel(filename, season)
                            episode_number = parseEpisodeNumber(filename)
                        },
                    )
                }
            } else {
                // Fallback to the old filename-scraping approach (no token)
                val filenames = extractFilenames(epHtml)
                for (filename in filenames) {
                    val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                    seasonEpisodes.add(
                        SEpisode.create().apply {
                            url = "/download/$slug/$season/$encodedRes/$encodedFilename"
                            name = buildEpisodeLabel(filename, season)
                            episode_number = parseEpisodeNumber(filename)
                        },
                    )
                }
            }

            seasonEpisodes.sortByDescending { it.episode_number }
            allEpisodes.addAll(seasonEpisodes)
        }

        return allEpisodes
    }

    private fun extractFilenames(html: String): List<String> {
        val filenames = mutableSetOf<String>()
        val addDecoded = { fn: String ->
            val cleanFn = try {
                URLDecoder.decode(fn.trim(), "UTF-8")
            } catch (_: Exception) {
                fn.trim()
            }
            if (cleanFn.isNotBlank() && !cleanFn.contains("/")) {
                filenames.add(cleanFn)
            }
        }
        val doc = Jsoup.parse(html)
        doc.select("a[href*='/download/']").forEach {
            addDecoded(it.attr("href").substringAfterLast("/").substringBefore("?"))
        }
        val mkvRegex =
            Regex("""([a-zA-Z0-9_ \-\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE)
        mkvRegex.findAll(html).forEach { addDecoded(it.groupValues[1]) }

        return filenames.toList()
    }

    private fun buildEpisodeLabel(filename: String, season: String): String {
        val epMatch = Regex("""\[(?:S\d+-)?E(\d+)]\s*(.+?)\s*\[""").find(filename)
        return if (epMatch != null) {
            val e = epMatch.groupValues[1]
            val titlePart = epMatch.groupValues[2].trim()
            val audioTag = Regex(
                """\[(Dual|Sub|Dub|English Dub)]""",
                RegexOption.IGNORE_CASE,
            ).find(filename)?.groupValues?.get(1) ?: ""
            "Season $season Ep $e - $titlePart${if (audioTag.isNotBlank()) " [$audioTag]" else ""}"
        } else {
            val cleanName =
                filename.replace(Regex("""\[\d{3,4}p].*"""), "").substringBeforeLast(".").trim()
            if (season != "1" && season.isNotBlank()) "Season $season - $cleanName" else cleanName
        }
    }

    private fun parseEpisodeNumber(filename: String): Float =
        Regex("""\[(?:S\d+-)?E(\d+)]""").find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

    // ══════════════════════════════════════════════════════════════════════════
    // VIDEO LIST  (FIX: use direct token-bearing URL; no more /get_ddl)
    // ══════════════════════════════════════════════════════════════════════════

    override fun videoListRequest(episode: SEpisode) =
        GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return try {
            // episode.url already contains the full path + ?token=… from the episodes page
            val downloadUrl = baseUrl + episode.url

            // Decode filename (strip token query param first) for quality labels
            val encodedFn = episode.url.substringAfterLast("/").substringBefore("?")
            val filename = try {
                URLDecoder.decode(encodedFn, "UTF-8")
            } catch (_: Exception) {
                encodedFn
            }  
       val resLabel =
                Regex("""\[(\d{3,4}p)]""").find(filename)?.groupValues?.get(1) ?: prefQuality
            val audioTag =
                Regex("""\[(Dual|Sub|Dub|English Dub)]""", RegexOption.IGNORE_CASE)
                    .find(filename)?.groupValues?.get(1)?.let { " [$it]" } ?: ""
            val qualLabel = "AV1 · $resLabel$audioTag"

            listOf(
                Video(downloadUrl, "$qualLabel · Direct DL", downloadUrl),
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILTERS
    // ══════════════════════════════════════════════════════════════════════════

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Sort and Type cannot be combined"),
        SortFilter(),
        TypeFilter(),
    )

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort By",
        arrayOf("Latest Added", "A–Z", "Z–A", "Episode Count"),
    )

    private class TypeFilter : AnimeFilter.Select<String>(
        "Audio Type (overrides Sort)",
        arrayOf("All", "Sub only (Airing)", "Dual audio (Airing)"),
    )

    // ══════════════════════════════════════════════════════════════════════════
    // PREFERENCES
    // ══════════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Resolution"
            summary = "%s\n\nIf a season shows no episodes, try a lower resolution."
            entries = QUALITY_ENTRIES
            entryValues = QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val q = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(q) },
                { it.quality.replace("p", "").toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1920 x 1080"
        private val QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val QUALITY_VALUES = arrayOf("1920 x 1080", "1280 x 720", "854 x 480", "640 x 360")
        private val SORT_VALUES = arrayOf("", "a-z", "z-a", "episodes")
        private val TYPE_VALUES = arrayOf("", "sub", "dual")
    }
    }     
    
