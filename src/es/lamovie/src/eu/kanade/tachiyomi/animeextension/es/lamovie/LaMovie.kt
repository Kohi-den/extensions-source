package eu.kanade.tachiyomi.animeextension.es.lamovie

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.lamovie.extractors.LaMovieEmbedExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.goodstramextractor.GoodStreamExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LaMovie : DopeFlix(
    "LaMovie",
    "es",
    arrayOf(
        "la.movie",
    ),
    "la.movie",
) {
    override val id: Long = 5419283741928374105

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client, headers) }
    private val lamovieEmbedExtractor by lazy { LaMovieEmbedExtractor(client, headers) }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val type = preferredListingType()
        val url = listingBuilder(type, page)
            .addQueryParameter("orderBy", "views")
            .addQueryParameter("order", "DESC")
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = response.parseListing()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val type = preferredListingType()
        val url = listingBuilder(type, page)
            .addQueryParameter("orderBy", "latest")
            .addQueryParameter("order", "DESC")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = response.parseListing()

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = LaMovieFilters.createFilterList()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            val builder = apiUrlBuilder("search")
                .addQueryParameter("postType", "any")
                .addQueryParameter("q", trimmedQuery)
                .addQueryParameter("postsPerPage", DEFAULT_POSTS_PER_PAGE.toString())
                .addQueryParameter("page", page.toString())

            return GET(builder.build(), headers)
        }

        val params = LaMovieFilters.getSearchParameters(filters)
        val listingType = normalizeListingType(params.type.ifBlank { preferredListingType() })

        val builder = listingBuilder(listingType, page)
            .addQueryParameter("orderBy", params.orderBy.ifBlank { LaMovieFilters.DEFAULT_ORDER_BY })
            .addQueryParameter("order", params.order.ifBlank { LaMovieFilters.DEFAULT_ORDER })

        buildFilterQuery(params)?.let { filterJson ->
            builder.addQueryParameter("filter", filterJson)
        }

        return GET(builder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = response.parseListing(allowShortQueryFallback = true)

    private fun buildFilterQuery(params: LaMovieFilters.FilterSearchParams): String? {
        val payload = buildMap<String, JsonElement> {
            fun putIntArray(key: String, values: List<Int>) {
                if (values.isNotEmpty()) {
                    put(key, JsonArray(values.map(::JsonPrimitive)))
                }
            }

            putIntArray("genres", params.genres)
            putIntArray("countries", params.countries)
            putIntArray("providers", params.providers)
            putIntArray("years", params.years)
        }

        if (payload.isEmpty()) return null

        return json.encodeToString(JsonObject(payload))
    }

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val context = parseAnimeContext(anime.url)

        val builder = apiUrlBuilder("single", context.type)
            .addQueryParameter("slug", context.slug)
            .addQueryParameter("postType", context.type)

        context.id?.let { builder.addQueryParameter("_id", it.toString()) }

        return GET(builder.build(), headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseData(PostDto.serializer())
        return data.toSAnime()
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseData(PostDto.serializer())
        val context = AnimeContext(
            type = data.postType,
            slug = data.slug.ifBlank { data.id.toString() },
            id = data.id,
        )
        val seriesId = data.id
        val postType = data.postType

        val episodes = if (postType in SERIES_POST_TYPES) {
            fetchAllEpisodes(seriesId)
        } else {
            listOf(EpisodeDto(id = seriesId, name = data.title, seasonNumber = 1, episodeNumber = 1))
        }

        return episodes
            .distinctBy(EpisodeDto::id)
            .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
            .map { it.toSEpisode(seriesId, context) }
            .reversed()
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val episodeUrl = parseEpisodeUrl(episode.url)
        val builder = apiUrlBuilder("player")
            .addQueryParameter("postId", episodeUrl.postId.toString())
            .addQueryParameter("demo", "0")

        return GET(builder.build(), headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = response.parseData(PlayerDataDto.serializer())

        val embeds = data.parseEmbeds()
        if (embeds.isEmpty()) return emptyList()

        val preferredLanguage = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)
            ?.let(::normalizeLanguagePreference)
            ?: PREF_LANGUAGE_DEFAULT
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prioritizedEmbeds = if (preferredLanguage == PREF_LANGUAGE_DEFAULT && preferredServer == PREF_SERVER_DEFAULT) {
            embeds
        } else {
            embeds.sortedWith(
                compareByDescending<EmbedItem> { it.matchesLanguage(preferredLanguage) }
                    .thenByDescending { it.matchesServer(preferredServer) },
            )
        }

        return prioritizedEmbeds.flatMap(::resolveEmbedVideos)
    }

    private fun resolveEmbedVideos(embed: EmbedItem): List<Video> {
        return runCatching {
            val prefix = buildString {
                embed.language?.takeIf(String::isNotBlank)?.let { append("${it.uppercase(Locale.US)} | ") }
                embed.quality?.takeIf(String::isNotBlank)?.let { append(" - $it") }
            }

            when (embed.serverKey()) {
                SERVER_KEY_DOOD -> doodExtractor.videosFromUrl(embed.url, "$prefix - Doodstream")
                SERVER_KEY_VOE -> voeExtractor.videosFromUrl(embed.url, "$prefix - Voe")
                SERVER_KEY_MP4UPLOAD -> mp4uploadExtractor.videosFromUrl(embed.url, headers, "$prefix - Mp4upload")
                SERVER_KEY_STREAMHIDE -> streamHideVidExtractor.videosFromUrl(embed.url) { quality -> "StreamHide - $quality - $prefix" }
                SERVER_KEY_STREAMWISH -> streamWishExtractor.videosFromUrl(embed.url, prefix)
                SERVER_KEY_YOURUPLOAD -> yourUploadExtractor.videoFromUrl(embed.url, headers, "$prefix - YourUpload")
                SERVER_KEY_FILEMOON -> filemoonExtractor.videosFromUrl(embed.url, "$prefix - Filemoon")
                SERVER_KEY_GOODSTREAM -> goodStreamExtractor.videosFromUrl(embed.url, "$prefix - GoodStream")
                SERVER_KEY_LAMOVIE -> lamovieEmbedExtractor.videosFromUrl(embed.url, "$prefix - HLS")
                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val preferredQualityLower = preferredQuality.lowercase(Locale.US)
        val preferredQualityValue = QUALITY_REGEX.find(preferredQualityLower)?.groupValues?.get(1)?.toIntOrNull()

        val qualityKeywordsByValue = mapOf(
            2160 to listOf("2160", "4k", "uhd"),
            1440 to listOf("1440", "2k", "qhd"),
            1080 to listOf("1080", "fhd", "full hd"),
            720 to listOf("720", "hd"),
            480 to listOf("480", "sd"),
            360 to listOf("360"),
        )

        fun Video.matchesPreferredQuality(): Boolean {
            val normalized = quality.lowercase(Locale.US)
            if (normalized.contains(preferredQualityLower)) return true

            val numericQuality = QUALITY_REGEX.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if (preferredQualityValue != null && numericQuality != null && numericQuality == preferredQualityValue) return true

            val aliases = qualityKeywordsByValue[preferredQualityValue]
            return !aliases.isNullOrEmpty() && aliases.any { normalized.contains(it) }
        }

        fun Video.extractQualityValue(): Int {
            val normalized = quality.lowercase(Locale.US)
            val numericQuality = QUALITY_REGEX.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if (numericQuality != null) return numericQuality

            return when {
                normalized.contains("4k") || normalized.contains("uhd") -> 2160
                normalized.contains("2k") || normalized.contains("qhd") -> 1440
                normalized.contains("full hd") || normalized.contains("fhd") -> 1080
                normalized.contains("hd") -> 720
                normalized.contains("sd") -> 480
                normalized.contains("cam") -> 144
                else -> 0
            }
        }

        val qualitySorted = this.sortedWith(
            compareByDescending<Video> { if (it.matchesPreferredQuality()) 1 else 0 }
                .thenByDescending { it.extractQualityValue() },
        )

        val preferredLanguage = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)
            ?.let(::normalizeLanguagePreference)
            ?: PREF_LANGUAGE_DEFAULT
        val languageSorted = if (preferredLanguage == PREF_LANGUAGE_DEFAULT) {
            qualitySorted
        } else {
            qualitySorted.sortedByDescending { it.matchesLanguage(preferredLanguage) }
        }

        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        if (preferredServer == PREF_SERVER_DEFAULT) return languageSorted

        return languageSorted.sortedByDescending { it.matchesServer(preferredServer) }
    }
    private fun EmbedItem.matchesServer(preferredKey: String): Boolean {
        if (preferredKey == PREF_SERVER_DEFAULT) return false
        return serverKey() == preferredKey
    }

    private fun EmbedItem.serverKey(): String = detectServer(server, url)

    private fun EmbedItem.matchesLanguage(preferredKey: String): Boolean {
        if (preferredKey == PREF_LANGUAGE_DEFAULT) return false
        return languageCode() == preferredKey
    }

    private fun EmbedItem.languageCode(): String = detectLanguage(language, server, url)

    private fun Video.matchesServer(preferredKey: String): Boolean {
        if (preferredKey == PREF_SERVER_DEFAULT) return false
        return serverKey() == preferredKey
    }

    private fun Video.serverKey(): String = detectServer(quality, url)

    private fun Video.matchesLanguage(preferredKey: String): Boolean {
        if (preferredKey == PREF_LANGUAGE_DEFAULT) return false
        return languageCode() == preferredKey
    }

    private fun Video.languageCode(): String = detectLanguage(quality, url)

    private fun detectServer(vararg texts: String?): String {
        if (texts.isEmpty()) return SERVER_KEY_UNKNOWN

        val combined = texts
            .asSequence()
            .filterNotNull()
            .map { it.lowercase(Locale.US) }
            .joinToString(" ")

        SERVER_KEYWORDS.forEach { (key, keywords) ->
            if (keywords.any { it in combined }) return key
        }

        return SERVER_KEY_UNKNOWN
    }

    private fun detectLanguage(vararg texts: String?): String {
        if (texts.isEmpty()) return LANGUAGE_CODE_UNKNOWN

        val fingerprint = texts
            .asSequence()
            .filterNotNull()
            .joinToString(" ")
            .lowercase(Locale.US)

        return when {
            LANGUAGE_LATINO_REGEX.containsMatchIn(fingerprint) -> LANGUAGE_CODE_LATINO
            LANGUAGE_CASTELLANO_REGEX.containsMatchIn(fingerprint) -> LANGUAGE_CODE_CASTELLANO
            LANGUAGE_SUB_REGEX.containsMatchIn(fingerprint) -> LANGUAGE_CODE_SUB
            LANGUAGE_ENGLISH_REGEX.containsMatchIn(fingerprint) -> LANGUAGE_CODE_ENGLISH
            else -> LANGUAGE_CODE_UNKNOWN
        }
    }

    // ============================== Utilities =============================
    private fun listingBuilder(type: String, page: Int): HttpUrl.Builder = apiUrlBuilder("listing", type)
        .addQueryParameter("postType", type)
        .addQueryParameter("postsPerPage", DEFAULT_POSTS_PER_PAGE.toString())
        .addQueryParameter("page", page.toString())

    private fun Response.parseListing(allowShortQueryFallback: Boolean = false): AnimesPage {
        val root = json.parseToJsonElement(body.string()).jsonObject
        if (root["error"]?.jsonPrimitive?.booleanOrNull == true) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Error desconocido"
            if (allowShortQueryFallback && message.contains("muy corta", ignoreCase = true)) {
                return AnimesPage(emptyList(), false)
            }
            throw Exception(message)
        }

        val data = root["data"] ?: throw Exception("Respuesta vacía de la API")
        val listing = json.decodeFromJsonElement(ListingDataDto.serializer(), data)
        val entries = listing.posts.map { it.toSAnime() }
        val hasNext = listing.pagination?.let { (it.currentPage ?: 0) < (it.lastPage ?: 0) } ?: false
        return AnimesPage(entries, hasNext)
    }

    private fun fetchAllEpisodes(seriesId: Long): List<EpisodeDto> {
        val seasons = linkedSetOf<String>()
        val initialSeasons = listOf("1", "0")
        var firstResponse: EpisodesListDto? = null
        var firstSeasonUsed: String? = null

        for (candidate in initialSeasons) {
            val result = kotlin.runCatching { fetchEpisodesPage(seriesId, candidate, 1) }.getOrNull()
            if (result != null) {
                firstResponse = result
                firstSeasonUsed = candidate
                break
            }
        }

        val baseResponse = firstResponse ?: fetchEpisodesPage(seriesId, "1", 1)
        val availableSeasons = baseResponse.seasons?.map(Int::toString)
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(firstSeasonUsed ?: "1")

        seasons += availableSeasons

        val episodes = mutableListOf<EpisodeDto>()

        for (season in seasons) {
            val firstPage = if (season == firstSeasonUsed) baseResponse else fetchEpisodesPage(seriesId, season, 1)
            episodes += firstPage.posts

            val lastPage = firstPage.pagination?.lastPage ?: 1
            for (page in 2..lastPage) {
                episodes += fetchEpisodesPage(seriesId, season, page).posts
            }
        }

        return episodes
    }

    private fun fetchEpisodesPage(seriesId: Long, season: String, page: Int): EpisodesListDto {
        val builder = apiUrlBuilder("single", "episodes", "list")
            .addQueryParameter("_id", seriesId.toString())
            .addQueryParameter("season", season)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("postsPerPage", EPISODES_PER_PAGE.toString())

        val request = GET(builder.build(), headers)

        val response = client.newCall(request).execute()
        response.use {
            return it.parseData(EpisodesListDto.serializer())
        }
    }

    private fun apiUrlBuilder(vararg segments: String): HttpUrl.Builder {
        val apiBase = "$baseUrl/$API_PATH".toHttpUrl().newBuilder()
        segments.forEach(apiBase::addPathSegment)
        return apiBase
    }

    private fun <T> Response.parseData(serializer: KSerializer<T>): T {
        val element = parseDataElement()
        return json.decodeFromJsonElement(serializer, element)
    }

    private fun Response.parseDataElement(): JsonElement {
        val root = json.parseToJsonElement(body.string()).jsonObject
        if (root["error"]?.jsonPrimitive?.booleanOrNull == true) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Error desconocido"
            throw Exception(message)
        }
        return root["data"] ?: throw Exception("Respuesta vacía de la API")
    }

    private fun PostDto.toSAnime(): SAnime = SAnime.create().apply {
        title = this@toSAnime.title
        thumbnail_url = resolveThumbnailUrl()
        description = overview?.takeIf { it.isNotBlank() }
        author = originalTitle?.takeIf { it.isNotBlank() }
        status = SAnime.UNKNOWN
        setUrlWithoutDomain(buildAnimeUrl(this@toSAnime))
    }

    private fun EpisodeDto.toSEpisode(seriesId: Long, context: AnimeContext): SEpisode {
        val season = (seasonNumber ?: 1).coerceAtLeast(1)
        val number = (episodeNumber ?: 1).coerceAtLeast(1)
        val baseName = sequenceOf(name, title)
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: "Episodio $number"

        val displayName = "T${season}x$number - $baseName"
        val episodeFloat = "$season.${number.toString().padStart(3, '0')}".toFloatOrNull() ?: number.toFloat()
        val episodeUrl = buildEpisodeUrl(id, seriesId, context, season, number)

        return SEpisode.create().also { episode ->
            episode.name = displayName
            episode.episode_number = episodeFloat
            episode.date_upload = parseDate(date)
            episode.setUrlWithoutDomain(episodeUrl)
        }
    }

    private fun buildEpisodeUrl(episodeId: Long, seriesId: Long, context: AnimeContext, season: Int?, episode: Int?): String {
        val builder = StringBuilder("/player")
        builder.append("?postId=").append(episodeId)
        builder.append("&seriesId=").append(seriesId)
        builder.append("&type=").append(context.type)
        builder.append("&slug=").append(context.slug)
        season?.let { builder.append("&season=").append(it) }
        episode?.let { builder.append("&episode=").append(it) }
        return builder.toString()
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return DATE_FORMATS.firstNotNullOfOrNull { format ->
            runCatching { format.parse(value)?.time }.getOrNull()
        } ?: 0L
    }

    private fun PostDto.resolveThumbnailUrl(): String? {
        val lamovieHosts = primaryImageHosts()

        return sequenceOf(
            buildImageUrls(images?.poster, lamovieHosts),
            buildImageUrls(images?.poster, TMDB_POSTER_HOSTS),
            buildImageUrls(gallery, TMDB_GALLERY_HOSTS),
            buildImageUrls(images?.backdrop, lamovieHosts),
            buildImageUrls(images?.backdrop, TMDB_BACKDROP_HOSTS),
        )
            .flatMap(List<String>::asSequence)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .firstOrNull()
    }

    private fun buildImageUrls(raw: String?, hosts: List<String>): List<String> {
        if (raw.isNullOrBlank() || hosts.isEmpty()) return emptyList()

        val paths = raw
            .split('\n', ',', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (paths.isEmpty()) return emptyList()

        return paths.flatMap { path ->
            if (path.startsWith("http", ignoreCase = true)) {
                listOf(path)
            } else {
                val normalized = when {
                    path.startsWith("//") -> "https:${path.removePrefix("//")}"
                    path.startsWith("/") -> path
                    else -> "/$path"
                }

                if (normalized.startsWith("http", ignoreCase = true)) {
                    listOf(normalized)
                } else {
                    hosts.map { host -> host.trimEnd('/') + normalized }
                }
            }
        }
    }

    private fun primaryImageHosts(): List<String> =
        (STATIC_IMAGE_HOSTS + baseUrl)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.trimEnd('/') }
            .distinct()

    private fun buildAnimeUrl(post: PostDto): String {
        val slug = post.slug.ifBlank { post.id.toString() }
        return "/${post.postType}/$slug?postId=${post.id}"
    }

    private fun parseAnimeContext(url: String): AnimeContext {
        val httpUrl = if (url.startsWith("http")) url.toHttpUrlOrNull() else (baseUrl + url).toHttpUrlOrNull()
        val segments = httpUrl?.pathSegments?.filter { it.isNotBlank() } ?: emptyList()
        val type = segments.getOrNull(0) ?: preferredListingType()
        val slug = segments.getOrNull(1) ?: segments.lastOrNull() ?: ""
        val id = httpUrl?.queryParameter("postId")?.toLongOrNull()
        return AnimeContext(type, slug, id)
    }

    private fun parseEpisodeUrl(url: String): EpisodeContext {
        val httpUrl = if (url.startsWith("http")) {
            url.toHttpUrlOrNull()
        } else {
            (baseUrl + url).toHttpUrlOrNull()
        }
        val postId = httpUrl?.queryParameter("postId")?.toLongOrNull()
            ?: throw IllegalArgumentException("postId ausente en la URL del episodio")
        return EpisodeContext(postId)
    }

    private fun PlayerDataDto.parseEmbeds(): List<EmbedItem> {
        val embedsElement = embeds ?: return emptyList()
        val embedList = mutableListOf<EmbedItem>()

        when (embedsElement) {
            is JsonArray -> {
                embedsElement.forEach { element ->
                    element.toEmbedItem()?.let(embedList::add)
                }
            }
            is JsonObject -> {
                embedsElement.entries.forEach { (language, element) ->
                    val array = element as? JsonArray ?: return@forEach
                    array.forEach { item ->
                        item.toEmbedItem(language)?.let(embedList::add)
                    }
                }
            }
            else -> return emptyList()
        }

        return embedList.filter { it.url.isNotBlank() }
    }

    private fun JsonElement.toEmbedItem(language: String? = null): EmbedItem? {
        val obj = this as? JsonObject ?: return null
        val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val quality = obj["quality"]?.jsonPrimitive?.contentOrNull
        val lang = obj["lang"]?.jsonPrimitive?.contentOrNull ?: language
        return EmbedItem(server = server.trim(), url = url.trim(), quality = quality?.trim(), language = lang?.trim())
    }

    companion object {
        private const val API_PATH = "wp-api/v1"
        const val DEFAULT_LISTING_TYPE = "movies"
        private val SERIES_POST_TYPES = setOf("tvshows", "animes")
        private const val DEFAULT_POSTS_PER_PAGE = 24
        private const val EPISODES_PER_PAGE = 3000

        private val STATIC_IMAGE_HOSTS = listOf(
            "https://la.movie/wp-content/uploads/",
        )
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p"
        private val TMDB_POSTER_HOSTS = listOf(
            "$TMDB_IMAGE_BASE/w342",
            "$TMDB_IMAGE_BASE/w500",
            "$TMDB_IMAGE_BASE/original",
        )
        private val TMDB_BACKDROP_HOSTS = listOf(
            "$TMDB_IMAGE_BASE/w780",
            "$TMDB_IMAGE_BASE/w1280",
            "$TMDB_IMAGE_BASE/original",
        )
        private val TMDB_GALLERY_HOSTS = listOf(
            "$TMDB_IMAGE_BASE/w780",
            "$TMDB_IMAGE_BASE/w500",
            "$TMDB_IMAGE_BASE/original",
        )

        private val DATE_FORMATS = arrayOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        )

        private const val PREF_POPULAR_KEY = "preferred_popular_page_new"
        private const val PREF_POPULAR_DEFAULT = "movie"
        private val CONTENT_ENTRIES = arrayOf("Películas", "Series", "Anime")
        private val CONTENT_VALUES = arrayOf("movies", "tvshows", "animes")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val BASE_PREF_LATEST_KEY = "preferred_latest_page"
        private const val BASE_PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_LANGUAGE_KEY = BASE_PREF_SUB_KEY

        private const val LANGUAGE_CODE_ANY = "any"
        private const val LANGUAGE_CODE_UNKNOWN = "unknown"
        private const val LANGUAGE_CODE_LATINO = "latino"
        private const val LANGUAGE_CODE_CASTELLANO = "castellano"
        private const val LANGUAGE_CODE_SUB = "sub"
        private const val LANGUAGE_CODE_ENGLISH = "english"

        private const val PREF_LANGUAGE_DEFAULT = LANGUAGE_CODE_ANY
        private val PREF_LANGUAGE_ENTRIES = arrayOf(
            "Sin preferencia",
            "Latino",
            "Castellano",
            "Subtitulado",
            "Inglés",
        )
        private val PREF_LANGUAGE_VALUES = arrayOf(
            LANGUAGE_CODE_ANY,
            LANGUAGE_CODE_LATINO,
            LANGUAGE_CODE_CASTELLANO,
            LANGUAGE_CODE_SUB,
            LANGUAGE_CODE_ENGLISH,
        )

        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private const val SERVER_KEY_UNKNOWN = "unknown"
        private const val SERVER_KEY_DOOD = "dood"
        private const val SERVER_KEY_VOE = "voe"
        private const val SERVER_KEY_MP4UPLOAD = "mp4upload"
        private const val SERVER_KEY_STREAMHIDE = "streamhide"
        private const val SERVER_KEY_STREAMWISH = "streamwish"
        private const val SERVER_KEY_YOURUPLOAD = "yourupload"
        private const val SERVER_KEY_FILEMOON = "filemoon"
        private const val SERVER_KEY_GOODSTREAM = "goodstream"
        private const val SERVER_KEY_LAMOVIE = "lamovie"

        private val SERVER_KEYWORDS = mapOf(
            SERVER_KEY_DOOD to listOf("dood", "d000d", "doodstream", "doodcdn", "doodapi", "ds2play"),
            SERVER_KEY_VOE to listOf("voe", "voeunblock", "voecloud"),
            SERVER_KEY_MP4UPLOAD to listOf("mp4upload", "mp4u", "mp4-cdn"),
            SERVER_KEY_STREAMHIDE to listOf("streamhide", "shtcdn", "shtembed", "shtplayer"),
            SERVER_KEY_STREAMWISH to listOf("streamwish", "hlswish", "wishfast", "wishflix", "wishvid", "strwish"),
            SERVER_KEY_YOURUPLOAD to listOf("yourupload", "urupload", "yourcdn"),
            SERVER_KEY_FILEMOON to listOf("filemoon", "moonplayer", "mooncdn", "moonstream"),
            SERVER_KEY_GOODSTREAM to listOf("goodstream", "gdstream", "gdst"),
            SERVER_KEY_LAMOVIE to listOf("lamovie.link", "lamovie", "la.movie", "vimeos"),
        )

        private val LANGUAGE_LATINO_REGEX = Regex("\\b(lat|latino|latam|latinoamerica|español|espanol|esp-lat|es-lat|es_lat)\\b")
        private val LANGUAGE_CASTELLANO_REGEX = Regex("\\b(cast|castellano|españa|espana|es-es|esp-es)\\b")
        private val LANGUAGE_SUB_REGEX = Regex("\\b(sub|subs|subtitulad[ao]|subtitulado|subtitulos|vose)\\b")
        private val LANGUAGE_ENGLISH_REGEX = Regex("\\b(english|ingles|inglés|eng)\\b")

        private const val PREF_SERVER_KEY = "preferred_server_lamovie"
        private const val PREF_SERVER_DEFAULT = "auto"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "Sin preferencia",
            "DoodStream",
            "VOE",
            "MP4Upload",
            "StreamHide",
            "StreamWish",
            "YourUpload",
            "Filemoon",
            "GoodStream",
            "LaMovie (HLS)",
        )
        private val PREF_SERVER_VALUES = arrayOf(
            PREF_SERVER_DEFAULT,
            SERVER_KEY_DOOD,
            SERVER_KEY_VOE,
            SERVER_KEY_MP4UPLOAD,
            SERVER_KEY_STREAMHIDE,
            SERVER_KEY_STREAMWISH,
            SERVER_KEY_YOURUPLOAD,
            SERVER_KEY_FILEMOON,
            SERVER_KEY_GOODSTREAM,
            SERVER_KEY_LAMOVIE,
        )
    }

    private fun preferredListingType(): String {
        val stored = preferences.getString(PREF_POPULAR_KEY, PREF_POPULAR_DEFAULT) ?: PREF_POPULAR_DEFAULT
        return normalizeListingType(stored)
    }

    private fun normalizeListingType(raw: String): String {
        return when (raw.lowercase(Locale.US)) {
            "movie", "movies", "peliculas", "películas" -> "movies"
            "tv-show", "tvshows", "tv shows", "series" -> "tvshows"
            "anime", "animes" -> "animes"
            else -> raw.ifBlank { DEFAULT_LISTING_TYPE }
        }
    }

    private fun normalizeLanguagePreference(raw: String): String {
        return when (raw.lowercase(Locale.US)) {
            "any", "none", "sin preferencia", "todos" -> LANGUAGE_CODE_ANY
            "latino", "latam", "es-lat", "esp-lat", "es_lat" -> LANGUAGE_CODE_LATINO
            "castellano", "esp", "es-es", "españa", "esp-es", "spanish", "es" -> LANGUAGE_CODE_CASTELLANO
            "sub", "subs", "subtitulado", "subtitulos", "vose" -> LANGUAGE_CODE_SUB
            "english", "ingles", "inglés", "eng", "en" -> LANGUAGE_CODE_ENGLISH
            else -> raw.ifBlank { LANGUAGE_CODE_ANY }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_POPULAR_KEY
            title = "Tipo de contenido predeterminado"
            entries = CONTENT_ENTRIES
            entryValues = CONTENT_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_POPULAR_DEFAULT) ?: PREF_POPULAR_DEFAULT
            val normalized = normalizeListingType(stored)
            if (normalized != stored) {
                preferences.edit().putString(key, normalized).apply()
            }
            value = normalized

            setOnPreferenceChangeListener { _, newValue ->
                val mapped = normalizeListingType(newValue as String)
                preferences.edit().putString(key, mapped).apply()
                value = mapped
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = BASE_PREF_LATEST_KEY
            title = "Sección para últimas actualizaciones"
            entries = CONTENT_ENTRIES
            entryValues = CONTENT_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_POPULAR_DEFAULT) ?: PREF_POPULAR_DEFAULT
            val normalized = normalizeListingType(stored)
            if (normalized != stored) {
                preferences.edit().putString(key, normalized).apply()
            }
            value = normalized

            setOnPreferenceChangeListener { _, newValue ->
                val mapped = normalizeListingType(newValue as String)
                preferences.edit().putString(key, mapped).apply()
                value = mapped
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Calidad preferida"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
            value = stored

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Idioma preferido"
            entries = PREF_LANGUAGE_ENTRIES
            entryValues = PREF_LANGUAGE_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_LANGUAGE_DEFAULT) ?: PREF_LANGUAGE_DEFAULT
            val normalized = normalizeLanguagePreference(stored)
            if (normalized != stored) {
                preferences.edit().putString(key, normalized).apply()
            }
            value = normalized

            setOnPreferenceChangeListener { _, newValue ->
                val mapped = normalizeLanguagePreference(newValue as String)
                preferences.edit().putString(key, mapped).apply()
                value = mapped
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Servidor de video preferido"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            summary = "%s"

            val stored = preferences.getString(key, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
            value = stored

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }
}
