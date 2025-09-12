package eu.kanade.tachiyomi.animeextension.en.animekai

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
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
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup.parseBodyFragment
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
        val searchParams = mutableListOf<String>()
        filters.forEach { filter ->
            if (filter is AnimeKaiFilters.TypeGroup) {
                filter.state.forEach { type ->
                    when (type.state) {
                        true -> searchParams.add("type[]=${type.id}")
                        else -> {
                            // Ignore false state
                        }
                    }
                }
                return@forEach
            }
            if (filter is AnimeKaiFilters.GenreGroup) {
                filter.state.forEach { genre ->
                    when (genre.state) {
                        AnimeFilter.TriState.STATE_INCLUDE -> searchParams.add("genre[]=${genre.id}")
                        AnimeFilter.TriState.STATE_EXCLUDE -> searchParams.add("genre[]=-${genre.id}")
                        else -> {
                            // Ignore STATE_IGNORE
                        }
                    }
                }
                return@forEach
            }
            if (filter is AnimeKaiFilters.StatusGroup) {
                filter.state.forEach { status ->
                    when (status.state) {
                        true -> searchParams.add("status[]=${status.id}")
                        else -> {
                            // Ignore false state
                        }
                    }
                }
                return@forEach
            }
            if (filter is AnimeKaiFilters.SortSelector) {
                val sortOrdinal = filter.state
                val sortOption = AnimeKaiFilters.SortOption.values()[sortOrdinal]
                searchParams.add("sort=${sortOption.id}")
                return@forEach
            }
        }
        val genreQuery = if (searchParams.isNotEmpty()) "&" + searchParams.joinToString("&") else ""
        return GET("$baseUrl/browser?keyword=$query&page=$page$genreQuery")
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
            title = document.selectFirst("h1.title")?.text() ?: ""
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
        Log.d("AnimeKai", "Extracted aniID: $aniId")

        // then use this url to make a request to get the token
        val tokenUrl = "https://ilovekai.simplepostrequest.workers.dev/?ilovefeet=$aniId"
        val tokenRequest = Request.Builder()
            .url(tokenUrl)
            .headers(Headers.headersOf("Referer", response.request.url.toString()))
            .build()
        val tokenResponse = client.newCall(tokenRequest).execute()
        val token = tokenResponse.body.string().trim()
        Log.d("AnimeKai", "Extracted token: $token")

        // from that response, extract the token and make a request to get episodes
        val episodesUrl = "$baseUrl/ajax/episodes/list?ani_id=$aniId&_=$token"
        val episodesRequest = Request.Builder()
            .url(episodesUrl)
            .headers(Headers.headersOf("Referer", response.request.url.toString()))
            .build()
        val episodesResponse = client.newCall(episodesRequest).execute()
        val episodesJson = episodesResponse.body.string()
        Log.d("AnimeKai", "Episodes JSON Response: $episodesJson")

        // Parse JSON and extract the 'result' field
        val resultHtml = try {
            org.json.JSONObject(episodesJson).getString("result")
        } catch (e: Exception) {
            Log.w("AnimeKai", "Failed to parse episodes JSON: ${e.message}")
            ""
        }
        // Log.d("AnimeKai", "Extracted result HTML: $resultHtml")

        // Parse the HTML fragment
        val episodesDoc = parseBodyFragment(resultHtml)
        val episodesDiv = episodesDoc.selectFirst("div.eplist.titles")
        // Log.d("AnimeKai", "Parsed episodesDiv: $episodesDiv")

        // Select all episode <a> elements
        val episodeElements = episodesDiv?.select("ul.range li > a[num][slug][token]") ?: emptyList()
        Log.d("AnimeKai", "Found ${episodeElements.size} episode elements")

        val episodes = episodeElements.map { ep ->
            SEpisode.create().apply {
                episode_number = ep.attr("num").toFloatOrNull() ?: 0f
                val episodeNum = ep.attr("num")
                val episodeTitle = ep.selectFirst("span[data-jp]")?.text()
                name = if (!episodeTitle.isNullOrBlank()) {
                    "Episode $episodeNum: $episodeTitle"
                } else {
                    "Episode $episodeNum"
                }
                // instead of decoding the episode token, we set it in the url for later decoding upon fetching video links
                val extractedToken = ep.attr("token")
                setUrlWithoutDomain("${response.request.url}?token=$extractedToken")
            }
        }
        Log.d("AnimeKai", "Returning ${episodes.size} episodes")
        return episodes.reversed()
    }

    // ============================ Video Links =============================

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

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // Log.d("AnimeKai", "Starting getVideoList for episode: ${episode.name}, url: ${episode.url}")
        val urlParts = episode.url.split("?token=")
        val watchUrl = urlParts[0]
        val episodeToken = urlParts.getOrNull(1) ?: throw Exception("Token not found")
        // Log.d("AnimeKai", "Parsed watchUrl: $watchUrl, episodeToken: $episodeToken")

        // Get the secondary token from the worker endpoint
        val secondaryToken = get("https://ilovekai.simplepostrequest.workers.dev/?ilovefeet=$episodeToken").trim()
        // Log.d("AnimeKai", "Secondary token: $secondaryToken")

        // Fetch the episode server links list
        val resultHtml = getJsonValue(get("$baseUrl/ajax/links/list?token=$episodeToken&_=$secondaryToken", watchUrl), "result")

        val linksDoc = parseBodyFragment(resultHtml)
        val serverDivs = linksDoc.select("div.server-items")
        // Log.d("AnimeKai", "Found ${serverDivs.size} server divs")

        val serverGroups = mutableListOf<EpisodeServerGroup>()
        for (serverDiv in serverDivs) {
            val type = serverDiv.attr("data-id")
            val serverSpans = serverDiv.select("span.server[data-lid]")

            val episodeServers = mutableListOf<EpisodeServer>()
            for (span in serverSpans) {
                val serverName = span.text()
                val serverId = span.attr("data-lid")
                val streamToken = get("https://ilovekai.simplepostrequest.workers.dev/?ilovefeet=$serverId").trim()
                // Log.d("AnimeKai", "Stream token for server $serverName ($type): $streamToken"
                val streamUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$streamToken"

                val streamJson = get(streamUrl, baseUrl)
                val encodedLink = getJsonValue(streamJson, "result").trim()
                // Log.d("AnimeKai", "encodedLink for $serverName: $encodedLink"
                val decryptedJson = get("https://ilovekai.simplepostrequest.workers.dev/?ilovearmpits=$encodedLink")
                val decryptedLink = getJsonValue(decryptedJson, "url").trim()
                // Log.d("AnimeKai", "Decrypted link for $serverName: $decryptedLink"

                val epServer = EpisodeServer(serverName, decryptedLink)
                episodeServers.add(epServer)

                // Log.d("AnimeKai", "Found episode source - Type: $type, Server Name: $serverName, Server ID: $serverId, Stream URL: $decryptedLink"
            }
            val serverGroup = EpisodeServerGroup(type, episodeServers)
            serverGroups.add(serverGroup)
        }

        // Log Server Groups and their servers
        serverGroups.forEach { group ->
            Log.d("AnimeKai", "Type: ${group.type}")
            group.servers.forEach { server ->
                Log.d("AnimeKai", "  Server: ${server.serverName}, URL: ${server.streamUrl}")
            }
        }

        // Use new Extractor on the first available streamUrl for testing
        val extractor = Extractor(client)
        val firstGroup = serverGroups.firstOrNull()
        val firstServer = firstGroup?.servers?.firstOrNull()
        val firstStreamUrl = firstServer?.streamUrl
        if (firstStreamUrl != null) {
            return withContext(Dispatchers.Main) {
                suspendCoroutine { continuation ->
                    extractor.extractVideosFromUrl(url = firstStreamUrl, prefix = "${firstGroup.type.capitalize()} - ${firstServer.serverName} - ") { videos ->
                        continuation.resume(videos)
                    }
                }
            }
        }
        return emptyList()
    }

    data class EpisodeServerGroup(
        val type: String, // "sub", "softsub", "dub"
        val servers: List<EpisodeServer>,
    )

    data class EpisodeServer(
        val serverName: String,
        val streamUrl: String,
    )

    // ============================== Extension Functions ===============================

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeElements = document.select("div.aitem")

        val animes = animeElements.map { element ->
            SAnime.create().apply {
                val url = element.selectFirst("a")!!.attr("href")
                setUrlWithoutDomain(url)

                val titleElement = element.selectFirst("a.title")
                this.title = titleElement?.attr("title") ?: ""

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
                preferences.edit().putString(key, value).commit()
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

                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(customDomainPref)
    }

    companion object {
        val PREF_DOMAIN_KEY = "preffered_domain"
        val PREF_DOMAIN_TITLE = "Preferred Domain (requires app restart)"
        val PREF_DOMAIN_DEFAULT = "https://animekai.bz"
        val PREF_DOMAIN_ENTRIES = arrayOf("animekai.to", "animekai.bz", "animekai.cc", "animekai.ac", "Custom")
        val PREF_DOMAIN_VALUES = arrayOf("https://animekai.to", "https://animekai.bz", "https://animekai.cc", "https://animekai.ac", "custom")

        val PREF_CUSTOM_DOMAIN_KEY = "custom_domain"
        val PREF_CUSTOM_DOMAIN_TITLE = "Custom Domain (requires app restart)"
        val PREF_CUSTOM_DOMAIN_DEFAULT = "https://animekai.to"
    }
}
