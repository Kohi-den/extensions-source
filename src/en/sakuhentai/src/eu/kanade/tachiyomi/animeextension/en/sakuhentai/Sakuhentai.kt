package eu.kanade.tachiyomi.animeextension.en.sakuhentai

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
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Sakuhentai : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Sakuhentai"

    override val baseUrl = "https://www.sakuhentai.net"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private val redirectRegex = Regex("""window\.location\.href\s*=\s*'([^']+)'""")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai-animation-online/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()

        // Only animation articles - gallery articles are excluded by CSS class filter
        document.select("article.category-hentai-animation-online").forEach { article ->
            animeList.add(article.toSAnime())
        }

        val hasNextPage = document.selectFirst(".page-numbers a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()

        // Homepage uses .recpost elements (36 per page), NOT article elements
        val recposts = document.select(".recpost")
        if (recposts.isNotEmpty()) {
            recposts.forEach { recpost ->
                val title = recpost.selectFirst(".recpost-title a")?.text() ?: ""
                // Filter: only include animations, skip galleries by title keyword
                if (!title.contains("Gallery", ignoreCase = true)) {
                    animeList.add(recpost.recpostToSAnime())
                }
            }
        } else {
            // Fallback: some pages might use article elements
            document.select("article.category-hentai-animation-online").forEach { article ->
                animeList.add(article.toSAnime())
            }
        }

        // Homepage has no pagination
        return AnimesPage(animeList, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            return if (page == 1) {
                GET("$baseUrl/?s=$encoded", headers)
            } else {
                GET("$baseUrl/page/$page/?s=$encoded", headers)
            }
        }

        return popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()

        // Search results use article elements with category CSS classes
        document.select("article.category-hentai-animation-online").forEach { article ->
            animeList.add(article.toSAnime())
        }

        // Also check articles without category classes (title keyword fallback)
        document.select("article").forEach { article ->
            if (!article.hasClass("category-hentai-animation-online") &&
                !article.hasClass("category-hentai-gallery")
            ) {
                val title = article.selectFirst(".entry-title a")?.text() ?: ""
                if (title.contains("Animation", ignoreCase = true)) {
                    animeList.add(article.toSAnime())
                }
            }
        }

        val hasNextPage = document.selectFirst(".page-numbers a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ===========================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        // CRITICAL: img.wp-post-image is WRONG on detail pages - it picks up
        // the PREVIOUS anime thumbnail from prev-next-thumbnail navigation.
        // The correct thumbnail is img.thumb-v (video player poster image).
        val thumbnailUrl = document.selectFirst("img.thumb-v")?.attr("src")
            ?: document.selectFirst("img.wp-post-image")?.attr("src")
            ?: ""

        val descriptionText = document.selectFirst("p.saku")?.text()?.trim() ?: ""

        val genreList = mutableListOf<String>()
        document.select(".breadcrumb a").forEach { breadcrumb ->
            val text = breadcrumb.text().trim()
            if (text.isNotBlank() && text != "Home" && text != "Sakuhentai") {
                genreList.add(text)
            }
        }

        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
            thumbnail_url = thumbnailUrl
            description = descriptionText
            genre = genreList.joinToString(", ")
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        // Each animation is a single episode
        val episode = SEpisode.create().apply {
            url = response.request.url.toString()
            name = "Episode 1"
            episode_number = 1f
            date_upload = parseDate(document.selectFirst("time.entry-date")?.attr("datetime"))
        }

        return listOf(episode)
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L

        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    // =============================== Videos ==============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Get all video embed buttons - these are SPAN elements with .change-video class
        val embedElements = document.select(".change-video[data-embed]")
        if (embedElements.isEmpty()) return emptyList()

        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        // Process embeds - try preferred server first
        val sortedEmbeds = if (preferredServer != "all") {
            embedElements.sortedByDescending { it.attr("data-embed").contains(preferredServer, ignoreCase = true) }
        } else {
            embedElements.toList()
        }

        for (embedElement in sortedEmbeds) {
            val embedUrl = embedElement.attr("data-embed")
            if (embedUrl.isNotBlank()) {
                val prefix = when {
                    embedUrl.contains("natsumi", ignoreCase = true) -> "Natsumi - "
                    embedUrl.contains("hglink", ignoreCase = true) -> "HgLink - "
                    else -> "Server - "
                }
                extractFromEmbed(embedUrl, prefix, videos)
            }
        }

        return videos.distinctBy { it.url }
    }

    private fun extractFromEmbed(embedUrl: String, prefix: String, videos: MutableList<Video>) {
        // Phase 1: Try VoeExtractor directly (it handles natsumi/hglink -> VOE redirect internally)
        try {
            val extractedVideos = voeExtractor.videosFromUrl(embedUrl, prefix)
            if (extractedVideos.isNotEmpty()) {
                videos.addAll(extractedVideos)
                return
            }
        } catch (_: Exception) {
        }

        // Phase 2: Manual redirect resolution - VoeExtractor only checks the FIRST script tag,
        // but some pages put the redirect in a different script tag or use a different format.
        try {
            val embedHeaders = headers.newBuilder()
                .set("Referer", embedUrl)
                .build()
            val embedDoc = client.newCall(GET(embedUrl, embedHeaders)).execute().asJsoup()

            // Search ALL script tags for the redirect, not just the first one
            val redirectUrl = embedDoc.select("script").mapNotNull { script ->
                redirectRegex.find(script.data())?.groupValues?.get(1)
            }.firstOrNull()

            if (redirectUrl != null) {
                val voeVideos = voeExtractor.videosFromUrl(redirectUrl, prefix)
                if (voeVideos.isNotEmpty()) {
                    videos.addAll(voeVideos)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(preferredServer, true) },
                { it.quality.contains(preferredQuality, true) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ============================== Helpers ==============================

    private fun Element.toSAnime(): SAnime = SAnime.create().apply {
        title = selectFirst(".entry-title a")?.text()?.trim() ?: "Unknown"
        url = selectFirst(".entry-title a")?.absUrl("href") ?: ""
        thumbnail_url = selectFirst("img.wp-post-image")?.absUrl("src") ?: ""
    }

    private fun Element.recpostToSAnime(): SAnime = SAnime.create().apply {
        title = selectFirst(".recpost-title a")?.text()?.trim() ?: "Unknown"
        url = selectFirst(".recpost-title a")?.absUrl("href") ?: ""
        thumbnail_url = selectFirst(".recpost-thumb img")?.absUrl("src") ?: ""
    }

    // ============================ Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_ENTRIES
            entryValues = QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_DISPLAY_ENTRIES
            entryValues = SERVER_VALUE_ENTRIES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "natsumi"
        private val SERVER_DISPLAY_ENTRIES = arrayOf("All", "Natsumi", "HgLink")
        private val SERVER_VALUE_ENTRIES = arrayOf("all", "natsumi", "hglink")

        const val PREFIX_SEARCH = "id:"
    }
}
