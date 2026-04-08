package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Anime3rb : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime3rb"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?page=$page", headers)

    override fun popularAnimeSelector() = "div.anime-card-container"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("h3").text()
        thumbnail_url = element.select("img").attr("abs:src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-episodes?page=$page", headers)

    override fun latestUpdatesSelector(): String = "div.episodes-card-container"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("h3").text()
        thumbnail_url = element.select("img").attr("abs:src")
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/search?q=$query&page=$page", headers)
        } else {
            // هنا يمكنك إضافة منطق الفلاتر الخاص بـ Anime3rb لاحقاً
            GET("$baseUrl/anime?page=$page", headers)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.select("h1.anime-details-title").text()
        genre = document.select("div.anime-genres a").joinToString { it.text() }
        description = document.select("p.anime-story").text()
        status = document.select("div.anime-info:contains(حالة الأنمي) span").text().let {
            when {
                it.contains("مستمر") -> SAnime.ONGOING
                it.contains("مكتمل") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.episodes-card-container a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val epText = element.select("h3").text()
            name = epText
            episode_number = epText.filter { it.isDigit() }.toFloatOrNull() ?: 1F
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        
        // محددات السيرفرات تعتمد على التصميم الحالي للموقع
        document.select("ul.servers-list li").forEach { server ->
            val name = server.text()
            val url = server.attr("data-url")
            
            when {
                "ok.ru" in url -> videos.addAll(okruExtractor.videosFromUrl(url))
                "mp4upload" in url -> videos.addAll(mp4uploadExtractor.videosFromUrl(url, headers))
            }
        }
        return videos
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "الجودة المفضلة"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"

        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private const val PREF_DOMAIN_DEFAULT = "https://www.anime3rb.com"
        private val PREF_DOMAIN_ENTRIES = arrayOf("anime3rb.com")
        private val PREF_DOMAIN_VALUES = arrayOf("https://www.anime3rb.com")
    }
}
