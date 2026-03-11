package eu.kanade.tachiyomi.animeextension.es.mhdflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamvidextractor.StreamVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

open class MhdFlix : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "MhdFlix"

    override val baseUrl = "https://ww1.mhdflix.com"

    private val apiUrl = "https://core.mhdflix.com"

    override val lang = "es"

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val jsonMediaType = "application/json".toMediaType()

    private val dateFormatter by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val popularPageSize = 40
    private val popularCacheTtlMs = 5 * 60 * 1000L
    private val latestPageSize = 20
    private val latestCacheTtlMs = 60 * 1000L

    private var seoCatalogCache: PopularCatalogCache? = null
    private var latestEpisodesCache: LatestEpisodesCache? = null
    private val mediaDetailCache = mutableMapOf<Int, MediaDto>()

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]", "[VOSE]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "Filemoon",
            "StreamVid",
            "VidHide",
            "Voe",
            "Uqload",
            "Lulu",
            "StreamTape",
            "Doodstream",
            "MixDrop",
            "Filelions",
            "Hexupload",
            "Netu",
        )
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*")

    override fun popularAnimeRequest(page: Int): Request =
        apiGet("$apiUrl/api/seo/medias", page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val requestedPage = response.request.page
        val now = System.currentTimeMillis()
        val freshEntries = response.parseAs<SeoMediaListResponse>().data
            .filter { it.idMedia != null }
            .sortedByDescending { it.createadAt.orEmpty() }

        val catalogEntries = when {
            freshEntries.isNotEmpty() -> {
                seoCatalogCache = PopularCatalogCache(now, freshEntries)
                freshEntries
            }
            else ->
                seoCatalogCache
                    ?.takeIf { now - it.timestamp <= popularCacheTtlMs }
                    ?.entries
                    ?: emptyList()
        }

        if (catalogEntries.isEmpty()) return AnimesPage(emptyList(), false)

        val startIndex = (requestedPage - 1) * popularPageSize
        if (startIndex >= catalogEntries.size) return AnimesPage(emptyList(), false)

        val endIndex = minOf(startIndex + popularPageSize, catalogEntries.size)
        val pageSlice = catalogEntries.subList(startIndex, endIndex)

        val animeList = pageSlice.mapNotNull { entry ->
            val mediaId = entry.idMedia ?: return@mapNotNull null
            fetchMediaSummary(mediaId)?.toSAnime()
        }

        val hasNext = endIndex < catalogEntries.size
        return AnimesPage(animeList, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        apiGet("$apiUrl/api/serie/episode/last?page=$page", page)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val requestedPage = response.request.page
        val now = System.currentTimeMillis()
        val payload = response.parseAs<EpisodeListResponse>()
        val freshEntries = payload.data

        val cachedEntries = when {
            freshEntries.isNotEmpty() -> {
                latestEpisodesCache = LatestEpisodesCache(now, freshEntries)
                freshEntries
            }
            else ->
                latestEpisodesCache
                    ?.takeIf { now - it.timestamp <= latestCacheTtlMs }
                    ?.entries
                    ?: emptyList()
        }

        if (cachedEntries.isEmpty()) return AnimesPage(emptyList(), false)

        val startIndex = (requestedPage - 1) * latestPageSize
        if (startIndex >= cachedEntries.size) return AnimesPage(emptyList(), false)

        val endIndex = minOf(startIndex + latestPageSize, cachedEntries.size)
        val pageSlice = cachedEntries.subList(startIndex, endIndex)
        val animeList = pageSlice.map { it.toLatestSAnime() }
        val hasNext = endIndex < cachedEntries.size
        return AnimesPage(animeList, hasNext)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = MhdFlixFilters.getSearchParameters(filters)
        val typeOnly = params.type?.isNotBlank() == true && query.isBlank() && params.genre == null && params.year == null

        if (typeOnly) {
            val type = params.type!!
            val url = "$apiUrl/api/seo/medias".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("typeFilter", type)
                .build()

            return Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .tag(Int::class.javaObjectType, page)
                .build()
        }

        val body = when {
            query.isNotBlank() -> buildJsonObject {
                put("query", query)
                put("page", page)
                params.type?.takeIf { it.isNotBlank() }?.let { put("type", it) }
            }
            params.genre != null -> buildJsonObject {
                put("genre", params.genre)
                put("page", page)
                params.type?.takeIf { it.isNotBlank() }?.let { put("type", it) }
            }
            params.year != null -> buildJsonObject {
                put("year", params.year)
                put("page", page)
                params.type?.takeIf { it.isNotBlank() }?.let { put("type", it) }
            }
            else -> buildJsonObject {
                put("query", "")
                put("page", page)
            }
        }
        val endpoint = when {
            query.isNotBlank() -> "$apiUrl/api/search/query"
            params.genre != null -> "$apiUrl/api/search/genres"
            params.year != null -> "$apiUrl/api/search/year"
            else -> "$apiUrl/api/search/query"
        }

        return apiPost(endpoint, page, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val requestUrl = response.request.url
        val requestedPage = response.request.page
        val typeFilter = requestUrl.queryParameter("typeFilter")?.takeIf { it.isNotBlank() }

        if (typeFilter != null) {
            return parseTypeFilterResults(response, typeFilter)
        }

        val payload = response.parseAs<MediaListResponse>()
        val animeList = payload.mediaEntries().mapNotNull { it.toSAnime() }
        val currentPage = payload.currentPage ?: requestedPage
        val hasNext = payload.totalPage?.let { currentPage < it } ?: false
        return AnimesPage(animeList, hasNext)
    }

    private fun parseTypeFilterResults(response: Response, typeFilter: String): AnimesPage {
        val requestedPage = response.request.page
        val now = System.currentTimeMillis()
        val payload = response.parseAs<SeoMediaListResponse>()
        val freshEntries = payload.data
            .filter { it.idMedia != null }
            .sortedByDescending { it.createadAt.orEmpty() }

        val catalogEntries = when {
            freshEntries.isNotEmpty() -> {
                seoCatalogCache = PopularCatalogCache(now, freshEntries)
                freshEntries
            }
            else ->
                seoCatalogCache
                    ?.takeIf { now - it.timestamp <= popularCacheTtlMs }
                    ?.entries
                    ?: emptyList()
        }

        if (catalogEntries.isEmpty()) return AnimesPage(emptyList(), false)

        val filteredEntries = catalogEntries.filter { it.type.equals(typeFilter, true) }
        if (filteredEntries.isEmpty()) return AnimesPage(emptyList(), false)

        val startIndex = (requestedPage - 1) * popularPageSize
        if (startIndex >= filteredEntries.size) return AnimesPage(emptyList(), false)

        val endIndex = minOf(startIndex + popularPageSize, filteredEntries.size)
        val pageSlice = filteredEntries.subList(startIndex, endIndex)
        val animeList = pageSlice.mapNotNull { entry ->
            val mediaId = entry.idMedia ?: return@mapNotNull null
            fetchMediaSummary(mediaId)?.toSAnime()
        }
        val hasNext = endIndex < filteredEntries.size
        return AnimesPage(animeList, hasNext)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (_, id) = decodeAnimeUrl(anime.url)
        return apiGet("$apiUrl/api/media/$id")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val payload = response.parseAs<MediaDetailResponse>()
        val data = payload.data ?: throw Exception("No se pudo obtener la información del anime")
        return data.toDetailedSAnime()
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val (_, id) = decodeAnimeUrl(anime.url)
        return apiGet("$apiUrl/api/media/$id")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val payload = response.parseAs<MediaDetailResponse>()
        val details = payload.data ?: return emptyList()
        return if (details.type.equals("movie", true)) {
            listOf(details.toMovieEpisode())
        } else {
            fetchEpisodes(details)
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val (kind, id) = decodeEpisodeUrl(episode.url)
        val endpoint = when (kind) {
            "movie" -> "$apiUrl/api/links/movie/$id"
            else -> "$apiUrl/api/links/episode/$id"
        }
        return apiGet(endpoint)
    }

    override fun videoListParse(response: Response): List<Video> {
        val payload = response.parseAs<LinksResponse>()
        val uniqueLinks = payload.data.distinctBy { it.link }
        if (uniqueLinks.isEmpty()) return emptyList()

        val videos = uniqueLinks.parallelFlatMapBlocking { link ->
            link.toVideos()
        }.distinctBy { it.url }

        return videos.sort()
    }

    private fun fetchMediaSummary(mediaId: Int): MediaDto? {
        mediaDetailCache[mediaId]?.let { return it }

        return runCatching {
            client.newCall(apiGet("$apiUrl/api/media/$mediaId")).execute().use { detailResponse ->
                val payload = json.decodeFromString<MediaDetailResponse>(detailResponse.body.string())
                payload.data?.also { mediaDetailCache[mediaId] = it }
            }
        }.getOrNull()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang, true) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = MhdFlixFilters.FILTER_LIST

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
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

        FilemoonExtractor.addSubtitlePref(screen)
    }

    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamVidExtractor by lazy { StreamVidExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }

    private fun LinkDto.toVideos(): List<Video> {
        val url = link ?: return emptyList()
        val languageTag = language?.name.toLanguageTag()
        val qualityLabel = quality?.name?.takeIf { it.isNotBlank() }
        val baseLabel = listOfNotNull(languageTag, qualityLabel)
        val serverLabel = server?.name.orEmpty()
        val lowerServer = serverLabel.lowercase()

        val nameBuilder: (String, String?) -> String = { host, dynamicQuality ->
            val parts = mutableListOf<String>()
            parts.addAll(baseLabel)
            parts += host
            dynamicQuality?.takeIf { it.isNotBlank() }?.let { parts += it }
            parts.joinToString(" - ").ifBlank { host }
        }

        val prefixLabel = baseLabel.joinToString(" - ").let { if (it.isNotBlank()) "$it - " else "" }

        return when {
            lowerServer.contains("streamwish") -> runExtractor { streamWishExtractor.videosFromUrl(url) { q -> nameBuilder("StreamWish", q) } }
            lowerServer.contains("vidhide") -> runExtractor { vidHideExtractor.videosFromUrl(url) { q -> nameBuilder("VidHide", q) } }
            lowerServer.contains("voe") -> runExtractor { voeExtractor.videosFromUrl(url, prefix = prefixLabel) }
            lowerServer.contains("uqload") -> runExtractor { uqloadExtractor.videosFromUrl(url, prefix = nameBuilder("Uqload", null)) }
            lowerServer.contains("streamtape") -> runExtractor { streamTapeExtractor.videosFromUrl(url, quality = nameBuilder("StreamTape", null)) }
            lowerServer.contains("dood") -> runExtractor { doodExtractor.videosFromUrl(url, quality = nameBuilder("Doodstream", null)) }
            lowerServer.contains("mixdrop") -> runExtractor { mixDropExtractor.videosFromUrl(url, prefix = prefixLabel) }
            lowerServer.contains("filemoon") -> runExtractor { filemoonExtractor.videosFromUrl(url, nameBuilder("Filemoon", null), headers) }
            lowerServer.contains("streamvid") -> runExtractor { streamVidExtractor.videosFromUrl(url, prefix = prefixLabel) }
            lowerServer.contains("lulu") || lowerServer.contains("luluvdo") -> runExtractor { luluExtractor.videosFromUrl(url, prefixLabel) }
            lowerServer.contains("filelions") -> runExtractor { universalExtractor.videosFromUrl(url, headers, prefix = nameBuilder("Filelions", null)) }
            lowerServer.contains("hexupload") -> runExtractor { universalExtractor.videosFromUrl(url, headers, prefix = nameBuilder("Hexupload", null)) }
            lowerServer.contains("netu") -> runExtractor { universalExtractor.videosFromUrl(url, headers, prefix = nameBuilder("Netu", null)) }
            else -> runExtractor { universalExtractor.videosFromUrl(url, headers, prefix = nameBuilder(serverLabel.ifBlank { "Mirror" }, null)) }
        }
    }

    private fun MediaDto.toSAnime(): SAnime? {
        val mediaId = idMedia ?: return null
        val mediaType = type?.ifBlank { null } ?: "tv"
        return SAnime.create().apply {
            title = this@toSAnime.resolveTitle()
            thumbnail_url = posterPath.toImageUrl()
            setUrlWithoutDomain("$mediaType/$mediaId")
        }
    }

    private fun MediaDto.toDetailedSAnime(): SAnime {
        val mediaType = type?.ifBlank { null } ?: "tv"
        val genreTags = mutableSetOf<String>().apply {
            genders?.forEach { label ->
                if (label.isNotBlank()) add(label)
            }
            genre?.forEach { label ->
                if (label.isNotBlank()) add(label)
            }
        }
        val normalizedStatus = status?.lowercase(Locale.ROOT)
        return SAnime.create().apply {
            title = this@toDetailedSAnime.resolveTitle()
            thumbnail_url = posterPath.toImageUrl()
            genre = genreTags.joinToString()
            description = content.orEmpty()
            status = when (normalizedStatus) {
                "ended", "finalizado" -> SAnime.COMPLETED
                "ongoing", "en emisión" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            setUrlWithoutDomain("$mediaType/${idMedia ?: 0}")
        }
    }

    private fun MediaDto.toMovieEpisode(): SEpisode {
        val id = idMedia ?: throw IllegalStateException("ID de película inválido")
        return SEpisode.create().apply {
            val movieTitle = this@toMovieEpisode.resolveTitle()
            val normalizedTitle = movieTitle.lowercase(Locale.ROOT)
            val defaultMoviePlaceholders = setOf("película sin título", "sin título")
            name = if (normalizedTitle in defaultMoviePlaceholders) {
                "Película"
            } else {
                "Película – $movieTitle"
            }
            episode_number = 1f
            setUrlWithoutDomain("movie/$id")
            date_upload = releaseDate.toEpoch()
            scanlator = id.toString()
        }
    }

    private fun fetchEpisodes(details: MediaDto): List<SEpisode> {
        val serieId = details.idMedia ?: return emptyList()
        val seasonsResponse = client.newCall(apiGet("$apiUrl/api/serie/$serieId/seasons")).execute()
        val seasons = seasonsResponse.parseAs<SeasonListResponse>().data
        val episodes = seasons.flatMap { season ->
            val seasonId = season.idSeasson ?: return@flatMap emptyList<SEpisode>()
            val seasonNumber = season.num ?: 0
            fetchSeasonEpisodes(seasonId, seasonNumber, serieId)
        }

        val sortedEpisodes = episodes.sortedByDescending { it.episode_number }
        sortedEpisodes.forEachIndexed { index, episode ->
            episode.episode_number = (sortedEpisodes.size - index).toFloat()
        }
        return sortedEpisodes
    }

    private fun fetchSeasonEpisodes(seasonId: Int, seasonNumber: Int, serieId: Int): List<SEpisode> {
        val collected = mutableListOf<SEpisode>()
        val seenIds = mutableSetOf<Int>()
        var page = 1
        do {
            val response = client.newCall(apiGet("$apiUrl/api/serie/episodes/$seasonId/$page", page)).execute()
            val payload = response.parseAs<EpisodeListResponse>()
            payload.data.forEach { episodeDto ->
                val episodeId = episodeDto.idEpisodios ?: return@forEach
                if (seenIds.add(episodeId)) {
                    collected += episodeDto.toEpisode(seasonNumber, serieId)
                }
            }
            val totalPages = payload.totalPage ?: page
            if (page >= totalPages) break
            page++
        } while (true)
        return collected
    }

    private fun EpisodeDto.toEpisode(seasonNumber: Int, serieId: Int): SEpisode {
        val number = numEpisode ?: 0.0
        val displayNumber = when {
            number == 0.0 -> "0"
            number % 1.0 == 0.0 -> number.toInt().toString()
            else -> number.toString()
        }
        return SEpisode.create().apply {
            val formattedNumber = if (seasonNumber > 0) "T${seasonNumber}x$displayNumber" else displayNumber
            name = listOfNotNull(formattedNumber.takeIf { it.isNotBlank() }, this@toEpisode.title).joinToString(" - ")
            val seasonOffset = ((seasonNumber.takeIf { it > 0 } ?: 1) - 1) * 100f
            episode_number = seasonOffset + number.toFloat()
            date_upload = airDate.toEpoch()
            setUrlWithoutDomain("episode/${idEpisodios ?: 0}")
            scanlator = serieId.toString()
        }
    }

    private fun EpisodeDto.toLatestSAnime(): SAnime = SAnime.create().apply {
        val serieIdentifier = serieId ?: idSerie ?: idMedia ?: 0
        title = this@toLatestSAnime.title.orEmpty()
        setUrlWithoutDomain("tv/$serieIdentifier")
        thumbnail_url = posterPath.toImageUrl()
    }

    private fun String?.toImageUrl(): String? = this?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

    private fun String?.toLanguageTag(): String? {
        val value = this?.lowercase(Locale.ROOT) ?: return null
        return when {
            "lat" in value -> "[LAT]"
            "cast" in value || "esp" in value -> "[CAST]"
            "sub" in value -> "[SUB]"
            "vose" in value -> "[VOSE]"
            else -> "[${this.trim()}]"
        }
    }

    private fun String?.toEpoch(): Long {
        return try {
            if (this.isNullOrBlank()) 0L else dateFormatter.parse(this)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun MediaDto.resolveTitle(forEpisode: Boolean = false): String {
        val sanitizedTitle = title
            ?.replace(whitespaceRegex, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (sanitizedTitle != null) return sanitizedTitle

        val slugTitle = slug
            ?.split('-')
            ?.mapNotNull { part ->
                part.takeIf { it.isNotBlank() }?.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
            ?.joinToString(" ")
            ?.replace(whitespaceRegex, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (slugTitle != null) return slugTitle

        return when {
            type.equals("movie", ignoreCase = true) -> if (forEpisode) "Película" else "Película sin título"
            else -> if (forEpisode) "Episodio" else "Sin título"
        }
    }

    private fun decodeAnimeUrl(url: String): Pair<String, Int> {
        val parts = url.trim('/').split('/')
        val type = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "tv"
        val id = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return type to id
    }

    private fun decodeEpisodeUrl(url: String): Pair<String, Int> {
        val parts = url.trim('/').split('/')
        val type = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "episode"
        val id = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return type to id
    }

    private fun apiGet(url: String, page: Int? = null): Request = Request.Builder()
        .url(url)
        .headers(headers)
        .get()
        .apply { page?.let { tag(Int::class.javaObjectType, it) } }
        .build()

    private fun apiPost(url: String, page: Int, obj: JsonObject): Request {
        val body = obj.toString().toRequestBody(jsonMediaType)
        return Request.Builder()
            .url(url)
            .headers(headers)
            .post(body)
            .tag(Int::class.javaObjectType, page)
            .build()
    }

    private val Request.page: Int
        get() = tag(Int::class.javaObjectType) ?: url.queryParameter("page")?.toIntOrNull() ?: 1

    private data class PopularCatalogCache(
        val timestamp: Long,
        val entries: List<SeoMediaDto>,
    )

    private data class LatestEpisodesCache(
        val timestamp: Long,
        val entries: List<EpisodeDto>,
    )

    private inline fun <reified T> Response.parseAs(): T = use { json.decodeFromString(it.body.string()) }

    private val qualityRegex = Regex("""(\d+)p""")
    private val whitespaceRegex = Regex("\\s+")

    private inline fun runExtractor(block: () -> List<Video>): List<Video> = runCatching(block).getOrElse { emptyList() }

    private fun MediaListResponse.mediaEntries(): List<MediaDto> = when (val element = data) {
        is JsonArray -> element.mapNotNull { runCatching { json.decodeFromJsonElement<MediaDto>(it) }.getOrNull() }
        is JsonObject -> listOfNotNull(runCatching { json.decodeFromJsonElement<MediaDto>(element) }.getOrNull())
        else -> emptyList()
    }
}
