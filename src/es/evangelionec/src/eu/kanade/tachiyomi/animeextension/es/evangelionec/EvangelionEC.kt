package eu.kanade.tachiyomi.animeextension.es.evangelionec

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.EXCLUDED_LABELS
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.ITEMS_PER_PAGE
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.LABEL_FILTER_REGEX
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.PREF_QUALITY_DEFAULT
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.PREF_QUALITY_KEY
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.PREF_SERVER_DEFAULT
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.PREF_SERVER_KEY
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.PREF_SPLIT_SEASONS_DEFAULT
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.PREF_SPLIT_SEASONS_KEY
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.QUALITY_LIST
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.RESOLUTION_REGEX
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.SERVER_LIST
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECConstants.SERVER_PATTERNS
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECFilters.GenreFilter
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECFilters.LanguageFilter
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECFilters.StatusFilter
import eu.kanade.tachiyomi.animeextension.es.evangelionec.EvangelionECFilters.TypeFilter
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class EvangelionEC :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Evangelion-EC"
    override val baseUrl = "https://www.evangelion-ec.net"
    override val lang = "es"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Extractors ===============================

    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val megaUpExtractor by lazy { MegaUpExtractor(client) }
    private val megaNzExtractor by lazy { MegaNzExtractor(client, json) }

    @Volatile
    private var currentEpNumber: String = ""

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val startIndex = (page - 1) * ITEMS_PER_PAGE + 1
        return GET(
            "$baseUrl/feeds/posts/default/-/Finalizado/TV?alt=json&max-results=$ITEMS_PER_PAGE&start-index=$startIndex",
            headers,
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val contentType = response.header("Content-Type") ?: ""
        // When called from relatedAnimeListParse, the response is HTML (detail page)
        if ("text/html" in contentType || !contentType.contains("json", ignoreCase = true)) {
            val body = response.peekBody(Long.MAX_VALUE).string().trimStart()
            if (body.startsWith("<")) {
                return parseRelatedFromHtml(body)
            }
        }
        return parseBloggerFeed(response)
    }

    private fun parseRelatedFromHtml(html: String): AnimesPage {
        val document = org.jsoup.Jsoup.parse(html)
        val labels =
            document
                .select("a[rel=tag]")
                .map { it.text() }
                .filter { !it.matches(LABEL_FILTER_REGEX) && it !in EXCLUDED_LABELS }
        if (labels.isEmpty()) return AnimesPage(emptyList(), false)

        val label = labels.first()
        val feedUrl = "$baseUrl/feeds/posts/default/-/$label?alt=json&max-results=$ITEMS_PER_PAGE"
        val feedResponse = client.newCall(GET(feedUrl, headers)).execute()
        return parseBloggerFeed(feedResponse)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val startIndex = (page - 1) * ITEMS_PER_PAGE + 1
        return GET(
            "$baseUrl/feeds/posts/default/-/En%20Emisi%C3%B3n?alt=json&max-results=$ITEMS_PER_PAGE&start-index=$startIndex",
            headers,
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseBloggerFeed(response)

    // ============================== Search ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val startIndex = (page - 1) * ITEMS_PER_PAGE + 1

        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val languageFilter = filters.filterIsInstance<LanguageFilter>().firstOrNull()

        // Build label path from filters
        val labels = mutableListOf<String>()
        genreFilter?.selected()?.let { labels.add(it) }
        statusFilter?.selected()?.let { labels.add(it) }
        typeFilter?.selected()?.let { labels.add(it) }
        languageFilter?.selected()?.let { labels.add(it) }

        return if (query.isNotBlank()) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            GET(
                "$baseUrl/feeds/posts/default?alt=json&max-results=$ITEMS_PER_PAGE&start-index=$startIndex&q=$encoded",
                headers,
            )
        } else if (labels.isNotEmpty()) {
            val labelPath = labels.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
            GET(
                "$baseUrl/feeds/posts/default/-/$labelPath?alt=json&max-results=$ITEMS_PER_PAGE&start-index=$startIndex",
                headers,
            )
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseBloggerFeed(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = EvangelionECFilters.getFilterList()

    // ============================== Anime Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url =
            anime.url
                .substringBefore("#")
                .toAbsoluteUrl()
                .forceDesktop()
        val fragment = anime.url.substringAfter("#", "")
        val finalUrl = if (fragment.isNotBlank()) "$url#$fragment" else url
        return GET(finalUrl, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.title[itemprop=name]")?.text()
                ?: document.selectFirst("title")?.text()?.substringBefore(" -") ?: ""

            thumbnail_url = document.selectFirst("section#info img[itemprop=image]")?.attr("src")
                ?: document.selectFirst(".separator img")?.attr("src")

            // Detect if it has seasons (skip if already a season entry)
            val isSeason =
                response.request.url.fragment
                    ?.startsWith("season") == true
            val hasSeasons = !isSeason && document.select(".v7_season-card a[href]").isNotEmpty()
            fetch_type = preferredFetchType(hasSeasons)

            description = document.selectFirst("#synopsis")?.text()

            genre =
                document
                    .select("section#info .meta a[rel=tag]")
                    .map { it.text().trim().removeSuffix(",") }
                    .filter { it.isNotBlank() && !it.matches(Regex("^(TV|Ova|Ona|Especial|Películas)$")) }
                    .joinToString(", ")

            status =
                when {
                    document.selectFirst(".btn__status[href*=Emisi]") != null -> SAnime.ONGOING
                    document.selectFirst(".btn__status[href*=Finalizado]") != null -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }

            // Extra info
            val extraInfo =
                buildString {
                    document.selectFirst("#extra-info")?.let { info ->
                        info.children().forEach { div ->
                            val text = div.text().trim()
                            if (text.isNotBlank() && !text.startsWith("Temporada:")) {
                                if (isNotEmpty()) append("\n")
                                append(text)
                            }
                        }
                    }
                }
            if (extraInfo.isNotBlank()) {
                description = (description ?: "") + "\n\n" + extraInfo
            }

            author = document.selectFirst("#extra-info div:contains(Estudio) span a")?.text()
        }
    }

    // ============================== Episodes ===============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET(
            anime.url
                .substringBefore("#")
                .toAbsoluteUrl()
                .forceDesktop(),
            headers,
        )

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        // Collect all unique episode numbers from the streaming player
        val episodeMap = mutableMapOf<String, MutableList<String>>()

        document.select("ul.serverEpisode .DagPlayOpt[data-embed]").forEach { element ->
            val embedUrl = element.attr("data-embed").trim()
            val epNumber = element.selectFirst("span")?.text()?.trim() ?: return@forEach
            if (embedUrl.isNotBlank()) {
                episodeMap.getOrPut(epNumber) { mutableListOf() }.add(embedUrl)
            }
        }

        // If no streaming episodes found, check if it's a movie (single page)
        if (episodeMap.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    name = "Película"
                    episode_number = 1F
                    setUrlWithoutDomain(response.request.url.encodedPath)
                },
            )
        }

        return episodeMap.keys
            .sortedByDescending { it.toIntOrNull() ?: 0 }
            .map { epNumber ->
                SEpisode.create().apply {
                    name = "Episodio $epNumber"
                    episode_number = epNumber.toFloatOrNull() ?: 0F
                    // Store anime URL with episode number as fragment
                    setUrlWithoutDomain(
                        response.request.url.encodedPath + "#ep=$epNumber",
                    )
                }
            }
    }

    // ============================== Hoster List ===============================

    override fun hosterListRequest(episode: SEpisode): Request {
        currentEpNumber = episode.url.substringAfter("#ep=", "")
        val cleanUrl =
            episode.url
                .substringBefore("#")
                .toAbsoluteUrl()
                .forceDesktop()
        return GET(cleanUrl, headers)
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val epNumber = currentEpNumber

        val embedUrls = mutableListOf<String>()

        if (epNumber.isBlank()) {
            document.select("ul.serverEpisode .DagPlayOpt[data-embed]").forEach { element ->
                val url = element.attr("data-embed").trim()
                if (url.isNotBlank()) embedUrls.add(url)
            }
        } else {
            document.select("ul.serverEpisode .DagPlayOpt[data-embed]").forEach { element ->
                val ep = element.selectFirst("span")?.text()?.trim()
                if (ep == epNumber) {
                    val url = element.attr("data-embed").trim()
                    if (url.isNotBlank()) embedUrls.add(url)
                }
            }
        }

        val hosterMap = mutableMapOf<String, MutableList<Video>>()

        embedUrls.distinct().forEach { url ->
            val serverName = guessServerName(url)
            val videos = runCatching { extractVideosFromUrl(url, serverName) }.getOrNull().orEmpty()
            if (videos.isNotEmpty()) {
                hosterMap.getOrPut(serverName) { mutableListOf() }.addAll(videos)
            }
        }

        return hosterMap.map { (name, videos) ->
            Hoster(hosterName = name, videoList = videos.sortVideos())
        }
    }

    // ============================== Season List ===============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        if (anime.fetch_type != FetchType.Seasons) return emptyList()
        val request = GET(anime.url.toAbsoluteUrl().forceDesktop(), headers)
        val response = client.newCall(request).execute()
        val document = response.use { it.asJsoup() }
        val seasonCards = document.select(".v7_season-card a[href]")

        if (seasonCards.isEmpty()) {
            // No season cards → single "season" fallback so episodes still load
            return listOf(
                SAnime.create().apply {
                    title = anime.title
                    thumbnail_url = anime.thumbnail_url
                    fetch_type = FetchType.Episodes
                    season_number = 1.0
                    setUrlWithoutDomain(anime.url.substringBefore("#") + "#season=1")
                },
            )
        }

        // Build season list from cards (these are the OTHER seasons)
        val seasons =
            seasonCards
                .mapIndexed { index, card ->
                    val href = card.attr("href")
                    val img = card.selectFirst("img")?.attr("src")
                    val seasonTitle = card.selectFirst(".v7_season-info p strong")?.text() ?: "Temporada ${index + 1}"
                    val seasonInfo = card.selectFirst(".v7_season-info p:last-child")?.text() ?: ""

                    SAnime.create().apply {
                        title = seasonTitle
                        thumbnail_url = img
                        description = seasonInfo
                        fetch_type = FetchType.Episodes
                        season_number = (index + 1).toDouble()
                        setUrlWithoutDomain(href.removePrefix(baseUrl).substringBefore("#") + "#season=${index + 1}")
                    }
                }.toMutableList()

        // Add the CURRENT anime as the latest season
        val currentSeasonTitle =
            document.selectFirst("h1.title[itemprop=name]")?.text()
                ?: anime.title
        val currentThumbnail =
            document.selectFirst("section#info img[itemprop=image]")?.attr("src")
                ?: anime.thumbnail_url

        seasons.add(
            SAnime.create().apply {
                title = currentSeasonTitle
                thumbnail_url = currentThumbnail
                fetch_type = FetchType.Episodes
                season_number = (seasons.size + 1).toDouble()
                setUrlWithoutDomain(anime.url.substringBefore("#") + "#season=${seasons.size + 1}")
            },
        )

        return seasons
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val seasonCards = document.select(".v7_season-card a[href]")
        if (seasonCards.isEmpty()) return emptyList()

        return seasonCards.mapIndexed { index, card ->
            val href = card.attr("href")
            val img = card.selectFirst("img")?.attr("src")
            val seasonTitle = card.selectFirst(".v7_season-info p strong")?.text() ?: "Temporada ${index + 1}"
            val seasonInfo = card.selectFirst(".v7_season-info p:last-child")?.text() ?: ""

            SAnime.create().apply {
                title = seasonTitle
                thumbnail_url = img
                description = seasonInfo
                fetch_type = FetchType.Episodes
                season_number = (index + 1).toDouble()
                setUrlWithoutDomain(href.removePrefix(baseUrl))
            }
        }
    }

    // ============================== Video Extraction ===============================

    private fun guessServerName(url: String): String {
        val lowerUrl = url.lowercase()
        return SERVER_PATTERNS
            .firstOrNull { pattern ->
                pattern.keywords.any { lowerUrl.contains(it) }
            }?.name ?: "Unknown"
    }

    private fun extractVideosFromUrl(
        url: String,
        serverName: String,
    ): List<Video> {
        val prefix = "$serverName "
        return when (serverName) {
            "StreamTape" -> streamTapeExtractor.videosFromUrl(url, quality = serverName)
            "Filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = prefix)
            "StreamWish" -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$serverName $it" })
            "Voe" -> voeExtractor.videosFromUrl(url, prefix)
            "Okru" -> okruExtractor.videosFromUrl(url, prefix)
            "Doodstream" -> doodExtractor.videosFromUrl(url, prefix)
            "Mp4Upload" -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = prefix)
            "Uqload" -> uqloadExtractor.videosFromUrl(url, prefix)
            "YourUpload" -> yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = prefix)
            "VidHide" -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$serverName $it" })
            "MegaUp" -> megaUpExtractor.videosFromUrl(url, prefix = prefix, referer = baseUrl)
            "MEGA" -> megaNzExtractor.videosFromUrl(url, prefix = prefix)
            else -> universalExtractor.videosFromUrl(url, headers, prefix)
        }
    }

    // ============================== Blogger Feed Parser ===============================

    private fun parseBloggerFeed(response: Response): AnimesPage {
        val body = response.body.string()
        val root = json.decodeFromString<JsonObject>(body)
        val feed = root["feed"]?.jsonObject ?: return AnimesPage(emptyList(), false)

        val totalResults =
            feed["openSearch\$totalResults"]
                ?.jsonObject
                ?.get("\$t")
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() ?: 0
        val startIndex =
            feed["openSearch\$startIndex"]
                ?.jsonObject
                ?.get("\$t")
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() ?: 1
        val itemsPerPage =
            feed["openSearch\$itemsPerPage"]
                ?.jsonObject
                ?.get("\$t")
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() ?: ITEMS_PER_PAGE

        val entries = feed["entry"]?.jsonArray ?: return AnimesPage(emptyList(), false)

        val animeList =
            entries.mapNotNull { entry ->
                parseAnimeFromFeedEntry(entry.jsonObject)
            }

        val hasNextPage = (startIndex + itemsPerPage - 1) < totalResults
        return AnimesPage(animeList, hasNextPage)
    }

    private fun parseAnimeFromFeedEntry(entry: JsonObject): SAnime? {
        val title =
            entry["title"]
                ?.jsonObject
                ?.get("\$t")
                ?.jsonPrimitive
                ?.content
                ?: return null

        // Get the alternate link (actual page URL)
        val links = entry["link"]?.jsonArray ?: return null
        val alternateLink =
            links
                .firstOrNull { link ->
                    link.jsonObject["rel"]?.jsonPrimitive?.content == "alternate"
                }?.jsonObject
                ?.get("href")
                ?.jsonPrimitive
                ?.content ?: return null

        // Get thumbnail and make it higher quality
        val thumbnailUrl =
            entry["media\$thumbnail"]
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content
                ?.replace("/s72-c/", "/w600/")

        // Get labels for genre/status info
        val labels =
            entry["category"]?.jsonArray?.mapNotNull {
                it.jsonObject["term"]?.jsonPrimitive?.content
            } ?: emptyList()

        val status =
            when {
                labels.contains("En Emisión") -> SAnime.ONGOING
                labels.contains("Finalizado") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

        val isMovie = labels.contains("Movie") || labels.contains("Películas")

        return SAnime.create().apply {
            this.title = title
            setUrlWithoutDomain(alternateLink.removePrefix(baseUrl))
            thumbnail_url = thumbnailUrl
            this.status = status
            fetch_type = preferredFetchType(!isMovie)
            genre =
                labels
                    .filter { it !in EXCLUDED_LABELS && !it.matches(LABEL_FILTER_REGEX) }
                    .joinToString(", ")
        }
    }

    // ============================== Preferences ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY_KEY
                title = "Calidad preferida"
                entries = QUALITY_LIST
                entryValues = QUALITY_LIST
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(key, newValue as String).commit()
                }
            }.also(screen::addPreference)

        ListPreference(screen.context)
            .apply {
                key = PREF_SERVER_KEY
                title = "Servidor preferido"
                entries = SERVER_LIST
                entryValues = SERVER_LIST
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(key, newValue as String).commit()
                }
            }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context)
            .apply {
                key = PREF_SPLIT_SEASONS_KEY
                title = "Dividir temporadas"
                summary = "Mostrar temporadas como entradas separadas"
                setDefaultValue(PREF_SPLIT_SEASONS_DEFAULT)
                isChecked = preferences.getBoolean(PREF_SPLIT_SEASONS_KEY, PREF_SPLIT_SEASONS_DEFAULT)
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putBoolean(key, newValue as Boolean).commit()
                }
            }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================

    private fun preferredFetchType(hasSeasons: Boolean): FetchType =
        if (hasSeasons && preferences.getBoolean(PREF_SPLIT_SEASONS_KEY, PREF_SPLIT_SEASONS_DEFAULT)) {
            FetchType.Seasons
        } else {
            FetchType.Episodes
        }

    private fun String.toAbsoluteUrl(): String = if (startsWith("http")) this else "$baseUrl$this"

    private fun String.forceDesktop(): String {
        if ("m=0" in this || "m=1" in this) return replace(Regex("[?&]m=[01]"), "").let { "$it${if ('?' in it) '&' else '?'}m=0" }
        return "${this}${if ('?' in this) '&' else '?'}m=0"
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(server, ignoreCase = true).not() },
                { it.videoTitle.contains(quality).not() },
                {
                    RESOLUTION_REGEX
                        .find(it.videoTitle)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull() ?: 0
                },
            ),
        ).reversed()
    }
}
