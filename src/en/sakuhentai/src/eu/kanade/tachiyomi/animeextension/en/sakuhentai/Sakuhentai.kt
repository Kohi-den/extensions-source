package eu.kanade.tachiyomi.animeextension.en.sakuhentai

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Sakuhentai : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Sakuhentai"

    override val baseUrl = "https://www.sakuhentai.net"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/hentai-animation-online/page/$page/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("article").mapNotNull { it.toSAnime() }
        return AnimesPage(animeList, hasNextPage(document))
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/page/$page/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("article").mapNotNull { it.toSAnime() }
        return AnimesPage(animeList, hasNextPage(document))
    }

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = SakuhentaiFilters.getSearchParameters(filters)

        // If a series filter is selected, use that path
        val categoryPath = when {
            params.series.isNotBlank() -> "/${params.series}/"
            else -> null
        }

        return if (categoryPath != null) {
            GET("$baseUrl${categoryPath}page/$page/", headers)
        } else if (query.isNotBlank()) {
            if (page == 1) {
                GET("$baseUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}", headers)
            } else {
                GET("$baseUrl/page/$page/?s=${java.net.URLEncoder.encode(query, "UTF-8")}", headers)
            }
        } else {
            GET("$baseUrl/hentai-animation-online/page/$page/", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("article").mapNotNull { it.toSAnime() }
        return AnimesPage(animeList, hasNextPage(document))
    }

    // Handle prefix search from URL intent
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$slug/", headers))
                .awaitSuccess()
                .use { searchAnimeByIdParse(it) }
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val anime = animeDetailsParse(response)
        return AnimesPage(listOf(anime), false)
    }

    override fun getFilterList(): AnimeFilterList = SakuhentaiFilters.FILTER_LIST

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
            thumbnail_url = document.selectFirst("img.wp-post-image")?.attr("abs:src")
                ?: document.selectFirst("img.wp-post-image")?.attr("data-src")
            description = document.selectFirst("p.saku")?.text()?.trim()
            status = SAnime.COMPLETED

            // Extract genre/series from breadcrumbs
            val breadcrumbs = document.select(".breadcrumb a")
            val genres = breadcrumbs.mapNotNull { a ->
                val href = a.attr("href")
                val text = a.text().trim()
                // Skip the "Home" breadcrumb
                if (text.equals("Home", ignoreCase = true) || href == baseUrl || href == "$baseUrl/") {
                    null
                } else {
                    text
                }
            }.filter { it.isNotBlank() }

            genre = genres.joinToString(", ").ifBlank { null }

            // Extract artist from description format "by ArtistName"
            artist = description?.let { desc ->
                Regex("""by\s+(\S+)""").find(desc)?.groupValues?.get(1)
            }

            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Episode 1"
                episode_number = 1f
                url = response.request.url.toString()
            },
        )
    }

    // ============================== Video Sources ==============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // CORRECT SELECTOR: .change-video[data-embed] (these are DIVs, not spans)
        val embedElements = document.select(".change-video[data-embed]")

        if (embedElements.isEmpty()) {
            // Fallback: try the lazy-v container
            val lazyEmbed = document.selectFirst(".lazy-v[data-embed]")
            lazyEmbed?.attr("data-embed")?.takeIf { it.isNotBlank() }?.let { embedUrl ->
                extractFromEmbed(embedUrl, "Voe - ", videos)
            }
        }

        embedElements.forEach { element ->
            val embedUrl = element.attr("data-embed")
            if (embedUrl.isBlank()) return@forEach

            val prefix = when {
                embedUrl.contains("natsumi", ignoreCase = true) -> "Natsumi - "
                embedUrl.contains("hglink", ignoreCase = true) -> "HgLink - "
                else -> "Server - "
            }

            extractFromEmbed(embedUrl, prefix, videos)
        }

        return videos.distinctBy { it.url }
    }

    private fun extractFromEmbed(embedUrl: String, prefix: String, videos: MutableList<Video>) {
        try {
            val extractedVideos = voeExtractor.videosFromUrl(embedUrl, prefix)
            videos.addAll(extractedVideos)
        } catch (_: Exception) {
            // Silently skip failed extractions - the other server may still work
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return sortedWith(
            compareByDescending { it.quality.contains(server, ignoreCase = true) }
                .thenByDescending { it.quality.contains(quality, ignoreCase = true) },
        )
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Utilities ==============================

    private fun Element.toSAnime(): SAnime? {
        val titleEl = selectFirst(".entry-title a") ?: return null
        val link = titleEl.attr("abs:href")
        if (link.isBlank()) return null

        // Skip non-animation articles (e.g. galleries)
        val isAnimation = className().contains("hentai-animation-online") || className().contains("hentai-ani")
        if (!isAnimation) return null

        val thumbnailEl = selectFirst("img.wp-post-image")
        val thumbnailUrl = thumbnailEl?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: thumbnailEl?.attr("data-src")?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            title = titleEl.text().trim()
            thumbnail_url = thumbnailUrl
            setUrlWithoutDomain(link)
            status = SAnime.COMPLETED
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        // Method 1: Check for .page-numbers a.next
        if (document.select(".page-numbers a.next").isNotEmpty()) return true

        // Method 2: Check if there's an <a> after the current page span
        val currentSpan = document.selectFirst(".page-numbers span.current")
        if (currentSpan != null) {
            val nextSibling = currentSpan.nextElementSibling()
            if (nextSibling != null && nextSibling.tagName() == "a") return true
        }

        return false
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "480p", "720p", "1080p")
        private val PREF_QUALITY_VALUES = arrayOf("360", "480", "720", "1080")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "natsumi"
        private val PREF_SERVER_ENTRIES = arrayOf("Player 1 (Natsumi)", "Player 2 (HgLink)")
        private val PREF_SERVER_VALUES = arrayOf("natsumi", "hglink")
    }
}
