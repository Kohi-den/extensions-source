package eu.kanade.tachiyomi.animeextension.es.veranime

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
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.pixeldrainextractor.PixelDrainExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VerAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "VerAni.me"

    override val baseUrl = "https://verani.me"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".anime-card a, article a")
        val nextPage = document.select(".pagination .next, a.next").any()

        val animeList = elements.mapNotNull { element ->
            val href = element.attr("abs:href")
            if (href.isBlank() || href.contains("/page/") || href.contains("/animes/")) return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = element.selectFirst("h3")?.text()?.trim()
                    ?: element.selectFirst("img")?.attr("alt")?.trim() ?: "Anime"
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.url }

        return AnimesPage(animeList, nextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query", headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()?.trim() ?: "Anime"
            description =
                document.selectFirst(".anime-hero-description, .sinopsis, .description, p.desc, .info p, .pelicula-overview p")?.text()
                    ?.trim()
            genre = document.select("a[href*=\"categoria\"]").map { it.text().trim() }.distinct().joinToString()
            status = parseStatus(
                document.selectFirst("div.anime-info-label:contains(Estado) span, div.anime-info-label:contains(Estado), .status")
                    ?.text()?.replace("Estado", "", true)?.trim(),
            )
            if (status == SAnime.UNKNOWN && response.request.url.toString().contains("/pelicula/")) {
                status = SAnime.COMPLETED
            }
        }
    }

    private fun parseStatus(status: String?): Int {
        return when (status?.lowercase()) {
            "en emision", "en emisión" -> SAnime.ONGOING
            "finalizado" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        // Some series group episodes into seasons
        val groups = document.select(".temporada-group")

        if (groups.isNotEmpty()) {
            val formatSeason = groups.size > 1
            groups.forEach { group ->
                val seasonNumber = group.selectFirst(".temporada-badge, .temporada-name")?.text()?.let { text ->
                    Regex("""\d+""").find(text)?.value?.toIntOrNull()
                }
                val seasonPrefix = if (formatSeason && seasonNumber != null) {
                    "S${seasonNumber.toString().padStart(2, '0')} "
                } else {
                    ""
                }

                group.select(".capitulo-card-link, a[href*=\"capitulo\"], a[href*=\"episodio\"]").forEach { element ->
                    parseEpisodeElement(element, seasonPrefix)?.let { episodes.add(it) }
                }
            }
        } else {
            document.select(".capitulo-card-link, a[href*=\"capitulo\"], a[href*=\"episodio\"]").forEach { element ->
                parseEpisodeElement(element, "")?.let { episodes.add(it) }
            }
        }

        if (episodes.isEmpty() && document.selectFirst(".iframe-wrapper") != null) {
            episodes.add(
                SEpisode.create().apply {
                    name = "Película"
                    setUrlWithoutDomain(response.request.url.toString())
                    episode_number = 1f
                },
            )
        }

        return episodes.distinctBy { it.url }.reversed()
    }

    private fun parseEpisodeElement(element: org.jsoup.nodes.Element, seasonPrefix: String): SEpisode? {
        val href = element.attr("abs:href")
        val text = element.text().trim()
        if (href.isBlank() || href.contains("proximos-capitulos") || element.hasClass("ver-ahora") || text.contains(
                "ver ahora",
                true,
            )
        ) {
            return null
        }

        return SEpisode.create().apply {
            name = seasonPrefix + text.ifBlank { "Capítulo" }
            setUrlWithoutDomain(href)

            val episodeNumber =
                Regex("""(?i)(?:capitulo|episodio)\s*(\d+)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
            if (episodeNumber != null) {
                episode_number = episodeNumber
            } else {
                Regex("""(?:capitulo|episodio)-(\d+)""").find(url)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    episode_number = it
                }
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframes = document.select("iframe[src], iframe[data-src]")

        return iframes.parallelCatchingFlatMapBlocking { iframe ->
            val src = iframe.attr("abs:src").ifEmpty { iframe.attr("abs:data-src") }
            if (src.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val parentItem = iframe.closest(".iframe-item")
            val language =
                parentItem?.selectFirst("span:contains(Idioma:)")?.text()?.substringAfter("Idioma:")?.trim() ?: ""

            val videos = serverVideoResolver(src)
            if (language.isNotBlank()) {
                videos.map { Video(it.url, "[$language] ${it.quality}", it.videoUrl, it.headers) }
            } else {
                videos
            }
        }
    }

    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val pixeldrainExtractor by lazy { PixelDrainExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        return when {
            arrayOf("ok.ru", "okru").any { url.contains(it, true) } -> okruExtractor.videosFromUrl(url)
            arrayOf("filelions", "lion", "fviplions").any {
                url.contains(
                    it,
                    true,
                )
            } -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })

            arrayOf("wishembed", "streamwish", "strwish", "wish", "animeav1.uns.bio").any {
                url.contains(
                    it,
                    true,
                )
            } -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })

            arrayOf("vidhide", "streamhide", "guccihide", "streamvid").any {
                url.contains(
                    it,
                    true,
                )
            } -> streamHideVidExtractor.videosFromUrl(url)

            arrayOf("voe").any { url.contains(it, true) } -> voeExtractor.videosFromUrl(url)
            arrayOf("yourupload", "upload").any { url.contains(it, true) } -> yourUploadExtractor.videoFromUrl(
                url,
                headers = headers,
            )

            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "zilla-networks").any {
                url.contains(
                    it,
                    true,
                )
            } -> vidGuardExtractor.videosFromUrl(url)

            arrayOf("mp4upload.com").any { url.contains(it, true) } -> mp4uploadExtractor.videosFromUrl(url, headers)
            arrayOf("pixeldrain.com").any { url.contains(it, true) } -> pixeldrainExtractor.videosFromUrl(url)
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
