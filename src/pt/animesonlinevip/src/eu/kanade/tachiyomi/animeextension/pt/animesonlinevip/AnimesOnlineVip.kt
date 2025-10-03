package eu.kanade.tachiyomi.animeextension.pt.animesonlinevip

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimesOnlineVip : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animes Online Vip"

    override val baseUrl = "https://animesonline.red"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/top-100", headers)

    override fun popularAnimeSelector() = "a.top100Item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesSelector() = "div.videos div.video div.video-thumb a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "ul.paginacao li.next"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page".toHttpUrl().newBuilder()
            .addPathSegment(page.toString())
            .addQueryParameter("s", query)
            .build()

        return GET(url, headers = headers)
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = doc.selectFirst("div.pagina-titulo h1")!!.text().trim()
            thumbnail_url = doc.selectFirst("div.post-capa img")?.attr("src")
            description = doc.selectFirst("ul.post-infos p")?.text()
            genre = doc.select("ul.post-infos li a").eachText().joinToString(", ")
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return getRealDoc(response.asJsoup())
            .select(episodeListSelector())
            .map(::episodeFromElement)
            .reversed()
    }

    override fun episodeListSelector() = "ul.episodios li a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.attr("title")
            .substringAfterLast("–").trim()
        episode_number = element.selectFirst("div.listaEpInfosEp span:eq(0)")
            ?.text()
            ?.substringAfter(":")
            ?.toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        return document.select("#video source")
            .parallelCatchingFlatMapBlocking {
                getVideosFromURL(it.attr("src"))
            }
    }

    private fun getVideosFromURL(url: String): List<Video> {
        return listOf(
            Video(url, "Default", videoUrl = url, headers),
        )
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
            entries = PREF_QUALITY_VALUES
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

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst("div.post-botoes ul li a i.fa-bars")
        if (menu != null) {
            val originalUrl = menu.parent()!!.attr("href")
            val response = client.newCall(GET(originalUrl, headers)).execute()
            return response.asJsoup()
        }

        return document
    }

    companion object {
        const val PREFIX_SEARCH = "path:"
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("360p", "720p", "1080p")
    }
}
