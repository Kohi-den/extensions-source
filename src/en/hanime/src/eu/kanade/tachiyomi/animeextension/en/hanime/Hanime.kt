package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class Hanime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "hanime.tv"

    override val baseUrl = "https://hanime.tv"

    /** Default CDN base URL for manifest and search API requests. */
    private val defaultCdnBaseUrl = DEFAULT_CDN_BASE_URL

    /** CDN base URL — uses custom domain if set and valid, otherwise the default. */
    private val cdnBaseUrl: String
        get() = preferences.getString(PREF_CUSTOM_CDN_KEY, PREF_CUSTOM_CDN_DEFAULT)
            ?.takeIf { it.isNotBlank() && it.toHttpUrlOrNull() != null }
            ?: defaultCdnBaseUrl

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
        .add("Accept", "application/json")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("content-type", "application/json")
        .add("Origin", "https://hanime.tv")
        .add("Referer", "https://hanime.tv/")
        .add("sec-ch-ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Android\"")

    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://player.hanime.tv/")
        .set("Origin", "https://player.hanime.tv")
        .build()

    /** Headers for video stream requests (m3u8, segments, AES key). */
    private fun playerVideoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://player.hanime.tv/")
        .set("Origin", "https://player.hanime.tv")
        .build()

    @Volatile
    private var authCookie: String? = null

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    private val context: Application by injectLazy()

    @Volatile
    private var signatureProvider: SignatureProvider? = null

    @Volatile
    private var signatureProviderMode: String? = null
    private val signatureProviderMutex = Mutex()

    private suspend fun ensureSignatureProvider(): SignatureProvider {
        // Fast path: check if provider exists and mode hasn't changed
        val currentProvider = signatureProvider
        val currentMode = preferences.getString(PREF_SIG_PROVIDER_KEY, PREF_SIG_PROVIDER_DEFAULT)!!
        if (currentProvider != null && currentMode == signatureProviderMode) {
            return currentProvider
        }

        // Slow path: acquire lock and re-read preference inside the lock
        return signatureProviderMutex.withLock {
            val mode = preferences.getString(PREF_SIG_PROVIDER_KEY, PREF_SIG_PROVIDER_DEFAULT)!!
            val lockedProvider = signatureProvider
            if (lockedProvider != null && signatureProviderMode == mode) {
                lockedProvider
            } else {
                val existing = signatureProvider
                val newProvider = createSignatureProvider(mode)
                Log.d(TAG, "Signature provider created: ${newProvider.javaClass.simpleName}")
                signatureProvider = newProvider
                signatureProviderMode = mode
                existing?.close()
                newProvider
            }
        }
    }

    private suspend fun createSignatureProvider(mode: String?): SignatureProvider = when (mode) {
        "native" -> NativeSignatureProvider()
        "webview" -> WebViewSignatureProvider()
        "wasm" -> {
            val binary = runCatching {
                withContext(Dispatchers.IO) { HanimeWasmBinary.fetchWasmBinary(client) }
            }.getOrNull()
            if (binary != null) {
                ChicorySignatureProvider(binary)
            } else {
                Log.w(TAG, "WASM binary fetch failed — falling back to WebView provider")
                WebViewSignatureProvider()
            }
        }
        else -> {
            Log.w(TAG, "Unknown signature provider mode '$mode', falling back to WebViewSignatureProvider")
            WebViewSignatureProvider()
        }
    }

    // ── Search API (v10 GET endpoint) ──────────────────────────────────

    /** Cached full search response for pagination and client-side filtering. */
    @Volatile
    private var cachedSearchHits: List<HitsModel>? = null

    /** Timestamp of when [cachedSearchHits] was last fetched. */
    @Volatile
    private var cachedSearchHitsTimestamp: Long = 0L

    /** Maximum age of cached search hits before refetching (based on preference, default 10 minutes). */
    private val searchHitsTtlMs: Long
        get() = preferences.getString(PREF_CACHE_TTL_KEY, PREF_CACHE_TTL_DEFAULT)
            ?.toLongOrNull()?.times(60 * 1000L)
            ?: (10L * 60 * 1000L)

    /** Mutex to prevent concurrent search cache refreshes. */
    private val searchCacheMutex = Mutex()

    /**
     * Fetch or return cached search results from the v10 search API.
     * The API returns all content in a single response — pagination and
     * filtering are handled client-side.
     */
    private suspend fun fetchSearchHits(): List<HitsModel> {
        val ttlMs = searchHitsTtlMs

        // Fast path: check cache without lock
        val now = System.currentTimeMillis()
        val cached = cachedSearchHits
        if (cached != null && now - cachedSearchHitsTimestamp < ttlMs) {
            return cached
        }

        // Slow path: acquire lock to prevent redundant fetches
        return searchCacheMutex.withLock {
            // Re-check cache after acquiring lock (another thread may have fetched)
            val nowLocked = System.currentTimeMillis()
            val cachedLocked = cachedSearchHits
            if (cachedLocked != null && nowLocked - cachedSearchHitsTimestamp < ttlMs) {
                cachedLocked
            } else {
                val signature = ensureSignatureProvider().getSignature()
                val searchHeaders = headers.newBuilder().apply {
                    SignatureHeaders.build(signature).forEach { (key, value) ->
                        add(key, value)
                    }
                }.build()

                val response = client.newCall(GET("$cdnBaseUrl/api/v10/search_hvs", searchHeaders)).await()
                val result = response.use { resp ->
                    val jsonLine = resp.body.string()
                    if (jsonLine.isEmpty()) {
                        Log.w(TAG, "fetchSearchHits() — search API returned empty body")
                        emptyList()
                    } else {
                        json.decodeFromString<List<HitsModel>>(jsonLine)
                    }
                }
                cachedSearchHits = result
                cachedSearchHitsTimestamp = System.currentTimeMillis()
                result
            }
        }
    }

    // ── Popular Anime ──────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val allHits = fetchSearchHits()
        return paginateHits(allHits, page, orderBy = "likes", ordering = "desc")
    }

    // ── Search Anime ───────────────────────────────────────────────────

    private data class SearchParameters(
        val includedTags: List<String>,
        val blackListedTags: List<String>,
        val brands: List<String>,
        val tagsMode: String,
        val orderBy: String,
        val ordering: String,
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(baseUrl, headers)

    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val (includedTags, blackListedTags, brands, tagsMode, orderBy, ordering) = getSearchParameters(filters)
        val allHits = fetchSearchHits()
        return paginateHits(
            hits = allHits,
            page = page,
            query = query,
            includedTags = includedTags,
            blackListedTags = blackListedTags,
            brands = brands,
            tagsMode = tagsMode,
            orderBy = orderBy,
            ordering = ordering,
        )
    }

    // ── Latest Updates ─────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val allHits = fetchSearchHits()
        return paginateHits(allHits, page, orderBy = "created_at_unix", ordering = "desc")
    }

    // ── Hit parsing & pagination ───────────────────────────────────────

    private fun parseHitsToAnimeList(hits: List<HitsModel>): List<SAnime> = hits.groupBy { getTitle(it.name) }.map { (_, items) -> items.first() }.map { item ->
        SAnime.create().apply {
            title = getTitle(item.name)
            thumbnail_url = item.coverUrl
            author = item.brand
            description = item.description?.replace(Regex("<[^>]*>"), "")
            status = SAnime.UNKNOWN
            genre = item.tags.joinToString { it }
            initialized = true
            setUrlWithoutDomain("https://hanime.tv/videos/hentai/" + item.slug)
        }
    }

    private val pageSize = 24

    /**
     * Paginate and sort the full hit list for a given page number.
     * The v10 search API returns all content in one response, so
     * pagination is handled client-side.
     */
    private fun paginateHits(
        hits: List<HitsModel>,
        page: Int,
        query: String = "",
        includedTags: List<String> = emptyList(),
        blackListedTags: List<String> = emptyList(),
        brands: List<String> = emptyList(),
        tagsMode: String = "OR",
        orderBy: String = "likes",
        ordering: String = "desc",
    ): AnimesPage {
        var filtered = hits

        // Apply text search filter
        if (query.isNotEmpty()) {
            val lowerQuery = query.lowercase(Locale.US)
            filtered = filtered.filter { hit ->
                hit.name.lowercase(Locale.US).contains(lowerQuery) ||
                    hit.tags.any { tag -> tag.lowercase(Locale.US).contains(lowerQuery) } ||
                    (hit.brand?.lowercase(Locale.US)?.contains(lowerQuery) == true)
            }
        }

        // Apply tag inclusion filter
        if (includedTags.isNotEmpty()) {
            val lowerTags = includedTags.map { it.lowercase(Locale.US) }
            val isAndMode = tagsMode.equals("and", ignoreCase = true)
            filtered = filtered.filter { hit ->
                if (isAndMode) {
                    lowerTags.all { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
                } else {
                    lowerTags.any { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
                }
            }
        }

        // Apply tag blacklist filter
        if (blackListedTags.isNotEmpty()) {
            val lowerBlacklist = blackListedTags.map { it.lowercase(Locale.US) }
            filtered = filtered.filterNot { hit ->
                lowerBlacklist.any { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
            }
        }

        // Censored content filter
        val censoredFilter = preferences.getString(PREF_CENSORED_KEY, PREF_CENSORED_DEFAULT) ?: PREF_CENSORED_DEFAULT
        filtered = when (censoredFilter) {
            "uncensored" -> filtered.filter { it.isCensored != true } // != true includes null/unknown items as potentially uncensored
            "censored" -> filtered.filter { it.isCensored == true }
            else -> filtered
        }

        // Apply brand filter
        if (brands.isNotEmpty()) {
            val lowerBrands = brands.map { it.lowercase(Locale.US) }
            filtered = filtered.filter { hit ->
                hit.brand?.lowercase(Locale.US) in lowerBrands
            }
        }

        // Apply sorting
        val comparator = when (orderBy) {
            "views" -> compareByDescending<HitsModel> { it.views ?: 0L }
            "likes" -> compareByDescending<HitsModel> { it.likes ?: 0L }
            "created_at_unix", "published_at_unix" -> compareByDescending<HitsModel> { it.createdAtUnix ?: 0L }
            "released_at_unix" -> compareByDescending<HitsModel> { it.releasedAtUnix ?: 0L }
            "title_sortable" -> compareBy<HitsModel> { it.name.lowercase(Locale.US) }
            else -> compareByDescending<HitsModel> { it.likes ?: 0L }
        }
        val sorted = if (ordering == "asc") filtered.sortedWith { a, b -> comparator.compare(b, a) } else filtered.sortedWith(comparator)

        // Paginate
        val fromIndex = (page - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, sorted.size)
        val pageItems = if (fromIndex < sorted.size) sorted.subList(fromIndex, toIndex) else emptyList()
        val hasNextPage = toIndex < sorted.size

        return AnimesPage(parseHitsToAnimeList(pageItems), hasNextPage)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun getTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.contains(" Ep ")) {
            return trimmed.split(" Ep ")[0].trim()
        }
        // Only strip trailing number if it's a standalone episode number
        // (1-3 digits at the end, preceded by a space, NOT preceded by 'x' connector)
        val episodeSuffixRegex = Regex("""\s(\d{1,3})$""")
        val match = episodeSuffixRegex.find(trimmed)
        return if (match != null) {
            val beforeNumber = trimmed.substring(0, match.range.first)
            // Don't strip if the number is part of a compound like "x 3" or "- 3"
            val prefixRegex = Regex("""[xX\-–]$""")
            if (!prefixRegex.containsMatchIn(beforeNumber)) {
                beforeNumber.trim()
            } else {
                trimmed
            }
        } else {
            trimmed
        }
    }

    // ── Anime Details ──────────────────────────────────────────────────

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = getTitle(document.select("h1.tv-title").text())
            thumbnail_url = document.selectFirst("img.hvpi-cover")?.attr("src")
            author = document.selectFirst("a.hvpimbc-text")?.text() ?: ""
            description = document.select("div.hvpist-description p").joinToString("\n\n") { it.text() }
            status = SAnime.UNKNOWN
            genre = document.select("div.hvpis-text div.btn__content").joinToString { it.text() }
            initialized = true
            setUrlWithoutDomain(document.location())
        }
    }

    // ── Video List ─────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode) = GET(episode.url)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        setAuthCookie()
        return if (authCookie != null) {
            fetchVideoListPremium(episode)
        } else {
            fetchVideoListWithSignature(episode)
        }
    }

    /**
     * Fetch video list using the manifest endpoint with WASM-generated signature headers.
     *
     * Flow:
     * 1. Call /api/v8/video?id={slug} to get the numeric hvId
     * 2. Call the guest manifest endpoint with signature headers for real stream URLs
     * 3. Use PlaylistUtils to parse m3u8 playlists into properly-headed Video objects
     * 4. Fall back to decoy streams from /api/v8/video if manifest fails
     */
    private suspend fun fetchVideoListWithSignature(episode: SEpisode): List<Video> {
        // If hvid is embedded in the episode URL, skip the /api/v8/video call
        val directHvId = extractHvIdFromUrl(episode.url)
        if (directHvId != null) {
            try {
                val manifestStreams = fetchManifestVideos(directHvId, retryOnAuthFailure = true)
                if (manifestStreams.isNotEmpty()) return manifestStreams
            } catch (e: Exception) {
                Log.w(TAG, "fetchVideoListWithSignature() — directHvId manifest failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Fallback: resolve hvId via /api/v8/video (backward compatibility for single-episode URLs)
        val slug = extractSlugFromUrl(episode.url)

        val videoString = client.newCall(GET("$baseUrl/api/v8/video?id=$slug", headers)).await().use {
            it.body.string()
        }
        if (videoString.isEmpty()) return emptyList()

        val videoModel = json.decodeFromString<VideoModel>(videoString)
        val hvId = videoModel.hentaiVideo?.id
            ?: videoModel.videosManifest?.servers?.firstOrNull()?.streams?.firstOrNull()?.hvId

        if (hvId != null) {
            try {
                val manifestStreams = fetchManifestVideos(hvId, retryOnAuthFailure = true)
                if (manifestStreams.isNotEmpty()) return manifestStreams
            } catch (e: Exception) {
                Log.w(TAG, "fetchVideoListWithSignature() — API hvId manifest failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Final fallback: parse manifest streams from API response without guest filter
        return parseVideoModelStreamsUnfiltered(videoModel)
    }

    /**
     * Parse manifest streams from a VideoModel without the isGuestAllowed filter.
     * Unlike [parseManifestStreams] which requires isGuestAllowed == true, this method
     * includes all streams except premium_alert kinds, making it effective as a fallback
     * when the CDN manifest endpoint fails (e.g. for non-premium multi-episode content).
     */
    private suspend fun parseVideoModelStreamsUnfiltered(videoModel: VideoModel): List<Video> {
        // Note: Premium filter not applied here — fallback path intentionally includes all available streams for reliability
        val servers = videoModel.videosManifest?.servers ?: return emptyList()
        val playerHeaders = playerVideoHeaders()

        return servers.flatMap { server ->
            val filtered = server.streams.filter { it.kind != "premium_alert" && it.url.contains(".m3u8") }
            filtered.flatMap { stream ->
                try {
                    playlistUtils.extractFromHls(
                        playlistUrl = stream.url,
                        masterHeaders = playerHeaders,
                        videoHeaders = playerHeaders,
                        videoNameGen = { quality ->
                            val label = if (quality == "Video") "${stream.height ?: "unknown"}p" else quality
                            "${server.name} - $label"
                        },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Playlist extraction failed for server '${server.name}' stream ${stream.height ?: "unknown"}p: ${e.javaClass.simpleName}: ${e.message}")
                    emptyList()
                }
            }
        }
    }

    /**
     * Fetch video streams from the CDN manifest endpoint using signature authentication.
     * When [retryOnAuthFailure] is true, a 401 response triggers escalating recovery:
     * 1. Retry with a fresh signature (no cache, so every call is fresh)
     * 2. If still 401, close and recreate the provider entirely, then retry
     */
    private suspend fun fetchManifestVideos(hvId: Long, retryOnAuthFailure: Boolean = false): List<Video> {
        val manifestUrl = "$cdnBaseUrl/api/v8/guest/videos/$hvId/manifest"
        val signature = ensureSignatureProvider().getSignature()
        val sigHeaders = headers.newBuilder().apply {
            SignatureHeaders.build(signature).forEach { (key, value) -> add(key, value) }
        }.build()

        var manifestResponseCode = 0
        val result = client.newCall(
            GET(manifestUrl, sigHeaders),
        ).await().use { response ->
            val contentType = response.body.contentType()
            val bodyString = response.body.string()
            manifestResponseCode = response.code
            if (response.isSuccessful) {
                response.newBuilder().body(bodyString.toResponseBody(contentType)).build().let { rebuilt ->
                    parseManifestStreams(rebuilt)
                }
            } else {
                val truncated = if (bodyString.length > 500) bodyString.take(500) + "..." else bodyString
                Log.w(TAG, "fetchManifestVideos() — manifest non-2xx ($manifestResponseCode) body: $truncated")
                emptyList()
            }
        }

        if (result.isNotEmpty()) return result

        if (manifestResponseCode == 401 && retryOnAuthFailure) {
            // Retry with a fresh signature (no cache, so every call is fresh)
            Log.d(TAG, "fetchManifestVideos() — 401 received, retrying with fresh signature")
            val freshSignature = ensureSignatureProvider().getSignature()
            val retryHeaders = headers.newBuilder().apply {
                SignatureHeaders.build(freshSignature).forEach { (key, value) -> add(key, value) }
            }.build()

            var retryResponseCode = 0
            val retryResult = client.newCall(
                GET(manifestUrl, retryHeaders),
            ).await().use { response ->
                val contentType = response.body.contentType()
                val bodyString = response.body.string()
                retryResponseCode = response.code
                if (response.isSuccessful) {
                    response.newBuilder().body(bodyString.toResponseBody(contentType)).build().let { rebuilt ->
                        parseManifestStreams(rebuilt)
                    }
                } else {
                    val truncated = if (bodyString.length > 500) bodyString.take(500) + "..." else bodyString
                    Log.w(TAG, "fetchManifestVideos() — retry non-2xx ($retryResponseCode) body: $truncated")
                    emptyList()
                }
            }

            if (retryResult.isNotEmpty()) return retryResult

            // Second retry: the provider itself may be in a bad state — recreate it
            if (retryResponseCode == 401) {
                Log.w(TAG, "fetchManifestVideos() — 401 persists after fresh signature — recreating signature provider")
                signatureProvider?.close()
                signatureProvider = null
                signatureProviderMode = null
                try {
                    val recreatedSignature = ensureSignatureProvider().getSignature()
                    val recreatedHeaders = headers.newBuilder().apply {
                        SignatureHeaders.build(recreatedSignature).forEach { (key, value) -> add(key, value) }
                    }.build()

                    return client.newCall(
                        GET(manifestUrl, recreatedHeaders),
                    ).await().use { response ->
                        val contentType = response.body.contentType()
                        val bodyString = response.body.string()
                        val responseCode = response.code
                        if (response.isSuccessful) {
                            response.newBuilder().body(bodyString.toResponseBody(contentType)).build().let { rebuilt ->
                                parseManifestStreams(rebuilt)
                            }
                        } else {
                            if (responseCode == 401) {
                                Log.e(TAG, "fetchManifestVideos() — 401 persists after provider recreation — giving up")
                            } else {
                                val truncated = if (bodyString.length > 500) bodyString.take(500) + "..." else bodyString
                                Log.w(TAG, "fetchManifestVideos() — retry non-2xx ($responseCode) body: $truncated")
                            }
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchManifestVideos() — provider recreation failed: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }

        return emptyList()
    }

    /**
     * Parse the guest manifest response and extract HLS video streams.
     * Uses PlaylistUtils.extractFromHls() to properly handle multi-quality
     * m3u8 playlists and set correct headers for segment/AES key requests.
     */
    private suspend fun parseManifestStreams(response: Response): List<Video> {
        val responseString = response.body.string().ifEmpty { return emptyList() }
        val manifestData = json.decodeFromString<ManifestWrapper>(responseString)
        val playerHeaders = playerVideoHeaders()
        val servers = manifestData.videosManifest.servers

        return servers.flatMap { server ->
            val includePremium = preferences.getBoolean(PREF_PREMIUM_STREAMS_KEY, PREF_PREMIUM_STREAMS_DEFAULT)
            val guestStreams = server.streams.filter { (it.isGuestAllowed == true || (includePremium && it.isMemberAllowed == true)) && it.url.contains(".m3u8") }
            guestStreams.flatMap { stream ->
                try {
                    playlistUtils.extractFromHls(
                        playlistUrl = stream.url,
                        masterHeaders = playerHeaders,
                        videoHeaders = playerHeaders,
                        videoNameGen = { quality ->
                            val label = if (quality == "Video") "${stream.height ?: "unknown"}p" else quality
                            "${server.name} - $label"
                        },
                    )
                } catch (_: Exception) {
                    // Fallback: create a single Video from the stream URL
                    listOf(Video(stream.url, "${server.name} - ${stream.height ?: "unknown"}p", stream.url, headers = playerHeaders))
                }
            }
        }
    }

    private suspend fun fetchVideoListPremium(episode: SEpisode): List<Video> {
        val cookie = authCookie ?: return emptyList()

        // If hvid is embedded in the episode URL, skip the HTML page parse
        val directHvId = extractHvIdFromUrl(episode.url)
        if (directHvId != null) {
            try {
                val manifestStreams = fetchManifestVideos(directHvId, retryOnAuthFailure = true)
                if (manifestStreams.isNotEmpty()) return manifestStreams
            } catch (_: Exception) {
                // Fall through to HTML parsing below
                Log.w(TAG, "fetchVideoListPremium() — directHvId manifest failed, falling back to HTML parsing")
            }
        }

        // Fallback: resolve hvId from the HTML page (backward compatibility)
        val slug = extractSlugFromUrl(episode.url)
        val headers = headers.newBuilder().add("cookie", cookie)
        val document = client.newCall(GET("$baseUrl/videos/hentai/$slug", headers = headers.build())).await().asJsoup()

        val nuxtScript = document.selectFirst("script:containsData(__NUXT__)") ?: return emptyList()
        val nuxtData = nuxtScript.data()
            .substringAfter("__NUXT__=")
            .substringBeforeLast(";")
        val parsed = json.decodeFromString<WindowNuxt>(nuxtData)

        // Try CDN guest manifest first — it has real Golem server streams (not Shiva decoys)
        val hvId = parsed.state.data.video.hentai_video?.id
        if (hvId != null) {
            try {
                val manifestStreams = fetchManifestVideos(hvId, retryOnAuthFailure = true)
                if (manifestStreams.isNotEmpty()) return manifestStreams
            } catch (_: Exception) {
                // Fall through to __NUXT__ streams below
                Log.w(TAG, "fetchVideoListPremium() — NUXT hvId manifest failed, falling back to NUXT streams")
            }
        }

        // Final fallback: parse manifest streams without guest filter
        val nuxtVideoModel = VideoModel(
            videosManifest = eu.kanade.tachiyomi.animeextension.en.hanime.VideosManifest(
                servers = parsed.state.data.video.videos_manifest.servers.map { nuxtServer ->
                    eu.kanade.tachiyomi.animeextension.en.hanime.Server(
                        name = nuxtServer.name,
                        streams = nuxtServer.streams.map { nuxtStream ->
                            eu.kanade.tachiyomi.animeextension.en.hanime.Stream(
                                height = nuxtStream.height,
                                url = nuxtStream.url,
                            )
                        },
                    )
                },
            ),
        )
        return parseVideoModelStreamsUnfiltered(nuxtVideoModel)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ── Episode List ───────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$baseUrl/api/v8/video?id=$slug", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string().ifEmpty { return emptyList() }
        val videoModel = json.decodeFromString<VideoModel>(responseString)

        val currentSeriesName = getTitle(videoModel.hentaiVideo?.name ?: "")
        val allFranchiseVideos = videoModel.hentaiFranchiseHentaiVideos ?: return emptyList()

        val seriesVideos = allFranchiseVideos
            .filter { getTitle(it.name ?: "") == currentSeriesName }

        if (seriesVideos.isEmpty()) {
            // No matching series found in franchise; return just the current video as a single episode
            val currentVideo = videoModel.hentaiVideo ?: return emptyList()
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = currentVideo.name ?: "Episode 1"
                    date_upload = (currentVideo.releasedAtUnix ?: 0) * 1000
                    val hvidParam = currentVideo.id?.let { id -> "&hvid=$id" } ?: ""
                    url = "$baseUrl/api/v8/video?id=${currentVideo.slug}$hvidParam"
                },
            )
        }

        return seriesVideos.mapIndexed { idx, it ->
            SEpisode.create().apply {
                episode_number = idx + 1f
                name = it.name ?: "Episode ${idx + 1}"
                date_upload = (it.releasedAtUnix ?: 0) * 1000
                val hvidParam = it.id?.let { id -> "&hvid=$id" } ?: ""
                url = "$baseUrl/api/v8/video?id=${it.slug}$hvidParam"
            }
        }.reversed()
    }

    // ── URL Helpers ───────────────────────────────────────────────────

    /** Extract the `hvid` query parameter from an episode URL, or null if absent. */
    private fun extractHvIdFromUrl(url: String): Long? {
        val hvidParam = url.substringAfter("&hvid=", missingDelimiterValue = "").substringBefore("&")
        return hvidParam.toLongOrNull()
    }

    /** Extract the slug (id query parameter) from an episode URL. */
    private fun extractSlugFromUrl(url: String): String = url.substringAfter("id=").substringBefore("&")

    // ── Auth ───────────────────────────────────────────────────────────

    private fun setAuthCookie() {
        if (authCookie == null) {
            val cookieList = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            val sessionCookie = cookieList.firstOrNull { it.name == "htv3session" }
            sessionCookie?.let { authCookie = "${it.name}=${it.value}" }
        }
    }

    // ── Filters ────────────────────────────────────────────────────────

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagList(getTags()),
        BrandList(getBrands()),
        SortFilter(sortableList.map { it.first }.toTypedArray()),
        TagInclusionMode(),
    )

    internal class Tag(val id: String, name: String) : AnimeFilter.TriState(name)
    internal class Brand(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class TagList(tags: List<Tag>) : AnimeFilter.Group<Tag>("Tags", tags)
    private class BrandList(brands: List<Brand>) : AnimeFilter.Group<Brand>("Brands", brands)
    private class TagInclusionMode : AnimeFilter.Select<String>("Included tags mode", arrayOf("And", "Or"), 0)

    private fun getSearchParameters(filters: AnimeFilterList): SearchParameters {
        val includedTags = mutableListOf<String>()
        val blackListedTags = mutableListOf<String>()
        val brands = mutableListOf<String>()
        var tagsMode = "AND"
        var orderBy = "likes"
        var ordering = "desc"
        filters.forEach { filter ->
            when (filter) {
                is TagList -> {
                    filter.state.forEach { tag ->
                        if (tag.isIncluded()) {
                            includedTags.add(
                                tag.id.lowercase(
                                    Locale.US,
                                ),
                            )
                        } else if (tag.isExcluded()) {
                            blackListedTags.add(
                                tag.id.lowercase(
                                    Locale.US,
                                ),
                            )
                        }
                    }
                }

                is TagInclusionMode -> {
                    tagsMode = filter.values[filter.state].uppercase(Locale.US)
                }

                is SortFilter -> {
                    if (filter.state != null) {
                        val query = sortableList[filter.state!!.index].second
                        val value = when (filter.state!!.ascending) {
                            true -> "asc"
                            false -> "desc"
                        }
                        ordering = value
                        orderBy = query
                    }
                }

                is BrandList -> {
                    filter.state.forEach { brand ->
                        if (brand.state) {
                            brands.add(
                                brand.id.lowercase(
                                    Locale.US,
                                ),
                            )
                        }
                    }
                }

                else -> {}
            }
        }
        return SearchParameters(includedTags.toList(), blackListedTags.toList(), brands.toList(), tagsMode, orderBy, ordering)
    }

    private fun getBrands() = listOf(
        Brand("37c-Binetsu", "37c-binetsu"),
        Brand("Adult Source Media", "adult-source-media"),
        Brand("Ajia-Do", "ajia-do"),
        Brand("Almond Collective", "almond-collective"),
        Brand("Alpha Polis", "alpha-polis"),
        Brand("Ameliatie", "ameliatie"),
        Brand("Amour", "amour"),
        Brand("Animac", "animac"),
        Brand("Antechinus", "antechinus"),
        Brand("APPP", "appp"),
        Brand("Arms", "arms"),
        Brand("Bishop", "bishop"),
        Brand("Blue Eyes", "blue-eyes"),
        Brand("BOMB! CUTE! BOMB!", "bomb-cute-bomb"),
        Brand("Bootleg", "bootleg"),
        Brand("BreakBottle", "breakbottle"),
        Brand("BugBug", "bugbug"),
        Brand("Bunnywalker", "bunnywalker"),
        Brand("Celeb", "celeb"),
        Brand("Central Park Media", "central-park-media"),
        Brand("ChiChinoya", "chichinoya"),
        Brand("Chocolat", "chocolat"),
        Brand("ChuChu", "chuchu"),
        Brand("Circle Tribute", "circle-tribute"),
        Brand("CoCoans", "cocoans"),
        Brand("Collaboration Works", "collaboration-works"),
        Brand("Comet", "comet"),
        Brand("Comic Media", "comic-media"),
        Brand("Cosmos", "cosmos"),
        Brand("Cranberry", "cranberry"),
        Brand("Crimson", "crimson"),
        Brand("D3", "d3"),
        Brand("Daiei", "daiei"),
        Brand("demodemon", "demodemon"),
        Brand("Digital Works", "digital-works"),
        Brand("Discovery", "discovery"),
        Brand("Dollhouse", "dollhouse"),
        Brand("EBIMARU-DO", "ebimaru-do"),
        Brand("Echo", "echo"),
        Brand("ECOLONUN", "ecolonun"),
        Brand("Edge", "edge"),
        Brand("Erozuki", "erozuki"),
        Brand("evee", "evee"),
        Brand("FINAL FUCK 7", "final-fuck-7"),
        Brand("Five Ways", "five-ways"),
        Brand("Friends Media Station", "friends-media-station"),
        Brand("Front Line", "front-line"),
        Brand("fruit", "fruit"),
        Brand("Godoy", "godoy"),
        Brand("GOLD BEAR", "gold-bear"),
        Brand("gomasioken", "gomasioken"),
        Brand("Green Bunny", "green-bunny"),
        Brand("Groover", "groover"),
        Brand("Hoods Entertainment", "hoods-entertainment"),
        Brand("Hot Bear", "hot-bear"),
        Brand("Hykobo", "hykobo"),
        Brand("IRONBELL", "ironbell"),
        Brand("Ivory Tower", "ivory-tower"),
        Brand("J.C.", "j-c"),
        Brand("Jellyfish", "jellyfish"),
        Brand("Jewel", "jewel"),
        Brand("Jumondo", "jumondo"),
        Brand("kate_sai", "kate_sai"),
        Brand("KENZsoft", "kenzsoft"),
        Brand("King Bee", "king-bee"),
        Brand("Kitty Media", "kitty-media"),
        Brand("Knack", "knack"),
        Brand("Kuril", "kuril"),
        Brand("L.", "l"),
        Brand("Lemon Heart", "lemon-heart"),
        Brand("Lilix", "lilix"),
        Brand("Lune Pictures", "lune-pictures"),
        Brand("Magic Bus", "magic-bus"),
        Brand("Magin Label", "magin-label"),
        Brand("Majin Petit", "majin-petit"),
        Brand("Marigold", "marigold"),
        Brand("Mary Jane", "mary-jane"),
        Brand("MediaBank", "mediabank"),
        Brand("Media Blasters", "media-blasters"),
        Brand("Metro Notes", "metro-notes"),
        Brand("Milky", "milky"),
        Brand("MiMiA Cute", "mimia-cute"),
        Brand("Moon Rock", "moon-rock"),
        Brand("Moonstone Cherry", "moonstone-cherry"),
        Brand("Mousou Senka", "mousou-senka"),
        Brand("MS Pictures", "ms-pictures"),
        Brand("Muse", "muse"),
        Brand("N43", "n43"),
        Brand("Nihikime no Dozeu", "nihikime-no-dozeu"),
        Brand("Nikkatsu Video", "nikkatsu-video"),
        Brand("nur", "nur"),
        Brand("NuTech Digital", "nutech-digital"),
        Brand("Obtain Future", "obtain-future"),
        Brand("Otodeli", "otodeli"),
        Brand("@ OZ", "oz"),
        Brand("Pashmina", "pashmina"),
        Brand("Passione", "passione"),
        Brand("Peach Pie", "peach-pie"),
        Brand("Pinkbell", "pinkbell"),
        Brand("Pink Pineapple", "pink-pineapple"),
        Brand("Pix", "pix"),
        Brand("Pixy Soft", "pixy-soft"),
        Brand("Pocomo Premium", "pocomo-premium"),
        Brand("PoRO", "poro"),
        Brand("Project No.9", "project-no-9"),
        Brand("Pumpkin Pie", "pumpkin-pie"),
        Brand("Queen Bee", "queen-bee"),
        Brand("Rabbit Gate", "rabbit-gate"),
        Brand("sakamotoJ", "sakamotoj"),
        Brand("Sakura Purin", "sakura-purin"),
        Brand("SANDWICHWORKS", "sandwichworks"),
        Brand("Schoolzone", "schoolzone"),
        Brand("seismic", "seismic"),
        Brand("SELFISH", "selfish"),
        Brand("Seven", "seven"),
        Brand("Shadow Prod. Co.", "shadow-prod-co"),
        Brand("Shelf", "shelf"),
        Brand("Shinyusha", "shinyusha"),
        Brand("ShoSai", "shosai"),
        Brand("Showten", "showten"),
        Brand("SoftCell", "softcell"),
        Brand("Soft on Demand", "soft-on-demand"),
        Brand("SPEED", "speed"),
        Brand("STARGATE3D", "stargate3d"),
        Brand("Studio 9 Maiami", "studio-9-maiami"),
        Brand("Studio Akai Shohosen", "studio-akai-shohosen"),
        Brand("Studio Deen", "studio-deen"),
        Brand("Studio Fantasia", "studio-fantasia"),
        Brand("Studio FOW", "studio-fow"),
        Brand("studio GGB", "studio-ggb"),
        Brand("Studio Houkiboshi", "studio-houkiboshi"),
        Brand("Studio Zealot", "studio-zealot"),
        Brand("Suiseisha", "suiseisha"),
        Brand("Suzuki Mirano", "suzuki-mirano"),
        Brand("SYLD", "syld"),
        Brand("TDK Core", "tdk-core"),
        Brand("t japan", "t-japan"),
        Brand("TNK", "tnk"),
        Brand("TOHO", "toho"),
        Brand("Toranoana", "toranoana"),
        Brand("T-Rex", "t-rex"),
        Brand("Triangle", "triangle"),
        Brand("Trimax", "trimax"),
        Brand("TYS Work", "tys-work"),
        Brand("U-Jin", "u-jin"),
        Brand("Umemaro-3D", "umemaro-3d"),
        Brand("Union Cho", "union-cho"),
        Brand("Valkyria", "valkyria"),
        Brand("Vanilla", "vanilla"),
        Brand("White Bear", "white-bear"),
        Brand("X City", "x-city"),
        Brand("yosino", "yosino"),
        Brand("Y.O.U.C.", "y-o-u-c"),
        Brand("ZIZ", "ziz"),
    )

    private fun getTags() = listOf(
        Tag("3D", "3D"),
        Tag("AHEGAO", "AHEGAO"),
        Tag("ANAL", "ANAL"),
        Tag("BDSM", "BDSM"),
        Tag("BIG BOOBS", "BIG BOOBS"),
        Tag("BLOW JOB", "BLOW JOB"),
        Tag("BONDAGE", "BONDAGE"),
        Tag("BOOB JOB", "BOOB JOB"),
        Tag("CENSORED", "CENSORED"),
        Tag("COMEDY", "COMEDY"),
        Tag("COSPLAY", "COSPLAY"),
        Tag("CREAMPIE", "CREAMPIE"),
        Tag("DARK SKIN", "DARK SKIN"),
        Tag("FACIAL", "FACIAL"),
        Tag("FANTASY", "FANTASY"),
        Tag("FILMED", "FILMED"),
        Tag("FOOT JOB", "FOOT JOB"),
        Tag("FUTANARI", "FUTANARI"),
        Tag("GANGBANG", "GANGBANG"),
        Tag("GLASSES", "GLASSES"),
        Tag("HAND JOB", "HAND JOB"),
        Tag("HAREM", "HAREM"),
        Tag("HD", "HD"),
        Tag("HORROR", "HORROR"),
        Tag("INCEST", "INCEST"),
        Tag("INFLATION", "INFLATION"),
        Tag("LACTATION", "LACTATION"),
        Tag("LOLI", "LOLI"),
        Tag("MAID", "MAID"),
        Tag("MASTURBATION", "MASTURBATION"),
        Tag("MILF", "MILF"),
        Tag("MIND BREAK", "MIND BREAK"),
        Tag("MIND CONTROL", "MIND CONTROL"),
        Tag("MONSTER", "MONSTER"),
        Tag("NEKOMIMI", "NEKOMIMI"),
        Tag("NTR", "NTR"),
        Tag("NURSE", "NURSE"),
        Tag("ORGY", "ORGY"),
        Tag("PLOT", "PLOT"),
        Tag("POV", "POV"),
        Tag("PREGNANT", "PREGNANT"),
        Tag("PUBLIC SEX", "PUBLIC SEX"),
        Tag("RAPE", "RAPE"),
        Tag("REVERSE RAPE", "REVERSE RAPE"),
        Tag("RIMJOB", "RIMJOB"),
        Tag("SCAT", "SCAT"),
        Tag("SCHOOL GIRL", "SCHOOL GIRL"),
        Tag("SHOTA", "SHOTA"),
        Tag("SOFTCORE", "SOFTCORE"),
        Tag("SWIMSUIT", "SWIMSUIT"),
        Tag("TEACHER", "TEACHER"),
        Tag("TENTACLE", "TENTACLE"),
        Tag("THREESOME", "THREESOME"),
        Tag("TOYS", "TOYS"),
        Tag("TRAP", "TRAP"),
        Tag("TSUNDERE", "TSUNDERE"),
        Tag("UGLY BASTARD", "UGLY BASTARD"),
        Tag("UNCENSORED", "UNCENSORED"),
        Tag("VANILLA", "VANILLA"),
        Tag("VIRGIN", "VIRGIN"),
        Tag("WATERSPORTS", "WATERSPORTS"),
        Tag("X-RAY", "X-RAY"),
        Tag("YAOI", "YAOI"),
        Tag("YURI", "YURI"),
    )

    private val sortableList = listOf(
        Pair("Uploads", "created_at_unix"),
        Pair("Views", "views"),
        Pair("Likes", "likes"),
        Pair("Release", "released_at_unix"),
        Pair("Alphabetical", "title_sortable"),
    )

    class SortFilter(sortables: Array<String>) : AnimeFilter.Sort("Sort", sortables, Selection(2, false))

    // ── Preferences ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "Hanime"
        private const val DEFAULT_CDN_BASE_URL = "https://cached.freeanimehentai.net"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SIG_PROVIDER_KEY = "signature_provider"
        private const val PREF_SIG_PROVIDER_DEFAULT = "native"
        private val SIG_PROVIDER_LIST = arrayOf("native", "webview", "wasm")

        private const val PREF_CENSORED_KEY = "censored_filter"
        private const val PREF_CENSORED_DEFAULT = "all"
        private val CENSORED_LIST = arrayOf("all", "uncensored", "censored")

        private const val PREF_PREMIUM_STREAMS_KEY = "premium_streams"
        private const val PREF_PREMIUM_STREAMS_DEFAULT = false

        private const val PREF_CACHE_TTL_KEY = "cache_duration"
        private const val PREF_CACHE_TTL_DEFAULT = "10"
        private val CACHE_TTL_LIST = arrayOf("1", "5", "10", "30")

        private const val PREF_CUSTOM_CDN_KEY = "custom_cdn"
        private const val PREF_CUSTOM_CDN_DEFAULT = ""
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Preferred Quality
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entries[index]
                summary = entry
                true
            }
        }.also(screen::addPreference)

        // Signature Provider
        ListPreference(screen.context).apply {
            key = PREF_SIG_PROVIDER_KEY
            title = "Signature provider"
            entries = arrayOf("Direct SHA-256 computation (Recommended)", "WebView", "Chicory WASM Runtime (Experimental)")
            entryValues = SIG_PROVIDER_LIST
            setDefaultValue(PREF_SIG_PROVIDER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, _ ->
                signatureProvider?.close()
                signatureProvider = null
                signatureProviderMode = null
                true
            }
        }.also(screen::addPreference)

        // Censored Content Filter
        ListPreference(screen.context).apply {
            key = PREF_CENSORED_KEY
            title = "Censored content filter"
            entries = arrayOf("Show All", "Uncensored Only", "Censored Only")
            entryValues = CENSORED_LIST
            setDefaultValue(PREF_CENSORED_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entries[index]
                summary = entry
                true
            }
        }.also(screen::addPreference)

        // Premium Streams Toggle
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_PREMIUM_STREAMS_KEY
            title = "Include premium streams"
            summary = "Show streams that require a premium account. These will fail to play without a premium login cookie."
            setDefaultValue(PREF_PREMIUM_STREAMS_DEFAULT)
        }.also(screen::addPreference)

        // Search Cache Duration
        ListPreference(screen.context).apply {
            key = PREF_CACHE_TTL_KEY
            title = "Search cache duration"
            entries = arrayOf("1 minute", "5 minutes", "10 minutes", "30 minutes")
            entryValues = CACHE_TTL_LIST
            setDefaultValue(PREF_CACHE_TTL_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entries[index]
                summary = entry
                true
            }
        }.also(screen::addPreference)

        // Custom CDN Domain
        EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_CDN_KEY
            title = "Custom CDN domain"
            summary = "Leave empty for default: $DEFAULT_CDN_BASE_URL"
            dialogMessage = "Enter custom CDN domain URL (leave empty for default: $DEFAULT_CDN_BASE_URL)"
            setDefaultValue(PREF_CUSTOM_CDN_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as String
                value.isBlank() || value.toHttpUrlOrNull() != null
            }
        }.also(screen::addPreference)
    }
}
