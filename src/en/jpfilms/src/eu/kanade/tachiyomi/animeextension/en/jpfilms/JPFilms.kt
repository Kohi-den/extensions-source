package eu.kanade.tachiyomi.animeextension.en.jpfilms

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class JPFilms : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val name = "JPFilms"
    override val baseUrl = "https://jp-films.com"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular Anime ==============================
    override fun popularAnimeSelector(): String =
        "div.item"

    override fun popularAnimeRequest(page: Int): Request = GET("https://jp-films.com/wp-content/themes/halimmovies/halim-ajax.php?action=halim_get_popular_post&showpost=50&type=all")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h3.title").text()
        anime.thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
        Log.d("JPFilmsDebug", "Thumbnail URL: ${anime.thumbnail_url}")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // ============================== Latest Anime ==============================
    override fun latestUpdatesSelector(): String =
        "#ajax-vertical-widget-movie > div.item, " +
            "#ajax-vertical-widget-tv_series > div.item"

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h3.title").text()
        anime.thumbnail_url = element.select("img").attr("data-src")
        Log.d("JPFilmsDebug", "Poster: ${anime.thumbnail_url}")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // ============================== Search Anime ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchQuery = query.replace(" ", "+")
        return GET("$baseUrl/?s=$searchQuery", headers)
    }

    override fun searchAnimeSelector(): String = "#main-contents > section > div.halim_box > article"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.halim-thumb").attr("href"))
        anime.title = element.select("a.halim-thumb").attr("title")
        anime.thumbnail_url = element.select("img").attr("data-src")
        Log.d("JPFilmsDebug", "Poster: ${anime.thumbnail_url}")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // ============================== Anime Details ==============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.newCall(GET(baseUrl + anime.url, headers)).execute().asJsoup()
        anime.title = document.select("h1.entry-title").text()
        anime.genre = document.select("p.category a").joinToString(", ") { it.text() }
        anime.description = document.select("#content > div > div.entry-content.htmlwrap.clearfix > div.video-item.halim-entry-box article p").text()
        anime.thumbnail_url = document.select("#content > div > div.halim-movie-wrapper.tpl-2 > div > div.movie-poster.col-md-4 > img").attr("data-src")
        anime.author = "forsyth47"
        return anime
    }

    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    // ============================== Episode List ==============================

    @Serializable
    data class JsonLdData(
        @SerialName("@type") val type: String? = null,
    )

    override fun episodeListSelector(): String {
        throw UnsupportedOperationException("Not used because we override episodeListParse.")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        // Extract JSON-LD data to determine if it's a Movie or TVSeries
        val jsonLdScript = document.selectFirst("script[type=application/ld+json]:not(.rank-math-schema)")?.data()
        Log.d("JPFilmsDebug", "JSON-LD Script: $jsonLdScript")

        val jsonLdData = json.decodeFromString<JsonLdData>(jsonLdScript ?: "{}")
        Log.d("JPFilmsDebug", "JSON-LD Data: $jsonLdData")

        val isMovie = jsonLdData.type == "Movie"
        Log.d("JPFilmsDebug", "Type: ${if (isMovie) "Movie" else "TVSeries"}")

        val serverAvailable = document.select("#halim-list-server > ul > li")
        Log.d("JPFilmsDebug", "Server Available: $serverAvailable")

        var freeServerFound: Boolean = false
        val episodeContainerSelector = run {
            freeServerFound = false
            var selectedContainer: String? = null

            // Iterate through each server div
            for (serverDiv in serverAvailable) {
                Log.d("JPFilmsDebug", "Server Div: $serverDiv")

                // Log the title of the current server div
                val title = serverDiv.select("li > a").text()
                Log.d("JPFilmsDebug", "Server Div Title: $title")

                // Check if the current server contains a <li> with a title containing "FREE"
                val hasFreeServer = title.contains("FREE")
                Log.d("JPFilmsDebug", "Has Free Server: $hasFreeServer")

                if (hasFreeServer) {
                    // Mark that a FREE server was found
                    freeServerFound = true
                    Log.d("JPFilmsDebug", "FREE Server Found")

                    // Select this server's container
                    selectedContainer = "${serverDiv.select("a").attr("href")} > div > ul"
                    break // Exit the loop once a FREE server is found
                } else if (!freeServerFound) {
                    // If no FREE server is found yet, select the first available server
                    selectedContainer = "${serverDiv.select("a").attr("href")} > div > ul"
                }
            }

            // Return the selected container or an empty string if none is found
            selectedContainer ?: ""
        }
        Log.d("JPFilmsDebug", "Episode Container Selector: $episodeContainerSelector")

        // Extract all <li> elements from the selected container
        val episodeElements = document.select("$episodeContainerSelector > li")
        Log.d("JPFilmsDebug", "Episode Elements: $episodeElements")

        return episodeElements.map { element ->
            SEpisode.create().apply {
                // Get the href attribute from either the anchor tag or the span tag
                var href = if (element.select("a").hasAttr("href")) {
                    element.select("a").attr("href")
                } else {
                    element.select("span").attr("data-href")
                }
                if (!freeServerFound) {
                    href = "$href?svid=2"
                }
                setUrlWithoutDomain(href)
                Log.d("JPFilmsDebug", "Episode URL: $href")

                // Determine if the episode belongs to a FREE or VIP server
                val isFreeServer = element.select("a").attr("title").contains("FREE") ||
                    element.select("span").text().contains("FREE")
                val serverPrefix = if (isFreeServer) "[FREE] " else "[VIP] "

                // Use the title attribute of the anchor tag as the episode name
                name = serverPrefix + (
                    element.select("a").attr("title").ifEmpty {
                        element.select("span").text()
                    }
                    )
                Log.d("JPFilmsDebug", "Episode Name: $name")

                // Generate an episode number based on the text content
                episode_number = element.text()
                    .filter { it.isDigit() }
                    .toFloatOrNull() ?: 1F
                Log.d("JPFilmsDebug", "Episode Number: $episode_number")
            }
        }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException("Not used because we override episodeListParse.")
    }

    // ============================== Video List ==============================

    // Define the JSON serializer
    private val json = Json { ignoreUnknownKeys = true }

    override fun videoListParse(response: Response): List<Video> {
        Log.d("JPFilmsDebug", "Episode Chosen Response URL: ${response.request.url}")
        val document = response.asJsoup()
        val postId = extractPostId(document)
        val episodeSlug = response.request.url.pathSegments.last().split("-").dropLast(1).joinToString("-")

        // Debugging: Log episode slug and post ID
        Log.d("JPFilmsDebug", "Episode Slug: $episodeSlug")
        Log.d("JPFilmsDebug", "Post ID: $postId")

        // Helper function to construct the player URL
        fun getPlayerUrl(serverId: Int, subsvId: String? = null): String {
            return "$baseUrl/wp-content/themes/halimmovies/player.php?" +
                "episode_slug=$episodeSlug&" +
                "server_id=$serverId&" +
                (if (subsvId != null) "subsv_id=$subsvId&" else "") +
                "post_id=$postId&" +
                "nonce=8c934fd387&custom_var="
        }

        // Create custom headers to match Postman
        val customHeaders = Headers.Builder()
            .add("sec-ch-ua-platform", "\"macOS\"")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
            .add("Accept", "text/html, */*; q=0.01")
            .add("sec-ch-ua", "\"Brave\";v=\"135\", \"Not-A.Brand\";v=\"8\", \"Chromium\";v=\"135\"")
            .add("DNT", "1")
            .add("sec-ch-ua-mobile", "?0")
            .add("Sec-GPC", "1")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .add("Host", "jp-films.com")
            .build()

        // Helper function to fetch and parse the player response
        fun fetchAndParsePlayerResponse(playerUrl: String): Pair<String, String> {
            // Debugging: Log the constructed player URL
            Log.d("JPFilmsDebug", "Player URL: $playerUrl")

            // Make the request with custom headers
            val playerResponse = client.newCall(GET(playerUrl, customHeaders)).execute().body.string()

            // Debugging: Log the player response
            Log.d("JPFilmsDebug", "Player Response: $playerResponse")

            // Parse the JSON response into a strongly-typed structure
            val jsonResponse = json.decodeFromString<PlayerResponse>(playerResponse)

            // Extract the 'sources' field from the parsed JSON
            val sources = jsonResponse.data?.sources ?: ""

            // Debugging: Log the extracted sources
            Log.d("JPFilmsDebug", "Extracted Sources: $sources")

            // Extract the HLS URL using string manipulation
            val hlsUrl = sources.split("source src=\"").getOrNull(1)?.split("\" type=")?.getOrNull(0) ?: ""

            // Debugging: Log the extracted HLS URL
            Log.d("JPFilmsDebug", "Extracted HLS URL: $hlsUrl")

            return Pair(sources, hlsUrl)
        }

        val serverAvailable = document.select("#halim-list-server > ul > li")
        Log.d("JPFilmsDebug", "Server Available: $serverAvailable")

        val episodeContainerSelector = run {
            var freeServerFound: Boolean = false
            var selectedContainer: String? = null

            // Iterate through each server div
            for (serverDiv in serverAvailable) {
                Log.d("JPFilmsDebug", "Server Div: $serverDiv")

                // Log the title of the current server div
                val title = serverDiv.select("li > a").text()
                Log.d("JPFilmsDebug", "Server Div Title: $title")

                // Check if the current server contains a <li> with a title containing "FREE"
                val hasFreeServer = title.contains("FREE")
                Log.d("JPFilmsDebug", "Has Free Server: $hasFreeServer")

                if (hasFreeServer) {
                    // Mark that a FREE server was found
                    freeServerFound = true
                    Log.d("JPFilmsDebug", "FREE Server Found")

                    // Select this server's container
                    selectedContainer = "${serverDiv.select("a").attr("href")} > div > ul"
                    break // Exit the loop once a FREE server is found
                } else if (!freeServerFound) {
                    // If no FREE server is found yet, select the first available server
                    selectedContainer = "${serverDiv.select("a").attr("href")} > div > ul"
                }
            }

            // Return the selected container or an empty string if none is found
            selectedContainer ?: ""
        }

        val episodeElements = document.select("$episodeContainerSelector > li")
        Log.d("JPFilmsDebug", "Episode Elements: $episodeElements")

        val targetEpisodeElement = episodeElements.firstOrNull { element ->
            element.select("span").attr("data-episode-slug") == episodeSlug
        } ?: run {
            Log.e("JPFilmsDebug", "No matching episode element found for slug: $episodeSlug")
            return emptyList() // Exit early if no matching element is found
        }

        // Extract the server ID from the target <li> element
        val serverId = targetEpisodeElement.select("span").attr("data-server").toIntOrNull() ?: 0

        // Debugging: Log the extracted server ID
        Log.d("JPFilmsDebug", "Extracted Server ID: $serverId")

        // First attempt with server_id=serverId and no subsvId
        var subsvId: String? = null
        val playerUrl1 = getPlayerUrl(serverId = serverId, subsvId = subsvId)
        val (_, hlsUrl1) = fetchAndParsePlayerResponse(playerUrl1)

        // Retry with subsvId=2 if the first attempt fails
        val hlsUrl = if (hlsUrl1.isEmpty()) {
            subsvId = "2"
            val playerUrl2 = getPlayerUrl(serverId = serverId, subsvId = subsvId)
            val (_, hlsUrl2) = fetchAndParsePlayerResponse(playerUrl2)
            hlsUrl2
        } else {
            hlsUrl1
        }

        // Return the video list if the HLS URL is found, otherwise return an empty list
        return if (hlsUrl.isNotEmpty()) {
            PlaylistUtils(client).extractFromHls(hlsUrl, referer = baseUrl)
        } else {
            emptyList()
        }
    }

    // Data classes for JSON parsing
    @Serializable
    data class PlayerResponse(
        val data: PlayerData? = null,
    )

    @Serializable
    data class PlayerData(
        val status: Boolean? = null,
        val sources: String? = null,
    )

    private fun extractPostId(document: Document): String {
        val bodyClass = document.select("body").attr("class")
        return Regex("postid-(\\d+)").find(bodyClass)?.groupValues?.get(1) ?: ""
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== ToDo ==============================
    // Plan to add option to change between original title and translated title
    // Plan to add backup server too.
    // ============================== Preferences ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = Companion.PREF_TITLE_STYLE_KEY
            title = "Preferred Title Style"
            entries = arrayOf("Original", "Translated")
            entryValues = arrayOf("original", "translated")
            setDefaultValue("translated")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    private val SharedPreferences.titleStyle
        get() = getString(Companion.PREF_TITLE_STYLE_KEY, "translated")!!

    companion object {
        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
    }
}
