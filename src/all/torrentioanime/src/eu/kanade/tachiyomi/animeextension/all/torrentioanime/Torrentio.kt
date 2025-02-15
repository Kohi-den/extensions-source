package eu.kanade.tachiyomi.animeextension.all.torrentioanime

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AniZipResponse
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMeta
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.AnilistMetaLatest
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.DetailsById
import eu.kanade.tachiyomi.animeextension.all.torrentioanime.dto.StreamDataTorrent
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Torrentio : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Torrentio Anime (Torrent / Debrid)"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // ============================== Anilist API Request ===================
    private fun makeGraphQLRequest(query: String, variables: String): Request {
        val requestBody = FormBody.Builder()
            .add("query", query)
            .add("variables", variables)
            .build()

        return POST("https://graphql.anilist.co", body = requestBody)
    }

    private fun parseSearchJson(jsonLine: String?, isLatestQuery: Boolean = false): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val metaData: Any = if (!isLatestQuery) {
            json.decodeFromString<AnilistMeta>(jsonData)
        } else {
            json.decodeFromString<AnilistMetaLatest>(jsonData)
        }

        val mediaList = when (metaData) {
            is AnilistMeta -> metaData.data?.page?.media.orEmpty()
            is AnilistMetaLatest -> metaData.data?.page?.airingSchedules.orEmpty().map { it.media }
            else -> emptyList()
        }

        val hasNextPage: Boolean = when (metaData) {
            is AnilistMeta -> metaData.data?.page?.pageInfo?.hasNextPage ?: false
            is AnilistMetaLatest -> metaData.data?.page?.pageInfo?.hasNextPage ?: false
            else -> false
        }

        val animeList = mediaList
            .filterNot { (it?.countryOfOrigin == "CN" || it?.isAdult == true) && isLatestQuery }
            .map { media ->
                val anime = SAnime.create().apply {
                    url = media?.id.toString()
                    title = when (preferences.getString(PREF_TITLE_KEY, "romaji")) {
                        "romaji" -> media?.title?.romaji.toString()
                        "english" -> (media?.title?.english?.takeIf { it.isNotBlank() } ?: media?.title?.romaji).toString()
                        "native" -> media?.title?.native.toString()
                        else -> ""
                    }
                    thumbnail_url = media?.coverImage?.extraLarge
                    description = media?.description
                        ?.replace(Regex("<br><br>"), "\n")
                        ?.replace(Regex("<.*?>"), "")
                        ?: "No Description"

                    status = when (media?.status) {
                        "RELEASING" -> SAnime.ONGOING
                        "FINISHED" -> SAnime.COMPLETED
                        "HIATUS" -> SAnime.ON_HIATUS
                        "NOT_YET_RELEASED" -> SAnime.LICENSED
                        else -> SAnime.UNKNOWN
                    }

                    // Extracting tags
                    val tagsList = media?.tags?.mapNotNull { it.name }.orEmpty()
                    // Extracting genres
                    val genresList = media?.genres.orEmpty()
                    genre = (tagsList + genresList).toSet().sorted().joinToString()

                    // Extracting studios
                    val studiosList = media?.studios?.nodes?.mapNotNull { it.name }.orEmpty()
                    author = studiosList.sorted().joinToString()

                    initialized = true
                }
                anime
            }

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "TRENDING_DESC",
                "status": ["FINISHED", "RELEASING"]
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "TIME_DESC"
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistLatestQuery(), variables)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData, true)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniListFilters.getSearchParameters(filters)
        val variablesObject = buildJsonObject {
            put("page", page)
            put("perPage", 30)
            put("sort", params.sort)
            if (query.isNotBlank()) put("search", query)

            if (params.genres.isNotEmpty()) {
                putJsonArray("genres") {
                    params.genres.forEach { add(it) }
                }
            }

            if (params.format.isNotEmpty()) {
                putJsonArray("format") {
                    params.format.forEach { add(it) }
                }
            }

            if (params.season.isBlank() && params.year.isNotBlank()) {
                put("year", "${params.year}%")
            }

            if (params.season.isNotBlank() && params.year.isBlank()) {
                throw Exception("Year cannot be blank if season is set")
            }

            if (params.season.isNotBlank() && params.year.isNotBlank()) {
                put("season", params.season)
                put("seasonYear", params.year)
            }

            if (params.status.isNotBlank()) {
                putJsonArray("status") {
                    params.status.forEach { add(it.toString()) }
                }
            }
        }

        val variables = json.encodeToString(variablesObject)

        println(anilistQuery())
        println(variables)

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniListFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val variables = """{"id": ${anime.url}}"""

        val metaData = runCatching {
            json.decodeFromString<DetailsById>(client.newCall(makeGraphQLRequest(getDetailsQuery(), variables)).execute().body.string())
        }.getOrNull()?.data?.media

        anime.title = metaData?.title?.let { title ->
            when (preferences.getString(PREF_TITLE_KEY, "romaji")) {
                "romaji" -> title.romaji
                "english" -> (metaData.title.english?.takeIf { it.isNotBlank() } ?: metaData.title.romaji).toString()
                "native" -> title.native
                else -> ""
            }
        } ?: ""

        anime.thumbnail_url = metaData?.coverImage?.extraLarge

        anime.description = buildString {
            append(
                metaData?.description?.let {
                    Jsoup.parseBodyFragment(
                        it.replace("<br>\n", "br2n")
                            .replace("<br>", "br2n")
                            .replace("\n", "br2n"),
                    ).text().replace("br2n", "\n")
                },
            )
            append("\n\n")
            if (!(metaData?.season == null && metaData?.seasonYear == null)) {
                append("Release: ${ metaData.season ?: ""} ${ metaData.seasonYear ?: ""}")
            }
            metaData?.format?.let { append("\nType: ${metaData.format}") }
            metaData?.episodes?.let { append("\nTotal Episode Count: ${metaData.episodes}") }
        }.trim()

        anime.status = when (metaData?.status) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "HIATUS" -> SAnime.ON_HIATUS
            "NOT_YET_RELEASED" -> SAnime.LICENSED
            else -> SAnime.UNKNOWN
        }

        // Extracting tags, genres, and studios
        val tagsList = metaData?.tags?.mapNotNull { it.name } ?: emptyList()
        val genresList = metaData?.genres ?: emptyList()
        val studiosList = metaData?.studios?.nodes?.mapNotNull { it.name } ?: emptyList()

        anime.genre = (tagsList + genresList).toSet().sorted().joinToString()
        anime.author = studiosList.sorted().joinToString()

        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        return GET("https://api.ani.zip/mappings?anilist_id=${anime.url}")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        val aniZipResponse = json.decodeFromString<AniZipResponse>(responseString)

        return when (aniZipResponse.mappings?.type) {
            "TV" -> {
                aniZipResponse.episodes
                    ?.let { episodes ->
                        if (preferences.getBoolean(UPCOMING_EP_KEY, UPCOMING_EP_DEFAULT)) {
                            episodes
                        } else {
                            episodes.filter { (_, episode) -> (episode?.airDate?.let { parseDate(it) } ?: 0L) <= System.currentTimeMillis() }
                        }
                    }
                    ?.mapNotNull { (_, episode) ->
                        val episodeNumber = runCatching { episode?.episode?.toFloat() }.getOrNull()

                        if (episodeNumber == null) {
                            return@mapNotNull null
                        }

                        val title = episode?.title?.get("en")

                        SEpisode.create().apply {
                            episode_number = episodeNumber
                            url = "/stream/series/kitsu:${aniZipResponse.mappings.kitsuId}:${String.format(Locale.ENGLISH, "%.0f", episodeNumber)}.json"
                            date_upload = episode?.airDate?.let { parseDate(it) } ?: 0L
                            name = if (title == null) "Episode ${episode?.episode}" else "Episode ${episode.episode}: $title"
                            scanlator = (episode?.airDate?.let { parseDate(it) } ?: 0L).takeIf { it > System.currentTimeMillis() }?.let { "Upcoming" } ?: ""
                        }
                    }.orEmpty().reversed()
            }

            "MOVIE" -> {
                val dateUpload = if (!aniZipResponse.episodes.isNullOrEmpty()) {
                    aniZipResponse.episodes["1"]?.airDate?.let { parseDate(it) } ?: 0L
                } else {
                    0L
                }

                listOf(
                    SEpisode.create().apply {
                        episode_number = 1.0F
                        url = "/stream/movie/kitsu:${aniZipResponse.mappings.kitsuId}.json"
                        name = "Movie"
                        date_upload = dateUpload
                    },
                ).reversed()
            }

            else -> emptyList()
        }
    }

    private fun parseDateTime(dateStr: String): Long {
        return runCatching { DATE_TIME_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val mainURL = buildString {
            append("$baseUrl/")

            val appendQueryParam: (String, Set<String>?) -> Unit = { key, values ->
                values?.takeIf { it.isNotEmpty() }?.let {
                    append("$key=${it.filter(String::isNotBlank).joinToString(",")}|")
                }
            }

            appendQueryParam("providers", preferences.getStringSet(PREF_PROVIDER_KEY, PREF_PROVIDERS_DEFAULT))
            appendQueryParam("language", preferences.getStringSet(PREF_LANG_KEY, PREF_LANG_DEFAULT))
            appendQueryParam("qualityfilter", preferences.getStringSet(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT))

            val sortKey = preferences.getString(PREF_SORT_KEY, "quality")
            appendQueryParam("sort", sortKey?.let { setOf(it) })

            val token = preferences.getString(PREF_TOKEN_KEY, null)
            val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

            when {
                token.isNullOrBlank() && debridProvider != "none" -> {
                    handler.post {
                        context.let {
                            Toast.makeText(
                                it,
                                "Kindly input the debrid token in the extension settings.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    throw UnsupportedOperationException()
                }
                !token.isNullOrBlank() && debridProvider != "none" -> append("$debridProvider=$token|")
            }
            append(episode.url)
        }.removeSuffix("|")
        return GET(mainURL)
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body.string()
        val streamList = json.decodeFromString<StreamDataTorrent>(responseString)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

        val animeTrackers = """http://nyaa.tracker.wf:7777/announce,
            http://anidex.moe:6969/announce,http://tracker.anirena.com:80/announce,
            udp://tracker.uw0.xyz:6969/announce,
            http://share.camoe.cn:8080/announce,
            http://t.nyaatracker.com:80/announce,
            udp://47.ip-51-68-199.eu:6969/announce,
            udp://9.rarbg.me:2940,
            udp://9.rarbg.to:2820,
            udp://exodus.desync.com:6969/announce,
            udp://explodie.org:6969/announce,
            udp://ipv4.tracker.harry.lu:80/announce,
            udp://open.stealth.si:80/announce,
            udp://opentor.org:2710/announce,
            udp://opentracker.i2p.rocks:6969/announce,
            udp://retracker.lanta-net.ru:2710/announce,
            udp://tracker.cyberia.is:6969/announce,
            udp://tracker.dler.org:6969/announce,
            udp://tracker.ds.is:6969/announce,
            udp://tracker.internetwarriors.net:1337,
            udp://tracker.openbittorrent.com:6969/announce,
            udp://tracker.opentrackr.org:1337/announce,
            udp://tracker.tiny-vps.com:6969/announce,
            udp://tracker.torrent.eu.org:451/announce,
            udp://valakas.rollo.dnsabr.com:2710/announce,
            udp://www.torrent.eu.org:451/announce,
            ${fetchTrackers().split("\n").joinToString(",")}
        """.trimIndent()

        return streamList.streams?.map { stream ->
            val urlOrHash =
                if (debridProvider == "none") {
                    val trackerList = animeTrackers.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString("&tr=")
                    "magnet:?xt=urn:btih:${stream.infoHash}&dn=${stream.infoHash}&tr=$trackerList&index=${stream.fileIdx}"
                } else {
                    stream.url ?: ""
                }
            Video(urlOrHash, ((stream.name?.replace("Torrentio\n", "") ?: "") + "\n" + stream.title), urlOrHash)
        }.orEmpty()
    }

    override fun List<Video>.sort(): List<Video> {
        val isDub = preferences.getBoolean(IS_DUB_KEY, IS_DUB_DEFAULT)
        val isEfficient = preferences.getBoolean(IS_EFFICIENT_KEY, IS_EFFICIENT_DEFAULT)

        return sortedWith(
            compareBy(
                { Regex("\\[(.+?) download]").containsMatchIn(it.quality) },
                { isDub && !it.quality.contains("dubbed", true) },
                { isEfficient && !arrayOf("hevc", "265", "av1").any { q -> it.quality.contains(q, true) } },
            ),
        )
    }

    private fun fetchTrackers(): String {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Unexpected code $response")
            return response.body.string().trim()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Debrid provider
        ListPreference(screen.context).apply {
            key = PREF_DEBRID_KEY
            title = "Debrid Provider"
            entries = PREF_DEBRID_ENTRIES
            entryValues = PREF_DEBRID_VALUES
            setDefaultValue("none")
            summary = "Choose 'None' for Torrent. If you select a Debrid provider, enter your token key. No token key is needed if 'None' is selected."

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        // Token
        EditTextPreference(screen.context).apply {
            key = PREF_TOKEN_KEY
            title = "Token"
            setDefaultValue(PREF_TOKEN_DEFAULT)
            summary = PREF_TOKEN_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).trim().ifBlank { PREF_TOKEN_DEFAULT }
                    Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)

        // Provider
        MultiSelectListPreference(screen.context).apply {
            key = PREF_PROVIDER_KEY
            title = "Enable/Disable Providers"
            entries = PREF_PROVIDERS
            entryValues = PREF_PROVIDERS_VALUE
            setDefaultValue(PREF_PROVIDERS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        // Exclude Qualities
        MultiSelectListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Exclude Qualities/Resolutions"
            entries = PREF_QUALITY
            entryValues = PREF_QUALITY_VALUE
            setDefaultValue(PREF_QUALITY_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        // Priority foreign language
        MultiSelectListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Priority foreign language"
            entries = PREF_LANG
            entryValues = PREF_LANG_VALUE
            setDefaultValue(PREF_LANG_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        // Sorting
        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Sorting"
            entries = PREF_SORT_ENTRIES
            entryValues = PREF_SORT_VALUES
            setDefaultValue("quality")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        // Title handler
        ListPreference(screen.context).apply {
            key = PREF_TITLE_KEY
            title = "Preferred Title"
            entries = PREF_TITLE_ENTRIES
            entryValues = PREF_TITLE_VALUES
            setDefaultValue("romaji")

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = UPCOMING_EP_KEY
            title = "Show Upcoming Episodes"
            setDefaultValue(UPCOMING_EP_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_DUB_KEY
            title = "Dubbed Video Priority"
            setDefaultValue(IS_DUB_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_EFFICIENT_KEY
            title = "Efficient Video Priority"
            setDefaultValue(IS_EFFICIENT_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Codec: (HEVC / x265)  & AV1. High-quality video with less data usage."
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        // Token
        private const val PREF_TOKEN_KEY = "token"
        private const val PREF_TOKEN_DEFAULT = ""
        private const val PREF_TOKEN_SUMMARY = "Exclusive to Debrid providers; not intended for Torrents."

        // Debrid
        private const val PREF_DEBRID_KEY = "debrid_provider"
        private val PREF_DEBRID_ENTRIES = arrayOf(
            "None",
            "RealDebrid",
            "Premiumize",
            "AllDebrid",
            "DebridLink",
            "Offcloud",
            "TorBox",
        )
        private val PREF_DEBRID_VALUES = arrayOf(
            "none",
            "realdebrid",
            "premiumize",
            "alldebrid",
            "debridlink",
            "offcloud",
            "torbox",
        )

        // Sort
        private const val PREF_SORT_KEY = "sorting_link"
        private val PREF_SORT_ENTRIES = arrayOf(
            "By quality then seeders",
            "By quality then size",
            "By seeders",
            "By size",
        )
        private val PREF_SORT_VALUES = arrayOf(
            "quality",
            "qualitysize",
            "seeders",
            "size",

        )

        // Provider
        private const val PREF_PROVIDER_KEY = "provider_selection"
        private val PREF_PROVIDERS = arrayOf(
            "YTS",
            "EZTV",
            "RARBG",
            "1337x",
            "ThePirateBay",
            "KickassTorrents",
            "TorrentGalaxy",
            "MagnetDL",
            "HorribleSubs",
            "NyaaSi",
            "TokyoTosho",
            "AniDex",
            "🇷🇺 Rutor",
            "🇷🇺 Rutracker",
            "🇵🇹 Comando",
            "🇵🇹 BluDV",
            "🇫🇷 Torrent9",
            "🇪🇸 MejorTorrent",
            "🇲🇽 Cinecalidad",
            "🇮🇹 ilCorsaroNero",
            "🇪🇸 Wolfmax4k",
        )

        private val PREF_PROVIDERS_VALUE = arrayOf(
            "yts",
            "eztv",
            "rarbg",
            "1337x",
            "thepiratebay",
            "kickasstorrents",
            "torrentgalaxy",
            "magnetdl",
            "horriblesubs",
            "nyaasi",
            "tokyotosho",
            "anidex",
            "rutor",
            "rutracker",
            "comando",
            "bludv",
            "torrent9",
            "mejortorrent",
            "cinecalidad",
            "ilcorsaronero",
            "wolfmax4k",
        )

        private val PREF_DEFAULT_PROVIDERS_VALUE = arrayOf(
            "yts",
            "eztv",
            "rarbg",
            "1337x",
            "thepiratebay",
            "kickasstorrents",
            "torrentgalaxy",
            "magnetdl",
            "horriblesubs",
            "nyaasi",
            "tokyotosho",
            "anidex",
        )
        private val PREF_PROVIDERS_DEFAULT = PREF_DEFAULT_PROVIDERS_VALUE.toSet()

        // Qualities/Resolutions
        private const val PREF_QUALITY_KEY = "quality_selection"
        private val PREF_QUALITY = arrayOf(
            "BluRay REMUX",
            "HDR/HDR10+/Dolby Vision",
            "Dolby Vision",
            "Dolby Vision + HDR",
            "3D",
            "Non 3D (DO NOT SELECT IF NOT SURE)",
            "4k",
            "1080p",
            "720p",
            "480p",
            "Other (DVDRip/HDRip/BDRip...)",
            "Screener",
            "Cam",
            "Unknown",
        )

        private val PREF_QUALITY_VALUE = arrayOf(
            "brremux",
            "hdrall",
            "dolbyvision",
            "dolbyvisionwithhdr",
            "threed",
            "nonthreed",
            "4k",
            "1080p",
            "720p",
            "480p",
            "other",
            "scr",
            "cam",
            "unknown",
        )

        private val PREF_DEFAULT_QUALITY_VALUE = arrayOf(
            "720p",
            "480p",
            "other",
            "scr",
            "cam",
            "unknown",
        )

        private val PREF_QUALITY_DEFAULT = PREF_DEFAULT_QUALITY_VALUE.toSet()

        // Qualities/Resolutions
        private const val PREF_LANG_KEY = "lang_selection"
        private val PREF_LANG = arrayOf(
            "🇯🇵 Japanese",
            "🇷🇺 Russian",
            "🇮🇹 Italian",
            "🇵🇹 Portuguese",
            "🇪🇸 Spanish",
            "🇲🇽 Latino",
            "🇰🇷 Korean",
            "🇨🇳 Chinese",
            "🇹🇼 Taiwanese",
            "🇫🇷 French",

            "🇩🇪 German",
            "🇳🇱 Dutch",
            "🇮🇳 Hindi",
            "🇮🇳 Telugu",
            "🇮🇳 Tamil",
            "🇵🇱 Polish",
            "🇱🇹 Lithuanian",
            "🇱🇻 Latvian",
            "🇪🇪 Estonian",
            "🇨🇿 Czech",

            "🇸🇰 Slovakian",
            "🇸🇮 Slovenian",
            "🇭🇺 Hungarian",
            "🇷🇴 Romanian",
            "🇧🇬 Bulgarian",
            "🇷🇸 Serbian",
            "🇭🇷 Croatian",
            "🇺🇦 Ukrainian",
            "🇬🇷 Greek",
            "🇩🇰 Danish",

            "🇫🇮 Finnish",
            "🇸🇪 Swedish",
            "🇳🇴 Norwegian",
            "🇹🇷 Turkish",
            "🇸🇦 Arabic",
            "🇮🇷 Persian",
            "🇮🇱 Hebrew",
            "🇻🇳 Vietnamese",
            "🇮🇩 Indonesian",
            "🇲🇾 Malay",

            "🇹🇭 Thai",
        )
        private val PREF_LANG_VALUE = arrayOf(
            "japanese",
            "russian",
            "italian",
            "portuguese",
            "spanish",
            "latino",
            "korean",
            "chinese",
            "taiwanese",
            "french",

            "german",
            "dutch",
            "hindi",
            "telugu",
            "tamil",
            "polish",
            "lithuanian",
            "latvian",
            "estonian",
            "czech",

            "slovakian",
            "slovenian",
            "hungarian",
            "romanian",
            "bulgarian",
            "serbian",
            "croatian",
            "ukrainian",
            "greek",
            "danish",

            "finnish",
            "swedish",
            "norwegian",
            "turkish",
            "arabic",
            "persian",
            "hebrew",
            "vietnamese",
            "indonesian",
            "malay",

            "thai",

        )

        private val PREF_LANG_DEFAULT = setOf<String>()

        // Title
        private const val PREF_TITLE_KEY = "pref_title"
        private val PREF_TITLE_ENTRIES = arrayOf(
            "Romaji",
            "English",
            "Native",
        )
        private val PREF_TITLE_VALUES = arrayOf(
            "romaji",
            "english",
            "native",
        )

        private const val UPCOMING_EP_KEY = "upcoming_ep"
        private const val UPCOMING_EP_DEFAULT = false

        private const val IS_DUB_KEY = "dubbed"
        private const val IS_DUB_DEFAULT = false

        private const val IS_EFFICIENT_KEY = "efficient"
        private const val IS_EFFICIENT_DEFAULT = false

        private val DATE_TIME_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
