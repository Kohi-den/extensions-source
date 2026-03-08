package eu.kanade.tachiyomi.animeextension.es.hackstoremx

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.goodstramextractor.GoodStreamExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamsilkextractor.StreamSilkExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale

/**
 * HackStoreMX Anime Extension
 * Extension for streaming anime from HackStore.mx
 */
class Hackstoremx :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    // ================================================================================================
    // BASIC CONFIGURATION
    // ================================================================================================

    override val name = "HackStoreMX"
    override val baseUrl = "https://hackstore.mx"
    override val lang = "es"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ================================================================================================
    // CONSTANTS
    // ================================================================================================

    companion object {
        private const val TAG = "HackStoreMX"
        private const val DEBUG_LOGS = true
        const val PREFIX_SEARCH = "id:"

        // Quality preferences
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        // Server preferences
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST =
            arrayOf(
                "Voe",
                "Vimeo",
                "Filemoon",
                "StreamWish",
                "VidHide",
                "Doodstream",
                "StreamTape",
                "Netu",
                "Amazon",
                "BurstCloud",
                "Fastream",
                "Upstream",
                "StreamSilk",
                "Streamlare",
                "Okru",
                "Uqload",
                "Mp4Upload",
                "YourUpload",
                "VidGuard",
            )

        // Language preferences
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[CAST]", "[SUB]")

        // Season preferences
        const val PREF_SPLIT_SEASONS_KEY = "split_seasons"
        const val PREF_SPLIT_SEASONS_DEFAULT = true
        internal const val LEGACY_PREF_FETCH_TYPE_KEY = "preferred_fetch_type"
        internal const val LEGACY_FETCH_TYPE_SEASONS = "1"

        // Date formatter
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale("es", "ES"))
        }
        private val TMDB_IMAGE_REGEX = Regex("^/?[A-Za-z0-9_-]{6,}.*\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
        private val PAGE_PROPS_SCRIPT_REGEX =
            Regex("""<script[^>]*>\s*(\{[\s\S]*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
        private val HOSTER_ARRAY_KEYS = setOf("result", "cyberlocker", "url", "link", "source", "download", "embed")
        private val PLAYER_URL_KEYS = arrayOf("url", "embed")
        private val PLAYER_SERVER_KEYS = arrayOf("server", "cyberlocker")
        private val EPISODE_URL_KEYS = arrayOf("result", "url", "link", "download", "embed")
        private val EPISODE_SERVER_KEYS = arrayOf("cyberlocker", "server", "name")

        // Server display names mapping
        private val SERVER_DISPLAY_NAMES =
            mapOf(
                "voe" to "Voe",
                "vimeo" to "Vimeo",
                "okru" to "Okru",
                "filemoon" to "Filemoon",
                "amazon" to "Amazon",
                "uqload" to "Uqload",
                "mp4upload" to "Mp4Upload",
                "streamwish" to "StreamWish",
                "doodstream" to "Doodstream",
                "streamlare" to "Streamlare",
                "yourupload" to "YourUpload",
                "burstcloud" to "BurstCloud",
                "fastream" to "Fastream",
                "upstream" to "Upstream",
                "streamsilk" to "StreamSilk",
                "streamtape" to "StreamTape",
                "vidhide" to "VidHide",
                "vidguard" to "VidGuard",
                "netu" to "Netu",
            )
    }

    // ================================================================================================
    // VIDEO EXTRACTORS
    // ================================================================================================

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client, headers) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val apiJsonCache = lruCache<String, JsonObject?>(96)
    private val pagePropsCache = lruCache<String, JsonObject?>(64)
    private val canonicalServerCache = lruCache<String, String>(128)
    private val resolvedEmbedUrlCache = lruCache<String, String>(128)
    private val resolvedVideosCache = lruCache<String, List<Video>>(128)

    // ================================================================================================
    // POPULAR ANIME
    // ================================================================================================

    override fun popularAnimeRequest(page: Int): Request =
        GET(
            "$baseUrl/wp-api/v1/listing/movies?page=$page&postsPerPage=12&orderBy=latest&order=desc&postType=movies&filter=%5B%5D",
            headers,
        )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val root = parseJsonObject(response.body.string()) ?: return AnimesPage(emptyList(), false)

        val error = root["error"]?.jsonPrimitive?.boolean ?: false
        if (error) return AnimesPage(emptyList(), false)

        val data = root["data"]?.jsonObject ?: return AnimesPage(emptyList(), false)
        val posts = data["posts"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        val pagination = data["pagination"]?.jsonObject
        val currentPage = pagination?.get("current_page")?.jsonPrimitive?.int ?: 1
        val lastPage = pagination?.get("last_page")?.jsonPrimitive?.int ?: 1
        val hasNextPage = currentPage < lastPage

        val animeList =
            posts.mapNotNull { element ->
                parseAnimeFromJson(element.jsonObject, false)
            }

        return AnimesPage(animeList, hasNextPage)
    }

    // ================================================================================================
    // LATEST UPDATES
    // ================================================================================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(
            "$baseUrl/wp-api/v1/listing/tvshows?page=$page&postsPerPage=12&orderBy=latest&order=desc&postType=tvshows&filter=%5B%5D",
            headers,
        )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val root = parseJsonObject(response.body.string()) ?: return AnimesPage(emptyList(), false)

        val error = root["error"]?.jsonPrimitive?.boolean ?: false
        if (error) return AnimesPage(emptyList(), false)

        val data = root["data"]?.jsonObject ?: return AnimesPage(emptyList(), false)
        val posts = data["posts"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        val pagination = data["pagination"]?.jsonObject
        val currentPage = pagination?.get("current_page")?.jsonPrimitive?.int ?: 1
        val lastPage = pagination?.get("last_page")?.jsonPrimitive?.int ?: 1
        val hasNextPage = currentPage < lastPage

        val animeList =
            posts.mapNotNull { element ->
                parseAnimeFromJson(element.jsonObject, true)
            }

        return AnimesPage(animeList, hasNextPage)
    }

    // ================================================================================================
    // SEARCH
    // ================================================================================================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request =
        if (query.isNotBlank()) {
            // Use the site's search API endpoint which returns JSON: /wp-api/v1/search
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            GET("$baseUrl/wp-api/v1/search?postType=any&q=$encoded&postsPerPage=12&page=$page", headers)
        } else {
            popularAnimeRequest(page)
        }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // Try parse as JSON listing API first
        val body = runCatching { response.body.string() }.getOrNull().orEmpty()
        val root = parseJsonObject(body)

        // If API returned structured data, use it
        if (root != null) {
            val error = root["error"]?.jsonPrimitive?.boolean ?: false
            if (error) return AnimesPage(emptyList(), false)

            val data = root["data"]?.jsonObject ?: return AnimesPage(emptyList(), false)
            val posts = data["posts"]?.jsonArray ?: return AnimesPage(emptyList(), false)
            val pagination = data["pagination"]?.jsonObject
            val currentPage = pagination?.get("current_page")?.jsonPrimitive?.int ?: 1
            val lastPage = pagination?.get("last_page")?.jsonPrimitive?.int ?: 1
            val hasNextPage = currentPage < lastPage

            val animeList =
                posts.mapNotNull { element ->
                    val obj = element.jsonObject

                    // Detect whether this is a series (tvshows) or a movie by checking
                    // several common fields returned by the API.
                    val isSeries = detectIsSeries(obj)
                    parseAnimeFromJson(obj, isSeries)
                }

            return AnimesPage(animeList, hasNextPage)
        }

        // Fallback to HTML parsing if API not available
        val document = Jsoup.parse(body)
        val animeList =
            document.select("article.post").map { element ->
                SAnime.create().apply {
                    title = element.selectFirst("h3")?.text() ?: ""
                    val itemUrl = element.selectFirst("a")?.attr("href") ?: ""
                    setUrlWithoutDomain(itemUrl)
                    val isSeries = itemUrl.contains("/series/") || itemUrl.contains("/serie/")
                    fetch_type = preferredFetchType(isSeries)
                    thumbnail_url = element.selectFirst("img")?.attr("src") ?: ""
                    status = SAnime.UNKNOWN
                }
            }
        val hasNextPage = document.selectFirst("a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ================================================================================================
    // ANIME DETAILS
    // ================================================================================================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url.toAbsoluteUrl(), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val requestUrl = response.request.url
        val pageProps = response.extractPageProps()
        val meta = pageProps?.toGnulaMeta()
        if (meta != null) {
            val forcedEpisodeMode =
                requestUrl.queryParameter("season") != null ||
                    requestUrl.encodedPath.contains("/episodes/")
            val fetchType =
                when {
                    forcedEpisodeMode -> FetchType.Episodes
                    meta.isMovie -> FetchType.Episodes
                    else -> preferredFetchType(meta.seasons.isNotEmpty())
                }

            return SAnime.create().apply {
                title = meta.title
                thumbnail_url = resolvePosterUrl(meta.poster)
                description = meta.overview
                meta.genres
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                    ?.let { genre = it }
                meta.director?.takeIf { it.isNotBlank() }?.let { author = it }
                meta.cast
                    .firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { artist = it }
                status = if (meta.isMovie) SAnime.COMPLETED else SAnime.UNKNOWN
                fetch_type = fetchType
                setUrlWithoutDomain(requestUrl.toString().removePrefix(baseUrl))
            }
        }

        val pathSegments = response.request.url.pathSegments
        val (postType, possibleSlug) =
            when {
                pathSegments.contains("peliculas") -> {
                    "movies" to pathSegments.getOrNull(pathSegments.indexOf("peliculas") + 1)
                }

                pathSegments.contains("series") -> {
                    "tvshows" to pathSegments.getOrNull(pathSegments.indexOf("series") + 1)
                }

                else -> {
                    null to pathSegments.lastOrNull()
                }
            }

        val slug = possibleSlug
        val siteConfig = pageProps
        val infoUrl = "$baseUrl/wp-api/v1/single/${postType ?: "tvshows"}?slug=$slug&postType=${postType ?: "tvshows"}"
        val data = fetchApiJson(infoUrl)?.obj("data")

        if (data != null) {
            val isMovie = data["type"]?.jsonPrimitive?.contentOrNull?.equals("movies", true) == true
            val hasSeasons =
                (data.array("seasons")?.isNotEmpty() == true) ||
                    (data.obj("post")?.array("seasons")?.isNotEmpty() == true)
            val fetchType = preferredFetchType(!isMovie && hasSeasons)

            return SAnime.create().apply {
                title = (data["title"]?.jsonPrimitive?.contentOrNull ?: slug).toString()
                val posterPath =
                    data
                        .obj("images")
                        ?.get("poster")
                        ?.jsonPrimitive
                        ?.contentOrNull
                thumbnail_url = posterPath?.let { resolvePosterUrl(it.optimizeImageUrl()) }
                description = data["overview"]?.jsonPrimitive?.contentOrNull

                // Map genre IDs to names
                val genreNames =
                    data
                        .array("genres")
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                        ?.mapNotNull { id ->
                            siteConfig
                                ?.obj("datas")
                                ?.obj("genres")
                                ?.get(id.toString())
                                ?.jsonObjectOrNull()
                                ?.string("name")
                        } ?: emptyList()
                genre = genreNames.joinToString(", ")

                author = data["director"]?.jsonPrimitive?.contentOrNull
                artist =
                    data
                        .obj("cast")
                        ?.array("acting")
                        ?.firstOrNull()
                        ?.jsonObjectOrNull()
                        ?.string("name")
                status = if (isMovie) SAnime.COMPLETED else SAnime.UNKNOWN
                fetch_type = fetchType
                setUrlWithoutDomain(if (isMovie) "/peliculas/$slug" else "/series/$slug")
            }
        }

        return SAnime.create()
    }

    // ================================================================================================
    // EPISODE LIST
    // ================================================================================================

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url.toAbsoluteUrl(), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val requestUrl = response.request.url
        val pathSegments = requestUrl.pathSegments
        val movieIndex = pathSegments.indexOf("peliculas")
        val movieSlug =
            if (movieIndex != -1 && movieIndex + 1 < pathSegments.size) {
                pathSegments[movieIndex + 1]
            } else {
                null
            }

        if (!movieSlug.isNullOrBlank()) {
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1F
                    name = "Película"
                    setUrlWithoutDomain("/peliculas/$movieSlug")
                },
            )
        }

        val selectedSeason = requestUrl.queryParameter("season")?.toIntOrNull()
        response
            .extractPageProps()
            ?.toGnulaMeta()
            ?.toEpisodeList(selectedSeason)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return getSeriesEpisodes(response)
    }

    // ================================================================================================
    // SEASON LIST
    // ================================================================================================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        if (anime.fetch_type != FetchType.Seasons) return emptyList()
        val request = GET(anime.url.toAbsoluteUrl(), headers)
        return client.newCall(request).execute().use { seasonListParse(it) }
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        debugLog { "seasonListParse: url=${response.request.url}" }

        val pageProps = response.extractPageProps()
        val meta = pageProps?.toGnulaMeta()

        if (meta != null) {
            debugLog { "seasonListParse: found pageProps meta, isMovie=${meta.isMovie}, seasons=${meta.seasons.size}" }
            if (meta.isMovie) return emptyList()

            val basePath =
                response.request.url
                    .toString()
                    .removePrefix(baseUrl)
            return meta.seasons.map { season ->
                season.toSAnime(basePath, meta, baseUrl)
            }
        }

        // Fallback: try to get seasons from public API
        return getSeasonListFromApi(response)
    }

    // ================================================================================================
    // HOSTER LIST
    // ================================================================================================

    override fun hosterListRequest(episode: SEpisode): Request {
        val url = episode.url.toAbsoluteUrl()
        val parsed = url.toHttpUrl()
        val qId = parsed.queryParameter("_id") ?: parsed.queryParameter("postId")

        if (!qId.isNullOrBlank()) {
            return GET("$baseUrl/wp-api/v1/player?postId=$qId", headers)
        }

        val pathSegments = parsed.pathSegments
        val idx = pathSegments.indexOf("episodes")
        val slug =
            if (idx != -1 && idx + 1 < pathSegments.size) {
                pathSegments[idx + 1]
            } else {
                null
            }

        // If this looks like a movie page (/peliculas/{slug}), request the movie single API
        val movieIdx = pathSegments.indexOf("peliculas")
        val movieSlug = if (movieIdx != -1 && movieIdx + 1 < pathSegments.size) pathSegments[movieIdx + 1] else null
        if (!movieSlug.isNullOrBlank()) {
            return GET("$baseUrl/wp-api/v1/single/movies?slug=$movieSlug&postType=movies", headers)
        }

        if (!slug.isNullOrBlank()) {
            return GET("$baseUrl/wp-api/v1/single/episodes?slug=$slug&postType=episodes", headers)
        }

        return GET(url, headers)
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        // Decide whether response is API JSON or HTML to avoid consuming the response twice.
        val requestUrl = response.request.url.toString()
        val contentType = response.header("Content-Type") ?: ""
        val isApiJsonResponse = contentType.contains("application/json") || requestUrl.contains("/wp-api/v1/")
        val isPlayerResponse = requestUrl.contains("/wp-api/v1/player")

        if (isApiJsonResponse) {
            val body = runCatching { response.body.string() }.getOrNull().orEmpty()
            val maybeJsonRoot = parseJsonObject(body)
            apiJsonCache[requestUrl] = maybeJsonRoot

            if (maybeJsonRoot != null) {
                if (isPlayerResponse) {
                    val hostersFromPlayer = extractHostersFromPlayer(maybeJsonRoot, response)
                    if (hostersFromPlayer.isNotEmpty()) return hostersFromPlayer
                }

                val directId = maybeJsonRoot.obj("data")?.firstString("_id", "id")
                if (!directId.isNullOrBlank() && requestUrl.contains("/single/movies")) {
                    val hostersFromPlayer = tryPlayerEndpointWithPostId(directId)
                    if (hostersFromPlayer.isNotEmpty()) return hostersFromPlayer
                }

                val hostersFromApi = parseHostersFromEpisodeJson(maybeJsonRoot)
                if (hostersFromApi.isNotEmpty()) return hostersFromApi

                val postIdCandidate = maybeJsonRoot.findFirstId()
                if (!postIdCandidate.isNullOrBlank()) {
                    val hostersFromPlayer = tryPlayerEndpointWithPostId(postIdCandidate)
                    if (hostersFromPlayer.isNotEmpty()) return hostersFromPlayer
                }
            }

            // As fallback try to parse pageProps from the JSON string or HTML embedded
            val pageProps = extractPagePropsFromString(body)
            if (pageProps != null) {
                val hostersFromProps = extractHostersFromPageProps(pageProps)
                if (hostersFromProps.isNotEmpty()) return hostersFromProps
            }

            // Try player parsing from the body
            if (!isPlayerResponse) {
                val hostersFromPlayer = extractHostersFromPlayer(maybeJsonRoot, response)
                if (hostersFromPlayer.isNotEmpty()) return hostersFromPlayer
            }

            return emptyList()
        } else {
            // HTML: use extractPageProps which works on the Response (consumes body internally)
            val pagePropsFromResponse = response.extractPageProps()
            if (pagePropsFromResponse != null) {
                val hostersFromProps = extractHostersFromPageProps(pagePropsFromResponse)
                if (hostersFromProps.isNotEmpty()) return hostersFromProps

                val postIdCandidate = pagePropsFromResponse.findFirstId()
                if (!postIdCandidate.isNullOrBlank()) {
                    val hostersFromPlayer = tryPlayerEndpointWithPostId(postIdCandidate)
                    if (hostersFromPlayer.isNotEmpty()) return hostersFromPlayer
                }
            }

            // If no pageProps or player found, we cannot re-read the body safely here (it was consumed). Return empty.
            return emptyList()
        }
    }

    // ================================================================================================
    // VIDEO RESOLVER
    // ================================================================================================

    fun serverVideoResolver(
        url: String,
        prefix: String = "",
        serverName: String? = "",
    ): List<Video> {
        return runCatching {
            val resolvedSource =
                when {
                    serverName.isNullOrBlank() -> url
                    serverName.equals("online", ignoreCase = true) -> url
                    serverName.length < 3 -> url
                    else -> serverName
                }

            val source = resolvedSource.ifEmpty { url }
            val matched = canonicalServerSlug(source)
            val displayServer = displayServerName(matched)
            val cacheKey = "$matched|$prefix|$url"

            resolvedVideosCache[cacheKey]?.let { return@runCatching it }

            debugLog {
                "serverVideoResolver: resolved source='$source' matched='$matched' displayServer='$displayServer' prefix='$prefix'"
            }

            val prefixBase = buildPrefix(prefix, displayServer)
            val prefixWithSpace = prefixBase.withTrailingSpace()

            val videos = extractVideosByServer(matched, url, prefixBase, prefixWithSpace)
            resolvedVideosCache[cacheKey] = videos
            return@runCatching videos
        }.getOrNull() ?: emptyList()
    }

    // ================================================================================================
    // VIDEO SORTING
    // ================================================================================================

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val preferredLang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!

        fun Video.matchesLanguage() = if (videoTitle.contains(preferredLang)) 1 else 0

        fun Video.matchesServer() = if (videoTitle.contains(preferredServer, ignoreCase = true)) 1 else 0

        fun Video.matchesQuality() = if (videoTitle.contains(preferredQuality)) 1 else 0

        fun Video.displayResolution(): Int =
            resolution
                ?: QUALITY_REGEX
                    .find(videoTitle)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: 0

        return sortedWith(
            compareBy(
                { it.matchesLanguage() },
                { it.matchesServer() },
                { it.matchesQuality() },
                { it.displayResolution() },
            ),
        ).reversed()
    }

    // ================================================================================================
    // FILTERS
    // ================================================================================================

    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList(
            AnimeFilter.Header("La busqueda por texto ignora el filtro"),
            GenreFilter(),
        )

    private class GenreFilter :
        UriPartFilter(
            "Géneros",
            arrayOf(
                Pair("<selecionar>", ""),
                Pair("Películas", "archives/movies/releases"),
                Pair("Series", "archives/series/releases"),
                Pair("Acción", "genres/accion"),
                Pair("Animación", "genres/animacion"),
                Pair("Crimen", "genres/crimen"),
                Pair("Fámilia", "genres/familia"),
                Pair("Misterio", "genres/misterio"),
                Pair("Suspenso", "genres/suspenso"),
                Pair("Aventura", "genres/aventura"),
                Pair("Ciencia Ficción", "genres/ciencia-ficcion"),
                Pair("Drama", "genres/drama"),
                Pair("Fantasía", "genres/fantasia"),
                Pair("Romance", "genres/romance"),
                Pair("Terror", "genres/terror"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray())

    // ================================================================================================
    // PREFERENCES
    // ================================================================================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_LANGUAGE_KEY
                title = "Preferred language"
                entries = LANGUAGE_LIST
                entryValues = LANGUAGE_LIST
                setDefaultValue(PREF_LANGUAGE_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }.also(screen::addPreference)

        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY_KEY
                title = "Preferred quality"
                entries = QUALITY_LIST
                entryValues = QUALITY_LIST
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }.also(screen::addPreference)

        ListPreference(screen.context)
            .apply {
                key = PREF_SERVER_KEY
                title = "Preferred server"
                entries = SERVER_LIST
                entryValues = SERVER_LIST
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context)
            .apply {
                key = PREF_SPLIT_SEASONS_KEY
                title = "Split seasons"
                summary = "Mostrar temporadas como entradas separadas"
                setDefaultValue(PREF_SPLIT_SEASONS_DEFAULT)
                isChecked = preferences.splitSeasons
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.splitSeasons = newValue as Boolean
                    true
                }
            }.also(screen::addPreference)
    }

    // ================================================================================================
    // HELPER METHODS - PARSING
    // ================================================================================================

    private fun parseAnimeFromJson(
        item: JsonObject,
        isSeries: Boolean,
    ): SAnime? {
        val title = item["title"]?.jsonPrimitive?.content ?: return null
        val slug = item["slug"]?.jsonPrimitive?.content ?: return null
        val images = item["images"]?.jsonObject
        val poster =
            images
                ?.get("poster")
                ?.jsonPrimitive
                ?.content
                .orEmpty()

        return SAnime.create().apply {
            this.title = title
            this.thumbnail_url = resolvePosterUrl(poster.optimizeImageUrl())
            this.fetch_type = preferredFetchType(isSeries)
            setUrlWithoutDomain(if (isSeries) "/series/$slug" else "/peliculas/$slug")
        }
    }

    private fun getSeriesEpisodes(response: Response): List<SEpisode> {
        try {
            val episodes = mutableListOf<SEpisode>()
            val pathSegments = response.request.url.pathSegments
            val slugIndex = pathSegments.indexOf("series")
            val seriesSlug =
                if (slugIndex != -1 && slugIndex + 1 < pathSegments.size) {
                    pathSegments[slugIndex + 1]
                } else {
                    null
                }

            if (!seriesSlug.isNullOrBlank()) {
                val infoUrl = "$baseUrl/wp-api/v1/single/tvshows?slug=$seriesSlug&postType=tvshows"
                val seriesId = fetchApiJson(infoUrl)?.obj("data")?.firstString("_id", "id")

                if (!seriesId.isNullOrBlank()) {
                    val selectedSeason =
                        response.request.url
                            .queryParameter("season")
                            ?.toIntOrNull() ?: 1
                    val epsUrl =
                        "$baseUrl/wp-api/v1/single/episodes/list?_id=$seriesId&season=$selectedSeason&page=1&postsPerPage=200"
                    val epsData = fetchApiJson(epsUrl)?.obj("data")
                    val posts = epsData?.array("posts")
                    val seasonsArr = epsData?.array("seasons")

                    posts?.let { parseEpisodesFromPosts(it, selectedSeason, seriesSlug, episodes) }

                    // Fallback if no episodes found
                    if (episodes.isEmpty() && !seasonsArr.isNullOrEmpty()) {
                        val firstSeasonNum =
                            seasonsArr
                                .firstOrNull()
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.toIntOrNull()
                        if (firstSeasonNum != null && firstSeasonNum != selectedSeason) {
                            fetchFallbackSeasonEpisodes(seriesId, firstSeasonNum, seriesSlug, episodes)
                        }
                    }

                    if (episodes.isNotEmpty()) {
                        return episodes.sortedWith(compareBy { it.episode_number }).reversed()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSeriesEpisodes failed: ${e.message}")
        }

        return emptyList()
    }

    private fun parseEpisodesFromPosts(
        posts: JsonArray,
        selectedSeason: Int,
        seriesSlug: String,
        episodes: MutableList<SEpisode>,
    ) {
        posts.forEach { elem ->
            val obj = elem.jsonObject
            val epNum =
                obj["episode_number"]?.jsonPrimitive?.int
                    ?: obj["episode"]?.jsonPrimitive?.int ?: 0
            val seasonNum = obj["season_number"]?.jsonPrimitive?.int ?: selectedSeason
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
            val date = obj["date"]?.jsonPrimitive?.contentOrNull
            val still = obj["still_path"]?.jsonPrimitive?.contentOrNull

            val ep =
                SEpisode.create().apply {
                    episode_number = epNum.toFloat()
                    name = buildEpisodeName(seasonNum, epNum, title)
                    summary = obj["overview"]?.jsonPrimitive?.contentOrNull
                    preview_url = resolveEpisodeImage(still)
                    date_upload = date?.let {
                        runCatching {
                            val normalized = it.replace(' ', 'T') + ".000Z"
                            DATE_FORMATTER.parse(normalized)?.time
                        }.getOrNull() ?: 0L
                    } ?: 0L
                    setUrlWithoutDomain("/series/$seriesSlug/seasons/$seasonNum/episodes/$slug")
                }
            episodes.add(ep)
        }
    }

    private fun fetchFallbackSeasonEpisodes(
        seriesId: String,
        seasonNum: Int,
        seriesSlug: String,
        episodes: MutableList<SEpisode>,
    ) {
        try {
            val fallbackUrl =
                "$baseUrl/wp-api/v1/single/episodes/list?_id=$seriesId&season=$seasonNum&page=1&postsPerPage=200"
            fetchApiJson(fallbackUrl)
                ?.obj("data")
                ?.array("posts")
                ?.let { parseEpisodesFromPosts(it, seasonNum, seriesSlug, episodes) }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFallbackSeasonEpisodes failed: ${e.message}")
        }
    }

    private fun getSeasonListFromApi(response: Response): List<SAnime> {
        try {
            val pathSegments = response.request.url.pathSegments
            val slugIndex =
                when {
                    pathSegments.contains("peliculas") -> pathSegments.indexOf("peliculas")
                    pathSegments.contains("series") -> pathSegments.indexOf("series")
                    else -> -1
                }
            val slug =
                if (slugIndex != -1 && slugIndex + 1 < pathSegments.size) {
                    pathSegments[slugIndex + 1]
                } else {
                    pathSegments.lastOrNull()
                }

            val postType = if (pathSegments.contains("peliculas")) "movies" else "tvshows"

            if (!slug.isNullOrBlank()) {
                val infoUrl = "$baseUrl/wp-api/v1/single/$postType?slug=$slug&postType=$postType"
                val data = fetchApiJson(infoUrl)?.obj("data") ?: return emptyList()

                var seasonsArr = data.array("seasons")
                debugLog { "seasonListParse: api single/$postType for slug=$slug returned seasons=${seasonsArr?.toString()}" }

                // If no seasons, try episodes/list endpoint
                if (seasonsArr == null || seasonsArr.isEmpty()) {
                    val seriesId = data.firstString("_id", "id")
                    if (!seriesId.isNullOrBlank()) {
                        try {
                            val epsInfoUrl = "$baseUrl/wp-api/v1/single/episodes/list?_id=$seriesId&page=1&postsPerPage=1"
                            val epsSeasons = fetchApiJson(epsInfoUrl)?.obj("data")?.array("seasons")
                            debugLog { "seasonListParse: episodes/list returned seasons=${epsSeasons?.toString()}" }
                            if (!epsSeasons.isNullOrEmpty()) seasonsArr = epsSeasons
                        } catch (e: Exception) {
                            Log.e(TAG, "seasonListParse: episodes/list fallback failed: ${e.message}")
                        }
                    }
                }

                if (seasonsArr == null || seasonsArr!!.isEmpty()) {
                    debugLog { "seasonListParse: no seasons found for slug=$slug postType=$postType" }
                    return emptyList()
                }

                val basePath =
                    if (response.request.url
                            .toString()
                            .startsWith(baseUrl)
                    ) {
                        response.request.url
                            .toString()
                            .removePrefix(baseUrl)
                    } else {
                        "/${if (postType == "movies") "peliculas" else "series"}/$slug"
                    }

                return (seasonsArr as Iterable<Any?>).mapNotNull { seasonElem ->
                    parseSeasonElement(seasonElem, data, slug, basePath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSeasonListFromApi failed: ${e.message}")
        }

        return emptyList()
    }

    private fun parseSeasonElement(
        seasonElem: Any?,
        data: JsonObject,
        slug: String,
        basePath: String,
    ): SAnime? {
        var seasonNum: Int
        var seasonTitle: String? = null
        var seasonOverview: String?
        var seasonSlugName: String? = null
        var seasonSlugSeason: String?

        when (seasonElem) {
            is JsonObject -> {
                seasonNum = seasonElem.long("number")?.toInt()
                    ?: seasonElem.string("number")?.toIntOrNull()
                    ?: return null
                seasonTitle = seasonElem.string("title")
                seasonOverview = seasonElem.string("overview") ?: data.string("overview")
                val slugObj = seasonElem.obj("slug")
                seasonSlugName = slugObj?.string("name")
                seasonSlugSeason = slugObj?.string("season") ?: seasonNum.toString()
            }

            is JsonPrimitive -> {
                val text = seasonElem.jsonPrimitive.contentOrNull ?: return null
                seasonNum = text.toIntOrNull() ?: return null
                seasonOverview = data.string("overview")
                seasonSlugSeason = seasonNum.toString()
            }

            else -> {
                return null
            }
        }

        val gnulaSeason =
            GnulaSeason(
                number = seasonNum,
                title = seasonTitle,
                overview = seasonOverview,
                slugName = seasonSlugName,
                slugSeason = seasonSlugSeason,
                episodes = emptyList(),
            )

        val gnulaMeta =
            GnulaMeta(
                title = data["title"]?.jsonPrimitive?.contentOrNull ?: slug,
                overview = data.string("overview"),
                poster = data.obj("images")?.string("poster")?.optimizeImageUrl(),
                genres = emptyList(),
                director = data.string("director"),
                cast = emptyList(),
                seasons = listOf(gnulaSeason),
                isMovie = data["type"]?.jsonPrimitive?.contentOrNull?.equals("movies", true) == true,
            )

        return gnulaSeason.toSAnime(basePath, gnulaMeta, baseUrl)
    }

    // ================================================================================================
    // HELPER METHODS - HOSTER EXTRACTION
    // ================================================================================================

    private fun extractPagePropsFromString(html: String): JsonObject? {
        if (!html.contains("\"pageProps\"")) return null
        val match = PAGE_PROPS_SCRIPT_REGEX.find(html)
        val jsonString = match?.groups?.get(1)?.value ?: return null
        val root = parseJsonObject(jsonString) ?: return null
        val propsObj = root["props"] as? JsonObject ?: return null
        return propsObj["pageProps"] as? JsonObject
    }

    private fun extractHostersFromPageProps(pageProps: JsonObject): List<Hoster> {
        try {
            val hosterGroups = LinkedHashMap<String, MutableList<Video>>()
            findHosterArrays(pageProps).forEach { arr ->
                arr.collectHosters("", hosterGroups)
            }

            if (hosterGroups.isNotEmpty()) {
                return hosterGroups.map { (server, videos) ->
                    val displayName = server
                    Hoster(hosterName = displayName.ifBlank { "Enlace" }, videoList = videos)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractHostersFromPageProps failed: ${e.message}")
        }

        return emptyList()
    }

    private fun extractHostersFromPlayer(
        root: JsonObject?,
        response: Response,
    ): List<Hoster> {
        try {
            val data = root?.obj("data")
            val embeds = data?.array("embeds")

            debugLog { "hosterListParse: /player root parsed=${root != null}, embeds_count=${embeds?.size ?: 0}" }

            if (embeds.isNullOrEmpty()) {
                debugLog { "hosterListParse: /player data dump: ${data?.toString()}" }
                return tryAlternativePlayerEndpoints(data, response.request.url.queryParameter("postId"))
            }

            val hosters =
                buildHosters(
                    resolveHosterEntries(embeds.toObjectList(), PLAYER_URL_KEYS, PLAYER_SERVER_KEYS),
                    splitByLanguage = false,
                )

            if (hosters.isNotEmpty()) {
                debugLog { "hosterListParse: assembled hosterMap with ${hosters.size} groups" }
                return hosters
            }

            val altHosters = parseHostersFromEpisodeJson(root)
            if (altHosters.isNotEmpty()) {
                debugLog {
                    "hosterListParse: parseHostersFromEpisodeJson returned ${altHosters.size} hoster groups as fallback"
                }
                return altHosters
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractHostersFromPlayer failed: ${e.message}")
        }

        return emptyList()
    }

    private fun tryAlternativePlayerEndpoints(
        data: JsonObject?,
        origPostId: String?,
    ): List<Hoster> {
        try {
            val epId =
                data
                    ?.obj("episode")
                    ?.get("_id")
                    ?.jsonPrimitive
                    ?.contentOrNull
            val serieId =
                data
                    ?.obj("serie")
                    ?.get("_id")
                    ?.jsonPrimitive
                    ?.contentOrNull
            val candidates = listOfNotNull(epId, serieId).distinct().filter { it != origPostId }

            for (candidate in candidates) {
                try {
                    val candRoot = fetchApiJson("$baseUrl/wp-api/v1/player?postId=$candidate")
                    val candData = candRoot?.obj("data")
                    val candEmbeds = candData?.array("embeds")

                    debugLog { "hosterListParse: tried candidate postId=$candidate, embeds_count=${candEmbeds?.size ?: 0}" }

                    if (!candEmbeds.isNullOrEmpty()) {
                        val hosters =
                            buildHosters(
                                resolveHosterEntries(candEmbeds.toObjectList(), PLAYER_URL_KEYS, PLAYER_SERVER_KEYS),
                                splitByLanguage = false,
                            )

                        if (hosters.isNotEmpty()) {
                            debugLog {
                                "hosterListParse: candidate postId=$candidate produced hosterMap with ${hosters.size} groups"
                            }
                            return hosters
                        }
                    }
                } catch (e: Exception) {
                    debugLog { "hosterListParse: candidate postId=$candidate request failed: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            debugLog { "hosterListParse: candidate postId lookup failed: ${e.message}" }
        }

        return emptyList()
    }

    private fun extractHostersFromEpisodeApi(response: Response): List<Hoster> {
        try {
            val pathSegments = response.request.url.pathSegments
            val episodeIndex = pathSegments.indexOf("episodes")
            val episodeSlug =
                if (episodeIndex != -1 && episodeIndex + 1 < pathSegments.size) {
                    pathSegments[episodeIndex + 1]
                } else {
                    null
                }
            val qId =
                response.request.url.queryParameter("_id")
                    ?: response.request.url.queryParameter("postId")

            if (!qId.isNullOrBlank() || !episodeSlug.isNullOrBlank()) {
                val infoUrl =
                    when {
                        !qId.isNullOrBlank() -> "$baseUrl/wp-api/v1/single/episodes?_id=$qId"
                        else -> "$baseUrl/wp-api/v1/single/episodes?slug=$episodeSlug&postType=episodes"
                    }

                val infoRoot = fetchApiJson(infoUrl)
                val hostersFromApi = parseHostersFromEpisodeJson(infoRoot)
                if (hostersFromApi.isNotEmpty()) return hostersFromApi

                val postIdCandidate = infoRoot.findFirstId()
                debugLog { "hosterListParse: resolved postIdCandidate=$postIdCandidate from infoRoot" }

                if (!postIdCandidate.isNullOrBlank()) {
                    return tryPlayerEndpointWithPostId(postIdCandidate)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractHostersFromEpisodeApi failed: ${e.message}")
        }

        return emptyList()
    }

    private fun tryPlayerEndpointWithPostId(postId: String): List<Hoster> {
        try {
            val playerData = fetchApiJson("$baseUrl/wp-api/v1/player?postId=$postId")?.obj("data")
            val playerEmbeds = playerData?.array("embeds")

            if (!playerEmbeds.isNullOrEmpty()) {
                buildHosters(
                    resolveHosterEntries(playerEmbeds.toObjectList(), PLAYER_URL_KEYS, PLAYER_SERVER_KEYS),
                    splitByLanguage = true,
                ).takeIf { it.isNotEmpty() }?.let { return it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryPlayerEndpointWithPostId failed: ${e.message}")
        }

        return emptyList()
    }

    private fun extractHostersFromHtml(body: String): List<Hoster> {
        val document = Jsoup.parse(body)
        val hosters = mutableListOf<Hoster>()

        document.select(".enlaces a, .links a, .player a").forEach { el ->
            val url = el.attr("href")
            val name = el.text().ifBlank { "Enlace" }
            val videos = serverVideoResolver(url, name)
            if (videos.isNotEmpty()) {
                hosters.add(Hoster(hosterName = name, videoList = videos))
            }
        }

        return hosters
    }

    private fun parseHostersFromEpisodeJson(root: JsonObject?): List<Hoster> {
        if (root == null) return emptyList()

        val post = root.obj("data")?.obj("post") ?: root.obj("post") ?: root
        val entries =
            findHosterArrays(post)
                .flatMap { it.toObjectList() }
                .let { resolveHosterEntries(it, EPISODE_URL_KEYS, EPISODE_SERVER_KEYS) }

        return buildHosters(entries, splitByLanguage = false)
    }

    private fun JsonArray?.collectHosters(
        languageTag: String,
        hosterGroups: LinkedHashMap<String, MutableList<Video>>,
    ) {
        val regions = this?.mapNotNull { it.jsonObjectOrNull() } ?: return

        val entries =
            regions.parallelCatchingFlatMapBlocking { region ->
                val serverSlug = region.string("cyberlocker").orEmpty()
                val videos = extractVideosFromRegion(region, languageTag, serverSlug)
                if (videos.isEmpty()) {
                    emptyList()
                } else {
                    listOf(HosterEntry(languageTag, serverSlug, videos))
                }
            }

        entries.forEach { entry ->
            val displayName = displayServerName(entry.serverSlug)
            val key = displayName
            val group = hosterGroups.getOrPut(key) { mutableListOf() }
            group.addAll(entry.videos)
        }
    }

    private fun extractVideosFromRegion(
        region: JsonObject,
        languageTag: String,
        serverSlug: String,
    ): List<Video> {
        val resultUrl = region.string("result").orEmpty()
        if (resultUrl.isBlank()) return emptyList()

        val extractedUrl = resolveEmbeddedUrl(resultUrl)
        if (extractedUrl.isBlank()) return emptyList()

        return serverVideoResolver(extractedUrl, languageTag, serverSlug)
    }

    private fun resolveEmbeddedUrl(resultUrl: String): String {
        resolvedEmbedUrlCache[resultUrl]?.let { return it }

        val extractedUrl =
            runCatching {
                client.newCall(GET(resultUrl)).execute().use { callResponse ->
                    callResponse.asJsoup().select("script").forEach { script ->
                        val data = script.data()
                        if (data.contains("var url = '")) {
                            return@use data
                                .substringAfter("var url = '")
                                .substringBefore("';")
                                .takeIf { it.isNotBlank() }
                                .orEmpty()
                        }
                    }
                    ""
                }
            }.getOrDefault("")

        resolvedEmbedUrlCache[resultUrl] = extractedUrl
        return extractedUrl
    }

    // ================================================================================================
    // HELPER METHODS - VIDEO EXTRACTION BY SERVER
    // ================================================================================================

    private val conventions =
        listOf(
            "voe" to
                listOf(
                    "voe",
                    "voesx",
                    "tubelessceliolymph",
                    "simpulumlamerop",
                    "urochsunloath",
                    "nathanfromsubject",
                    "yip.",
                    "metagnathtuggers",
                    "donaldlineelse",
                    "yip.",
                ),
            "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im", "vimeos"),
            "goodstream" to listOf("goodstream", "gdrm"),
            "okru" to listOf("ok.ru", "okru"),
            "amazon" to listOf("amazon", "amz"),
            "uqload" to listOf("uqload"),
            "mp4upload" to listOf("mp4upload"),
            "streamwish" to
                listOf(
                    "wishembed",
                    "streamwish",
                    "strwish",
                    "wish",
                    "Kswplayer",
                    "Swhoi",
                    "Multimovies",
                    "Uqloads",
                    "neko-stream",
                    "swdyu",
                    "iplayerhls",
                    "streamgg",
                ),
            "doodstream" to
                listOf(
                    "doodstream",
                    "dood.",
                    "ds2play",
                    "doods.",
                    "ds2play",
                    "ds2video",
                    "dooood",
                    "d000d",
                    "d0000d",
                ),
            "streamlare" to listOf("streamlare", "slmaxed"),
            "yourupload" to listOf("yourupload", "upload"),
            "burstcloud" to listOf("burstcloud", "burst"),
            "fastream" to listOf("fastream"),
            "upstream" to listOf("upstream"),
            "streamsilk" to listOf("streamsilk"),
            "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
            "vidhide" to
                listOf(
                    "ahvsh",
                    "streamhide",
                    "guccihide",
                    "streamvid",
                    "vidhide",
                    "kinoger",
                    "smoothpre",
                    "dhtpre",
                    "peytonepre",
                    "earnvids",
                    "ryderjet",
                ),
            "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "bembed"),
        )

    private fun extractVideosByServer(
        matched: String,
        url: String,
        prefixBase: String,
        prefixWithSpace: String,
    ): List<Video> {
        return when (matched) {
            "voe" -> {
                val vids = voeExtractor.videosFromUrl(url, prefixWithSpace)
                debugLog { "serverVideoResolver: voeExtractor returned ${vids.size} videos for url=$url" }
                if (vids.isNotEmpty()) return vids

                // Fallback to universal extractor
                val fallback = universalExtractor.videosFromUrl(url, headers, prefix = prefixWithSpace)
                debugLog {
                    "serverVideoResolver: voeExtractor empty, universalExtractor fallback returned ${fallback.size} videos for url=$url"
                }
                fallback
            }

            "goodstream" -> {
                val vids = goodStreamExtractor.videosFromUrl(url, prefixWithSpace)
                debugLog { "serverVideoResolver: goodStreamExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "okru" -> {
                val vids = okruExtractor.videosFromUrl(url, prefixWithSpace)
                debugLog { "serverVideoResolver: okruExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "filemoon" -> {
                val vids = filemoonExtractor.videosFromUrl(url, prefix = prefixWithSpace)
                debugLog { "serverVideoResolver: filemoonExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "amazon" -> {
                extractAmazonVideos(url, prefixBase)
            }

            "uqload" -> {
                val vids = uqloadExtractor.videosFromUrl(url, prefixWithSpace)
                debugLog { "serverVideoResolver: uqloadExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "mp4upload" -> {
                val vids = mp4uploadExtractor.videosFromUrl(url, headers, prefix = prefixWithSpace)
                debugLog { "serverVideoResolver: mp4uploadExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "streamwish" -> {
                val vids =
                    streamWishExtractor.videosFromUrl(url, videoNameGen = { quality ->
                        buildVideoName(prefixBase, quality)
                    })
                debugLog { "serverVideoResolver: streamWishExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "doodstream" -> {
                val vids = doodExtractor.videosFromUrl(url, prefixWithSpace)
                debugLog { "serverVideoResolver: doodExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "streamlare" -> {
                val vids = streamlareExtractor.videosFromUrl(url, prefixWithSpace)
                debugLog { "serverVideoResolver: streamlareExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "yourupload" -> {
                val vids = yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = prefixWithSpace)
                debugLog { "serverVideoResolver: yourUploadExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "burstcloud" -> {
                val vids = burstCloudExtractor.videoFromUrl(url, headers = headers, prefix = prefixWithSpace)
                debugLog { "serverVideoResolver: burstCloudExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "fastream" -> {
                val vids = fastreamExtractor.videosFromUrl(url, prefix = prefixWithSpace)
                debugLog { "serverVideoResolver: fastreamExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "upstream" -> {
                val vids = upstreamExtractor.videosFromUrl(url, prefix = prefixWithSpace)
                debugLog { "serverVideoResolver: upstreamExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "streamsilk" -> {
                val vids =
                    streamSilkExtractor.videosFromUrl(url, videoNameGen = { quality ->
                        buildVideoName(prefixBase, quality)
                    })
                debugLog { "serverVideoResolver: streamSilkExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "streamtape" -> {
                val vids = streamTapeExtractor.videosFromUrl(url, quality = prefixBase)
                debugLog { "serverVideoResolver: streamTapeExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "vidhide" -> {
                val vids =
                    vidHideExtractor.videosFromUrl(url, videoNameGen = { quality ->
                        buildVideoName(prefixBase, quality)
                    })
                debugLog { "serverVideoResolver: vidHideExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            "vidguard" -> {
                val vids = vidGuardExtractor.videosFromUrl(url, prefix = prefixWithSpace)
                debugLog { "serverVideoResolver: vidGuardExtractor returned ${vids.size} videos for url=$url" }
                vids
            }

            else -> {
                val vids = universalExtractor.videosFromUrl(url, headers, prefix = prefixWithSpace)
                debugLog { "serverVideoResolver: universalExtractor returned ${vids.size} videos for url=$url (matched=$matched)" }
                vids
            }
        }
    }

    private fun extractAmazonVideos(
        url: String,
        prefixBase: String,
    ): List<Video> =
        runCatching {
            val body = client.newCall(GET(url)).execute().asJsoup()
            if (body.select("script:containsData(var shareId)").toString().isNotBlank()) {
                val shareId =
                    body
                        .selectFirst("script:containsData(var shareId)")!!
                        .data()
                        .substringAfter("shareId = \"")
                        .substringBefore("\"")

                val amazonApiJson =
                    client
                        .newCall(
                            GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"),
                        ).execute()
                        .asJsoup()

                val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
                val amazonApi =
                    client
                        .newCall(
                            GET(
                                "https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId",
                            ),
                        ).execute()
                        .asJsoup()

                val videoUrl =
                    amazonApi
                        .toString()
                        .substringAfter("\"FOLDER\":")
                        .substringAfter("tempLink:\"")
                        .substringBefore("\"")

                listOf(Video(videoUrl = videoUrl, videoTitle = buildVideoName(prefixBase, "Amazon")))
            } else {
                emptyList()
            }
        }.getOrNull() ?: emptyList()

    // ================================================================================================
    // HELPER METHODS - SERVER DETECTION
    // ================================================================================================

    private fun canonicalServerSlug(serverSlug: String): String {
        val lower = serverSlug.lowercase()
        canonicalServerCache[lower]?.let { return it }

        val canonical =
            conventions
                .firstOrNull { (key, names) ->
                    key.equals(lower, true) ||
                        lower.contains(key, ignoreCase = true) ||
                        names.any { name ->
                            name.equals(lower, true) || lower.contains(name, true)
                        }
                }?.first ?: lower

        canonicalServerCache[lower] = canonical
        return canonical
    }

    private fun displayServerName(serverSlug: String): String {
        val canonical = canonicalServerSlug(serverSlug)
        if (canonical.isBlank()) return "Unknown"

        return SERVER_DISPLAY_NAMES[canonical] ?: canonical.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }

    // ================================================================================================
    // HELPER METHODS - DATA MODELS
    // ================================================================================================

    private data class HosterEntry(
        val languageTag: String,
        val serverSlug: String,
        val videos: List<Video>,
    )

    private data class GnulaMeta(
        val title: String,
        val overview: String?,
        val poster: String?,
        val genres: List<String>,
        val director: String?,
        val cast: List<String>,
        val seasons: List<GnulaSeason>,
        val isMovie: Boolean,
    )

    private data class GnulaSeason(
        val number: Int,
        val title: String?,
        val overview: String?,
        val slugName: String?,
        val slugSeason: String?,
        val episodes: List<GnulaEpisode>,
    )

    private data class GnulaEpisode(
        val season: Int,
        val number: Int,
        val title: String?,
        val overview: String?,
        val image: String?,
        val releaseDate: String?,
        val slugName: String,
        val slugSeason: String,
        val slugEpisode: String,
    )

    private data class GnulaEpisodeBundle(
        val seasonNumber: Int,
        val episode: GnulaEpisode,
    )

    // ================================================================================================
    // HELPER METHODS - JSON CONVERSIONS
    // ================================================================================================

    private fun Response.extractPageProps(): JsonObject? {
        val cacheKey = request.url.toString()
        if (pagePropsCache.containsKey(cacheKey)) return pagePropsCache[cacheKey]

        val document = asJsoup()

        // Search for embedded script with pageProps
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data()
        if (jsonString != null) {
            val pageProps = parseJsonObject(jsonString)?.obj("props")?.obj("pageProps")
            pagePropsCache[cacheKey] = pageProps
            return pageProps
        }

        // Fallback: search for window.siteConfig
        val siteConfigScript =
            document
                .select("script")
                .firstOrNull { it.data().contains("window.siteConfig") }
                ?.data()
        if (siteConfigScript != null) {
            val jsonStart = siteConfigScript.indexOf("window.siteConfig = ")
            if (jsonStart != -1) {
                val jsonRaw = siteConfigScript.substring(jsonStart + 19).substringBefore(";").trim()
                val siteConfig = parseJsonObject(jsonRaw)
                pagePropsCache[cacheKey] = siteConfig
                return siteConfig
            }
        }

        pagePropsCache[cacheKey] = null
        return null
    }

    private fun JsonObject.toGnulaMeta(): GnulaMeta? {
        val post = obj("post") ?: return null
        val title = post.obj("titles")?.string("name") ?: return null
        val overview = post.string("overview")
        val poster = post.obj("images")?.string("poster")?.optimizeImageUrl()

        val genres =
            post.array("genres")?.mapNotNull { element ->
                element.jsonObjectOrNull()?.string("name")?.takeIf { it.isNotBlank() }
            } ?: emptyList()

        val director = post.string("director")
        val cast =
            post.obj("cast")?.array("acting")?.mapNotNull { element ->
                element.jsonObjectOrNull()?.string("name")?.takeIf { it.isNotBlank() }
            } ?: emptyList()

        val seasons =
            post.array("seasons")?.mapNotNull { element ->
                when (element) {
                    is JsonObject -> {
                        element.toGnulaSeason(overview)
                    }

                    is JsonPrimitive -> {
                        val txt = element.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                        val num = txt.toIntOrNull() ?: return@mapNotNull null
                        GnulaSeason(
                            number = num,
                            title = null,
                            overview = overview,
                            slugName = null,
                            slugSeason = num.toString(),
                            episodes = emptyList(),
                        )
                    }

                    else -> {
                        null
                    }
                }
            } ?: emptyList()

        val isMovie = post.string("type")?.equals("movie", true) == true || seasons.isEmpty()

        return GnulaMeta(
            title = title,
            overview = overview,
            poster = poster,
            genres = genres,
            director = director,
            cast = cast,
            seasons = seasons,
            isMovie = isMovie,
        )
    }

    private fun JsonObject.toGnulaSeason(seriesOverview: String?): GnulaSeason? {
        val number = long("number")?.toInt() ?: return null
        val title = string("title")
        val overview = string("overview") ?: seriesOverview
        val slug = obj("slug")
        val slugName = slug?.string("name")
        val slugSeason = slug?.string("season")

        val episodes =
            array("episodes")?.mapIndexedNotNull { index, element ->
                element.jsonObjectOrNull()?.toGnulaEpisode(number, index + 1)
            } ?: emptyList()

        return GnulaSeason(
            number = number,
            title = title,
            overview = overview,
            slugName = slugName,
            slugSeason = slugSeason,
            episodes = episodes,
        )
    }

    private fun JsonObject.toGnulaEpisode(
        seasonNumber: Int,
        fallbackNumber: Int,
    ): GnulaEpisode? {
        val slug = obj("slug") ?: return null
        val slugName = slug.string("name") ?: return null
        val slugSeason = slug.string("season") ?: seasonNumber.toString()
        val slugEpisode = slug.string("episode") ?: return null

        val episodeNumber =
            long("number")?.toInt()
                ?: slugEpisode.filter(Char::isDigit).toIntOrNull()
                ?: fallbackNumber

        val title = string("title")
        val overview = string("overview") ?: string("description")
        val image = resolveEpisodeImage(string("image")?.optimizeImageUrl())
        val releaseDate = string("releaseDate")

        return GnulaEpisode(
            season = seasonNumber,
            number = episodeNumber,
            title = title,
            overview = overview,
            image = image,
            releaseDate = releaseDate,
            slugName = slugName,
            slugSeason = slugSeason,
            slugEpisode = slugEpisode,
        )
    }

    private fun GnulaSeason.toSAnime(
        baseUrlPath: String,
        meta: GnulaMeta,
        sourceBaseUrl: String,
    ): SAnime {
        val normalizedNumber =
            slugSeason?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
                ?: number.takeIf { it > 0 }
                ?: 1

        val resolvedTitle =
            title?.takeIf { it.isNotBlank() }
                ?: when {
                    meta.title.isNotBlank() -> "${meta.title} - Temporada $normalizedNumber"
                    else -> "Temporada $normalizedNumber"
                }

        val seasonDescription = overview ?: meta.overview

        return SAnime.create().apply {
            title = resolvedTitle
            seasonDescription?.takeIf { it.isNotBlank() }?.let { description = it }
            thumbnail_url = resolvePosterUrl(meta.poster)
            fetch_type = FetchType.Episodes
            season_number = normalizedNumber.toDouble()

            meta.genres
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?.let { genre = it }
            meta.director?.takeIf { it.isNotBlank() }?.let { author = it }
            meta.cast
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { artist = it }
            status = if (meta.isMovie) SAnime.COMPLETED else SAnime.UNKNOWN

            setUrlWithoutDomain(buildSeasonUrl(sourceBaseUrl, baseUrlPath, normalizedNumber))
        }
    }

    private fun GnulaMeta.toEpisodeList(selectedSeason: Int?): List<SEpisode> {
        val seasonsToUse = seasons.filter { selectedSeason == null || it.number == selectedSeason }
        if (seasonsToUse.isEmpty()) return emptyList()

        val singleSeason = seasonsToUse.size == 1

        return seasonsToUse
            .flatMap { season ->
                season.episodes.map { episode ->
                    GnulaEpisodeBundle(season.number, episode)
                }
            }.sortedWith(compareBy({ it.seasonNumber }, { it.episode.number }))
            .reversed()
            .map { bundle ->
                val episode = bundle.episode
                val episodeNumberValue =
                    if (singleSeason) {
                        episode.number.toFloat()
                    } else {
                        buildCombinedEpisodeNumber(bundle.seasonNumber, episode.number)
                    }

                SEpisode.create().apply {
                    episode_number = episodeNumberValue
                    name = buildEpisodeName(bundle.seasonNumber, episode.number, episode.title)
                    summary = episode.overview
                    preview_url = episode.image
                    date_upload = episode.releaseDate?.toDate() ?: 0L
                    setUrlWithoutDomain("/series/${episode.slugName}/seasons/${episode.slugSeason}/episodes/${episode.slugEpisode}")
                }
            }
    }

    // ================================================================================================
    // HELPER METHODS - URL & IMAGE RESOLUTION
    // ================================================================================================

    private fun resolvePosterUrl(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        return when {
            poster.contains("/thumbs/") -> "$baseUrl/wp-content/uploads$poster"
            poster.startsWith("/") -> "$baseUrl$poster"
            poster.contains("hackstore.mx") -> poster
            else -> poster
        }
    }

    private fun resolveEpisodeImage(path: String?): String? {
        if (path.isNullOrBlank()) return null

        return when {
            path.startsWith("http", true) -> {
                path
            }

            TMDB_IMAGE_REGEX.matches(path) && !path.contains("wp-content/uploads") && !path.contains("hackstore.mx") -> {
                val clean = path.trimStart('/')
                "https://image.tmdb.org/t/p/w500/$clean"
            }

            path.startsWith("/") -> {
                "$baseUrl/wp-content/uploads$path"
            }

            path.contains("wp-content/uploads") -> {
                path
            }

            path.contains("hackstore.mx") -> {
                path
            }

            else -> {
                "$baseUrl/wp-content/uploads/$path"
            }
        }
    }

    private fun String.toAbsoluteUrl(): String =
        if (startsWith("http", true)) {
            this
        } else {
            val separator = if (startsWith("/")) "" else "/"
            "$baseUrl$separator$this"
        }

    private fun buildSeasonUrl(
        sourceBaseUrl: String,
        baseUrlPath: String,
        seasonNumber: Int,
    ): String {
        val normalizedPath =
            when {
                baseUrlPath.startsWith("http", true) -> baseUrlPath.removePrefix(sourceBaseUrl)
                baseUrlPath.startsWith("/") -> baseUrlPath
                baseUrlPath.isBlank() -> "/"
                else -> "/${baseUrlPath.trimStart('/')}"
            }
        val hasQuery = normalizedPath.contains('?')
        val separator = if (hasQuery) '&' else '?'
        return "$normalizedPath${separator}season=$seasonNumber"
    }

    // ================================================================================================
    // HELPER METHODS - STRING UTILITIES
    // ================================================================================================

    private fun buildEpisodeName(
        season: Int,
        episode: Int,
        title: String?,
    ): String {
        val label = title?.takeIf { it.isNotBlank() }
        return buildString {
            append("E")
            append(episode)
            label?.let {
                append(" - ")
                append(it)
            }
            append(" (T")
            append(season)
            append(")")
        }
    }

    private fun buildCombinedEpisodeNumber(
        season: Int,
        episode: Int,
    ): Float = (season * 1000 + episode).toFloat()

    private fun buildPrefix(
        languageTag: String,
        serverName: String,
    ): String =
        sequenceOf(languageTag, serverName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun String.withTrailingSpace(): String = if (isBlank()) "" else "$this "

    private fun buildVideoName(
        prefix: String,
        detail: String,
    ): String =
        when {
            prefix.isBlank() -> detail.trim()
            detail.isBlank() -> prefix.trim()
            else -> "$prefix ${detail.trim()}"
        }

    private fun String.optimizeImageUrl(): String =
        if (contains("/original/", ignoreCase = true)) {
            replace("/original/", "/w400/")
        } else {
            this
        }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L

    // ================================================================================================
    // HELPER METHODS - JSON EXTENSIONS
    // ================================================================================================

    private fun JsonObject.string(key: String): String? = get(key).stringValue()

    private fun JsonObject.long(key: String): Long? = get(key).stringValue()?.toLongOrNull()

    private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.firstString(vararg keys: String): String? {
        keys.forEach { key ->
            string(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun JsonElement?.findFirstId(): String? {
        if (this == null) return null

        val stack = ArrayDeque<JsonElement>()
        stack.add(this)

        while (stack.isNotEmpty()) {
            when (val current = stack.removeLast()) {
                is JsonObject -> {
                    current.firstString("_id", "id")?.let { return it }
                    current.values.forEach(stack::addLast)
                }

                is JsonArray -> {
                    current.forEach(stack::addLast)
                }

                else -> {
                    Unit
                }
            }
        }

        return null
    }

    private fun JsonArray.toObjectList(): List<JsonObject> = mapNotNull { it.jsonObjectOrNull() }

    private fun parseJsonObject(body: String?): JsonObject? =
        body
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun fetchApiJson(url: String): JsonObject? {
        if (apiJsonCache.containsKey(url)) return apiJsonCache[url]

        val root =
            runCatching {
                client.newCall(GET(url, headers)).execute().use { response ->
                    parseJsonObject(response.body.string())
                }
            }.getOrNull()

        apiJsonCache[url] = root
        return root
    }

    private fun detectIsSeries(item: JsonObject): Boolean {
        val candidates =
            listOf(
                item.firstString("type", "postType", "post_type"),
                item.obj("data")?.firstString("type"),
                item.obj("post")?.firstString("type"),
            ).mapNotNull { it?.lowercase() }

        candidates.forEach { value ->
            when {
                value.contains("tv") ||
                    value.contains("serie") ||
                    value.contains("series") ||
                    value.contains("tvshow") ||
                    value.contains("tvshows") -> return true

                value.contains("movie") || value.contains("movies") -> return false
            }
        }

        return false
    }

    private fun findHosterArrays(element: JsonElement): List<JsonArray> {
        val arrays = mutableListOf<JsonArray>()
        val stack = ArrayDeque<JsonElement>()
        stack.add(element)

        while (stack.isNotEmpty()) {
            when (val current = stack.removeLast()) {
                is JsonObject -> {
                    current.values.forEach(stack::addLast)
                }

                is JsonArray -> {
                    val objects = current.toObjectList()
                    if (objects.isNotEmpty() && objects.any { obj -> HOSTER_ARRAY_KEYS.any { key -> obj.containsKey(key) } }) {
                        arrays.add(current)
                    } else {
                        current.forEach(stack::addLast)
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        return arrays
    }

    private fun resolveHosterEntries(
        items: List<JsonObject>,
        urlKeys: Array<String>,
        serverKeys: Array<String>,
    ): List<HosterEntry> =
        items.parallelCatchingFlatMapBlocking { item ->
            val url = item.firstString(*urlKeys) ?: return@parallelCatchingFlatMapBlocking emptyList()
            val serverSlug = item.firstString(*serverKeys).orEmpty()
            val languageTag = item.firstString("lang", "language").orEmpty()
            val videos = serverVideoResolver(url, languageTag, serverSlug)

            if (videos.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    HosterEntry(
                        languageTag = languageTag,
                        serverSlug = serverSlug.ifBlank { url },
                        videos = videos,
                    ),
                )
            }
        }

    private fun buildHosters(
        entries: List<HosterEntry>,
        splitByLanguage: Boolean,
    ): List<Hoster> {
        if (entries.isEmpty()) return emptyList()

        val hosterMap = LinkedHashMap<String, MutableList<Video>>()
        entries.forEach { entry ->
            val serverDisplay = displayServerName(entry.serverSlug)
            val hosterName =
                if (splitByLanguage) {
                    buildPrefix(entry.languageTag, serverDisplay).ifBlank { serverDisplay }
                } else {
                    serverDisplay
                }.ifBlank { "Enlace" }

            hosterMap.getOrPut(hosterName) { mutableListOf() }.addAll(entry.videos)
        }

        return hosterMap.map { (name, videos) ->
            Hoster(hosterName = name, videoList = videos)
        }
    }

    private inline fun debugLog(message: () -> String) {
        if (DEBUG_LOGS) Log.d(TAG, message())
    }

    private fun <K, V> lruCache(maxSize: Int): MutableMap<K, V> =
        Collections.synchronizedMap(
            object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
            },
        )

    // ================================================================================================
    // HELPER METHODS - PREFERENCES
    // ================================================================================================

    private fun prefersSeasonFetch(): Boolean = preferences.splitSeasons

    private fun preferredFetchType(isSeries: Boolean): FetchType =
        if (isSeries && prefersSeasonFetch()) FetchType.Seasons else FetchType.Episodes
}

// ================================================================================================
// PREFERENCE EXTENSIONS
// ================================================================================================

private var SharedPreferences.splitSeasons: Boolean
    get() {
        if (contains(Hackstoremx.PREF_SPLIT_SEASONS_KEY)) {
            return getBoolean(Hackstoremx.PREF_SPLIT_SEASONS_KEY, true)
        }

        val legacy = getString(Hackstoremx.LEGACY_PREF_FETCH_TYPE_KEY, null)
        val migrated = legacy?.equals(Hackstoremx.LEGACY_FETCH_TYPE_SEASONS, ignoreCase = true) ?: false

        if (legacy != null) {
            edit()
                .putBoolean(Hackstoremx.PREF_SPLIT_SEASONS_KEY, migrated)
                .remove(Hackstoremx.LEGACY_PREF_FETCH_TYPE_KEY)
                .apply()
        }

        return legacy?.let { migrated } ?: true
    }
    set(value) {
        edit()
            .putBoolean(Hackstoremx.PREF_SPLIT_SEASONS_KEY, value)
            .remove(Hackstoremx.LEGACY_PREF_FETCH_TYPE_KEY)
            .apply()
    }
