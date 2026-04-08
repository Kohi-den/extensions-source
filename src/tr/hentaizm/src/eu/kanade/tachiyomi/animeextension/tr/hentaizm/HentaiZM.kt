package eu.kanade.tachiyomi.animeextension.tr.hentaizm

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.hentaizm.extractors.MailRuExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HentaiZM : ParsedAnimeHttpSource(), ConfigurableAnimeSource {
 
    override val name = "HentaiZM"

    override val baseUrl = "https://www.hentaizm6.online" // Çift slash hatası olmaması için sondaki slash kaldırıldı

    override val lang = "tr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    init {
        runBlocking {
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("user", "demo")
                    .add("pass", "demo")
                    .add("redirect_to", baseUrl)
                    .build()

                val headers = headersBuilder()
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()

                client.newCall(POST("$baseUrl/giris", headers, body)).execute()
                    .close()
            }
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/?tab=fav" else "$baseUrl/?tab=fav&page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response) =
        super.popularAnimeParse(response).let { page ->
            val animes = page.animes.distinctBy { it.url }
            AnimesPage(animes, page.hasNextPage)
        }

    override fun popularAnimeSelector() = "div.video-list-item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val titleLink = element.selectFirst("div.title > a")
        if (titleLink != null) {
            title = titleLink.text()
            val href = titleLink.attr("href")
            
            // "/izle/slug/..." linkini "/anime/slug" detay linkine çeviriyoruz
            if (href.contains("/izle/")) {
                val parts = href.split("/")
                if (parts.size >= 3) {
                    val slug = parts[2]
                    setUrlWithoutDomain("/anime/$slug")
                } else {
                    setUrlWithoutDomain(href)
                }
            } else {
                setUrlWithoutDomain(href)
            }
        }

        element.selectFirst("img")?.attr("abs:src")?.let {
            thumbnail_url = it
        }
    }
 
    override fun popularAnimeNextPageSelector() = "span.current + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) =
        super.latestUpdatesParse(response).let { page ->
            val animes = page.animes.distinctBy { it.url }
            AnimesPage(animes, page.hasNextPage)
        }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "a[rel=next]:contains(Sonraki Sayfa)"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            // Detay linki yapısı güncellendi
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        
        val solKolon = document.selectFirst("div.col-md-2")
        thumbnail_url = solKolon?.selectFirst("img")?.attr("abs:src")
        
        val ortaKolon = document.selectFirst("div.col-md-6")
        if (ortaKolon != null) {
            title = ortaKolon.selectFirst("div.head h1, div.head h2")?.text() 
                    ?: ortaKolon.select("p.anime-detail-item:contains(Anime Adı)").text().substringAfter(":").trim()
            
            genre = ortaKolon.select("p.anime-detail-item:contains(Anime Türü) span").eachText().joinToString()
            if (genre.isNullOrEmpty()) {
                genre = ortaKolon.select("p.anime-detail-item:contains(Anime Türü)").text().substringAfter(":").trim()
            }
            
            val ozet = ortaKolon.selectFirst("div.anime-synopsis p")?.text()
            val detayListesi = ortaKolon.select("p.anime-detail-item").eachText()
            val digerDetaylar = detayListesi.filterNot { it.contains("Anime Adı") || it.contains("Anime Türü") }.joinToString("\n")
            
            description = if (ozet != null) "$ozet\n\n$digerDetaylar" else digerDetaylar
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div#episodes-list-anime a.episode-list-item"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        
        val epName = element.selectFirst("div.text")?.text() ?: element.text()
        name = epName
        
        val numRegex = Regex("""(\d+)[\.,]?\s*Bölüm""")
        val match = numRegex.find(epName)
        episode_number = match?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val playerLinkElement = document.selectFirst("div#player-area a")
        val reklamLinki = playerLinkElement?.attr("href")

        // ay.live reklamını bypass edip asıl mail.ru linkini çıkarma işlemi
        if (reklamLinki != null && reklamLinki.contains("ay.live")) {
            val asilVideoLinki = reklamLinki.substringAfter("url=")
            
            if (asilVideoLinki.contains("mail.ru")) {
                val mailRuExtractor = MailRuExtractor(client)
                val mailRuVideos = mailRuExtractor.videosFromUrl(asilVideoLinki)
                videoList.addAll(mailRuVideos)
            }
        }

        return videoList
    }

    private val qualityRegex by lazy { Regex("""(\d+)p""") }
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
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
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
