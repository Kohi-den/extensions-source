package eu.kanade.tachiyomi.animeextension.en.animekai

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup.parseBodyFragment
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AnimeKai : AnimeHttpSource(), ConfigurableAnimeSource {
    override val name = "AnimeKai"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl by lazy {
        val selected = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT
        if (selected == "custom") {
            preferences.getString(PREF_CUSTOM_DOMAIN_KEY, PREF_CUSTOM_DOMAIN_DEFAULT) ?: PREF_CUSTOM_DOMAIN_DEFAULT
        } else {
            selected
        }
    }

    override val client: OkHttpClient = network.client

    override val lang = "en"
    override val supportsLatest = true

    // ============================== Popular ===============================
    // This source does not have a popular anime page,
    // so we use search page with sort by trending.
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?keyword=&sort=trending&page=$page")
    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page")
    override fun latestUpdatesParse(response: Response): AnimesPage {
        // Use the unified parsing function for both latest and search
        return parseAnimesPage(response)
    }

    // ============================== Search Anime ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val safeQuery = URLEncoder.encode(query, "UTF-8")
        val searchParams = mutableListOf<String>()
        filters.forEach { filter ->
            if (filter is AnimeKaiFilters.KaiFilter) {
                searchParams.addAll(filter.getParams())
                return@forEach
            }
        }

        if (searchParams.contains("sort=auto")) {
            searchParams.remove("sort=auto")
            if (safeQuery.isNotEmpty()) {
                // Log.d("AnimeKai", "Search query detected, changing sort=auto to sort=most_relevance")
                searchParams.add("sort=most_relevance") // use most_relevance sort
            } else {
                // Log.d("AnimeKai", "No search query, changing sort=auto to sort=trending")
                searchParams.add("sort=trending")
            }
        }

        val filterQuerys = if (searchParams.isNotEmpty()) "&" + searchParams.joinToString("&") else ""
        // Log.d("AnimeKai", "Search URL: $baseUrl/browser?keyword=$safeQuery&page=$page$genreQuery")
        return GET("$baseUrl/browser?keyword=$safeQuery&page=$page$filterQuerys")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnimesPage(response)
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeKaiFilters.getFilterList()
    }

    // ============================== Anime Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            // Title
            if (preferences.getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT) == "en") {
                title = document.selectFirst("h1.title")?.text() ?: ""
            } else {
                title = document.selectFirst("h1.title")?.attr("data-jp") ?: ""
            }
            // Poster/Thumbnail
            thumbnail_url = document.selectFirst(".poster img")?.attr("src")
            // Description (main)
            description = document.selectFirst("div.desc.text-expand")?.text()
            // Genres
            genre = document.select("div.detail div:contains(Genres) span a").map { it.text() }.joinToString(", ")
            // Status
            status = when (document.selectFirst("div.detail div:contains(Status) span")?.text()?.trim()?.lowercase()) {
                "releasing" -> SAnime.ONGOING
                "finished" -> SAnime.COMPLETED
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            // Studios as author
            author = document.select("div.detail div:contains(Studios) span a span").map { it.text() }.joinToString(", ")
            // Producers as artist
            artist = document.select("div.detail div:contains(Producers) span a span").map { it.text() }.joinToString(", ")
        }
    }

    // ============================== Episodes ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        // from the page source, extract the animeID variable
        val document = response.asJsoup()
        val aniId = document.selectFirst("div.rate-box")?.attr("data-id")
            ?: throw Exception("animeID not found")

        // then use this url to make a request to get the token
        val token = get("${DECODE1_URL}$aniId").trim()
        // Log.d("AnimeKai", "Extracted token: $token")

        // from that response, extract the token and make a request to get episodes
        val resultHtml = getJsonValue(get("$baseUrl/ajax/episodes/list?ani_id=$aniId&_=$token", response.request.url.toString()), "result")

        // Parse the HTML fragment
        val episodesDoc = parseBodyFragment(resultHtml)
        val episodesDiv = episodesDoc.selectFirst("div.eplist.titles")

        val episodeElements = episodesDiv?.select("ul.range li > a[num][slug][token]") ?: emptyList()

        val episodes = episodeElements.map { ep ->
            SEpisode.create().apply {
                val isFiller = ep.hasClass("filler")
                val langs = ep.attr("langs")

                episode_number = ep.attr("num").toFloatOrNull() ?: 0f
                val episodeNum = ep.attr("num")
                val episodeTitle = ep.selectFirst("span[data-jp]")?.text()
                name = if (!episodeTitle.isNullOrBlank()) {
                    "Episode $episodeNum: $episodeTitle"
                } else {
                    "Episode $episodeNum"
                }
                val label = when (langs) {
                    "3", "2" -> "Sub & Dub "
                    "1" -> "Sub "
                    else -> ""
                } + if (isFiller) "Filler " else ""
                scanlator = label.trim()
                // instead of decoding the episode token, we set it in the url for later decoding upon fetching video links
                val extractedToken = ep.attr("token")
                setUrlWithoutDomain("${response.request.url}?token=$extractedToken")
            }
        }
        return episodes.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlParts = episode.url.split("?token=")
        val watchUrl = urlParts[0]
        val episodeToken = urlParts.getOrNull(1) ?: throw Exception("Token not found")

        // Get the secondary token from the worker endpoint
        val secondaryToken = get("${DECODE1_URL}$episodeToken").trim()

        // Fetch the episode server links list
        val resultHtml = getJsonValue(get("$baseUrl/ajax/links/list?token=$episodeToken&_=$secondaryToken", watchUrl), "result")

        val linksDoc = parseBodyFragment(resultHtml)
        val serverDivs = linksDoc.select("div.server-items")

        val serverGroups = mutableListOf<EpisodeServerGroup>()

        val enabledTypes = preferences.getStringSet(PREF_ENABLED_TYPES_KEY, PREF_ENABLED_TYPES_DEFAULT) ?: PREF_ENABLED_TYPES_DEFAULT
        // Log.d("AnimeKai", "Enabled types: $enabledTypes")
        for (serverDiv in serverDivs) {
            val type = serverDiv.attr("data-id")
            // Log.d("AnimeKai", "Found server type: $type")
            if (type !in enabledTypes) {
                // Log.d("AnimeKai", "Skipping: $type")
                continue
            }
            val serverSpans = serverDiv.select("span.server[data-lid]")

            val episodeServers = mutableListOf<EpisodeServer>()
            for (span in serverSpans) {
                val serverName = span.text()
                val serverId = span.attr("data-lid")
                val streamToken = get("${DECODE1_URL}$serverId").trim()

                val streamUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$streamToken"

                val streamJson = get(streamUrl, baseUrl)
                val encodedLink = getJsonValue(streamJson, "result").trim()

                // Log.d("AnimeKai", "Encoded link for $serverName: $encodedLink")
                val decryptedJson = get("${DECODE2_URL}$encodedLink")
                val decryptedLink = getJsonValue(decryptedJson, "url").trim()
                // Log.d("AnimeKai", "Decrypted link for $serverName: $decryptedLink")

                val epServer = EpisodeServer(serverName, decryptedLink)
                episodeServers.add(epServer)
            }
            val serverGroup = EpisodeServerGroup(type, episodeServers)
            serverGroups.add(serverGroup)
        }

        val extractor = Extractor(client)
        val videos = mutableListOf<Video>()

        serverGroups.forEach { group ->
            val typeDisplay = getTypeDisplayName(group.type)
            group.servers.forEach { server ->
                videos.addAll(
                    withContext(Dispatchers.Main) {
                        suspendCoroutine { continuation ->
                            extractor.extractVideosFromUrl(
                                url = server.streamUrl,
                                prefix = "$typeDisplay | ${server.serverName} | ",
                            ) { vids ->
                                continuation.resume(vids)
                            }
                        }
                    },
                )
            }
        }

        return videos
    }

    data class EpisodeServerGroup(
        val type: String, // "sub", "dub", "softsub"
        val servers: List<EpisodeServer>,
    )

    data class EpisodeServer(
        val serverName: String,
        val streamUrl: String,
    )

    // Video list sort
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val type = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(type) },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    // ============================== Extension Functions ===============================

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeElements = document.select("div.aitem")

        val animes = animeElements.mapNotNull { element ->
            if (!preferences.getBoolean(PREF_ADULT_KEY, PREF_ADULT_DEFAULT)) {
                // If user has disabled adult content, Skip anime if it has an adult tag
                val tagsDiv = element.selectFirst("div.tags")
                val isAdult = tagsDiv?.selectFirst("div.adult") != null
                if (isAdult) return@mapNotNull null
            }
            SAnime.create().apply {
                val url = element.selectFirst("a")!!.attr("href")
                setUrlWithoutDomain(url)
                val titleElement = element.selectFirst("a.title")
                if (preferences.getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT) == "en") {
                    this.title = titleElement?.attr("title") ?: ""
                } else {
                    this.title = titleElement?.attr("data-jp") ?: ""
                }
                val thumbnailUrl = element.select(".poster img").attr("data-src")
                this.thumbnail_url = thumbnailUrl

                // Log.v("AnimeKai", "Parsed Anime: $title, URL: $url, Thumbnail: $thumbnail_url"
            }
        }

        return AnimesPage(
            animes,
            hasNextPage = document.selectFirst("ul.pagination a[rel=next]") != null,
        )
    }

    private fun get(url: String, referer: String? = null): String {
        val builder = Request.Builder().url(url)
        if (referer != null) {
            builder.headers(Headers.headersOf("Referer", referer))
        }
        return client.newCall(builder.build()).execute().body.string()
    }

    private fun getJsonValue(json: String, key: String): String {
        return org.json.JSONObject(json).getString(key)
    }

    // ============================== Preferences ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val customDomainPref = EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_DOMAIN_KEY
            title = PREF_CUSTOM_DOMAIN_TITLE
            dialogTitle = PREF_CUSTOM_DOMAIN_TITLE
            setDefaultValue(PREF_CUSTOM_DOMAIN_DEFAULT)
            summary = preferences.getString(key, PREF_CUSTOM_DOMAIN_DEFAULT)
            setVisible(preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) == "custom")
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, "App Restart Required", Toast.LENGTH_SHORT).show()
                val value = newValue as String
                summary = value
                preferences.edit().putString(key, value).apply()
                true
            }
        }

        val domainPref = ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, "App Restart Required", Toast.LENGTH_SHORT).show()
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String

                customDomainPref.setVisible(entry == "custom")

                preferences.edit().putString(key, entry).apply()
                true
            }
        }

        val typePref = MultiSelectListPreference(screen.context).apply {
            key = PREF_ENABLED_TYPES_KEY
            title = PREF_ENABLED_TYPES_TITLE
            entries = PREF_ENABLED_TYPES_ENTRIES
            entryValues = PREF_ENABLED_TYPES_VALUES
            setDefaultValue(PREF_ENABLED_TYPES_DEFAULT) // Only HardSub and Dub by default
            // Helper to map values to entries
            fun getDisplayNames(selected: Set<String>?): String {
                if (selected == null) return ""
                return PREF_ENABLED_TYPES_VALUES.mapIndexedNotNull { idx, value ->
                    if (selected.contains(value)) PREF_ENABLED_TYPES_ENTRIES[idx] else null
                }.joinToString(", ")
            }
            val selected = preferences.getStringSet(key, PREF_ENABLED_TYPES_DEFAULT)
            summary = getDisplayNames(selected)
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as Set<String>
                summary = getDisplayNames(selected)
                preferences.edit().putStringSet(key, selected).apply()
                true
            }
        }

        val adultPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT_KEY
            title = PREF_ADULT_TITLE
            summary = "Enable to show adult anime in search and popular (may contain NSFW content)"
            setDefaultValue(PREF_ADULT_DEFAULT)
            isChecked = preferences.getBoolean(key, PREF_ADULT_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val isChecked = newValue as Boolean
                preferences.edit().putBoolean(key, isChecked).apply()
                true
            }
        }

        val titlePref = ListPreference(screen.context).apply {
            key = PREF_TITLE_KEY
            title = PREF_TITLE_TITLE
            entries = PREF_TITLE_ENTRIES
            entryValues = PREF_TITLE_VALUES
            setDefaultValue(PREF_TITLE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, "Clear source database to reload all titles.", Toast.LENGTH_SHORT).show()
                val selected = newValue as String
                preferences.edit().putString(key, selected).apply()
                true
            }
        }

        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).apply()
                true
            }
        }

        val subPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).apply()
                true
            }
        }

        val serverPref = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).apply()
                true
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(customDomainPref)
        screen.addPreference(adultPref)
        screen.addPreference(typePref)
        screen.addPreference(titlePref)
        screen.addPreference(qualityPref)
        screen.addPreference(subPref)
        screen.addPreference(serverPref)
    }

    // Helper to map type to display name
    private fun getTypeDisplayName(type: String): String {
        return when (type) {
            "sub" -> "Subtitled"
            "dub" -> "Dubbed"
            "softsub" -> "Softsubed"
            else -> type.replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        // Courtesy of 50n50 for the decoding api.
        val DECODE1_URL = "https://ilovekai.simplepostrequest.workers.dev/?ilovefeet="
        val DECODE2_URL = "https://ilovekai.simplepostrequest.workers.dev/?ilovearmpits="

        val PREF_DOMAIN_KEY = "preffered_domain"
        val PREF_DOMAIN_TITLE = "Preferred Domain (requires app restart)"
        val PREF_DOMAIN_DEFAULT = "https://animekai.bz"
        val PREF_DOMAIN_ENTRIES = arrayOf("animekai.to", "animekai.bz", "animekai.cc", "animekai.ac", "Custom")
        val PREF_DOMAIN_VALUES = arrayOf("https://animekai.to", "https://animekai.bz", "https://animekai.cc", "https://animekai.ac", "custom")

        val PREF_CUSTOM_DOMAIN_KEY = "custom_domain"
        val PREF_CUSTOM_DOMAIN_TITLE = "Custom Domain (requires app restart)"
        val PREF_CUSTOM_DOMAIN_DEFAULT = "https://animekai.to"

        val PREF_ENABLED_TYPES_KEY = "enabled_types"
        val PREF_ENABLED_TYPES_TITLE = "Enabled Video Types (Less is faster)"
        val PREF_ENABLED_TYPES_ENTRIES = arrayOf("Sub", "Dub", "Soft Sub")
        val PREF_ENABLED_TYPES_VALUES = arrayOf("sub", "dub", "softsub")
        val PREF_ENABLED_TYPES_DEFAULT = setOf("sub", "dub", "softsub")

        val PREF_ADULT_KEY = "show_adult"
        val PREF_ADULT_TITLE = "Show Adult Anime"
        val PREF_ADULT_DEFAULT = false

        val PREF_TITLE_KEY = "title_preference"
        val PREF_TITLE_TITLE = "Title Display Preference"
        val PREF_TITLE_ENTRIES = arrayOf("English", "Romaji")
        val PREF_TITLE_VALUES = arrayOf("en", "romaji")
        val PREF_TITLE_DEFAULT = "en"

        val PREF_QUALITY_KEY = "preferred_quality"
        val PREF_QUALITY_TITLE = "Preferred Quality"
        val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "360p")
        val PREF_QUALITY_VALUES = arrayOf("1080", "720", "360")
        val PREF_QUALITY_DEFAULT = "1080"

        val PREF_SUB_KEY = "preferred_sub"
        val PREF_SUB_TITLE = "Preferred Sub/Dub"
        val PREF_SUB_ENTRIES = arrayOf("Sub", "Dub", "Soft Sub")
        val PREF_SUB_VALUES = arrayOf("Subtitled", "Dubbed", "Softsubed")
        val PREF_SUB_DEFAULT = "Subtitled"

        val PREF_SERVER_KEY = "preferred_server"
        val PREF_SERVER_TITLE = "Preferred Server"
        val PREF_SERVER_ENTRIES = arrayOf("Server 1", "Server 2")
        val PREF_SERVER_VALUES = arrayOf("Server 1", "Server 2")
        val PREF_SERVER_DEFAULT = "Server 1"
    }
}
