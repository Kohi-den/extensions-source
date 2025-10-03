package eu.kanade.tachiyomi.animeextension.pt.anitube

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.pt.anitube.extractors.AnitubeDownloadExtractor
import eu.kanade.tachiyomi.animeextension.pt.anitube.extractors.AnitubeExtractor
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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Anitube : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anitube"

    override val baseUrl = "https://www.anitube.vip"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime/page/$page", headers)
    override fun popularAnimeSelector() = "div.lista_de_animes div.ani_loop_item_img > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")!!
        title = img.attr("title")
        thumbnail_url = img.attr("src")
    }

    /**
     * Translation of this abomination:
     * First it tries to get the `a.current` element IF its not the second-to-last,
     * and then gets the next `a` element (only useful for the `episodeListParser`).
     *
     * If the first selector fails, then it tries to match a `div.pagination`
     * element that does not have any `a.current` element inside it,
     * and also doesn't have just three elements (previous - current - next),
     * and finally gets the last `a` element("next" button, only useful to `episodeListParser`).
     *
     * I hate the antichrist.
     */
    override fun popularAnimeNextPageSelector() =
        "div.pagination > a.current:not(:nth-last-child(2)) + a, " +
            "div.pagination:not(:has(.current)):not(:has(a:first-child + a + a:last-child)) > a:last-child"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesSelector() = "div.threeItensPerContent > div.epi_loop_item > a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

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

    override fun getFilterList(): AnimeFilterList = AnitubeFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isBlank()) {
            val params = AnitubeFilters.getSearchParameters(filters)
            val season = params.season
            val genre = params.genre
            val year = params.year
            val char = params.initialChar
            when {
                season.isNotBlank() -> "$baseUrl/temporada/$season/$year"
                genre.isNotBlank() ->
                    "$baseUrl/genero/$genre/page/$page/${
                        char.replace(
                            "todos",
                            "",
                        )
                    }"

                else -> "$baseUrl/anime/page/$page/letra/$char"
            }
        } else {
            "$baseUrl/busca.php?s=$query&submit=Buscar"
        }

        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())
        val content = doc.selectFirst("div.anime_container_content")!!
        val infos = content.selectFirst("div.anime_infos")!!

        title = doc.selectFirst("div.anime_container_titulo")!!.text()
        thumbnail_url = content.selectFirst("img")?.attr("src")
        genre = infos.getInfo("Gêneros")
        author = infos.getInfo("Autor")
        artist = infos.getInfo("Estúdio")
        status = parseStatus(infos.getInfo("Status"))

        val infoItems = listOf("Ano", "Direção", "Episódios", "Temporada", "Título Alternativo")

        description = buildString {
            append(doc.selectFirst("div.sinopse_container_content")!!.text() + "\n")
            infoItems.forEach { item ->
                infos.getInfo(item)?.also { append("\n$item: $it") }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.animepag_episodios_item > a"

    override fun episodeListParse(response: Response) = buildList {
        var doc = getRealDoc(response.asJsoup())
        do {
            if (isNotEmpty()) {
                val path = doc.selectFirst(popularAnimeNextPageSelector())!!.attr("href")
                doc = client.newCall(GET(baseUrl + path, headers)).execute().asJsoup()
            }
            doc.select(episodeListSelector())
                .map(::episodeFromElement)
                .also(::addAll)
        } while (doc.selectFirst(popularAnimeNextPageSelector()) != null)
        reverse()
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        episode_number = element.selectFirst("div.animepag_episodios_item_views")!!
            .text()
            .substringAfter(" ")
            .toFloatOrNull() ?: 0F
        name = element.selectFirst("div.animepag_episodios_item_nome")!!.text()
        date_upload = element.selectFirst("div.animepag_episodios_item_date")!!
            .text()
            .toDate()
    }

    // ============================ Video Links =============================
    private val anitubeExtractor by lazy { AnitubeExtractor(headers, client, preferences) }
    private val downloadExtractor by lazy { AnitubeDownloadExtractor(headers, client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val links = mutableListOf(document.location())

        if (preferences.getBoolean(PREF_FILE4GO_KEY, PREF_FILE4GO_DEFAULT)!!) {
            document.selectFirst("div.abaItemDown > a")?.attr("href")?.let {
                links.add(it)
            }
        }

        val epName = document.selectFirst("meta[itemprop=name]")!!.attr("content")

        return links.parallelCatchingFlatMapBlocking {
            when {
                it.contains("/download/") -> downloadExtractor.videosFromUrl(it, epName)
                it.contains("file4go.net") -> downloadExtractor.videosFromUrl(it, epName)
                else -> anitubeExtractor.getVideoList(document)
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FILE4GO_KEY
            title = "Usar File4Go como mirror"
            setDefaultValue(PREF_FILE4GO_DEFAULT)
            summary = PREF_FILE4GO_SUMMARY
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        // Auth Code
        EditTextPreference(screen.context).apply {
            key = PREF_AUTHCODE_KEY
            title = "Auth Code"
            setDefaultValue(PREF_AUTHCODE_DEFAULT)
            summary = PREF_AUTHCODE_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).trim().ifBlank { PREF_AUTHCODE_DEFAULT }
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        if (!document.location().contains("/video/")) {
            return document
        }

        return document.selectFirst("div.controles_ep > a[href]:has(i.spr.listaEP)")
            ?.let {
                val path = it.attr("href")
                client.newCall(GET(baseUrl + path, headers)).execute().asJsoup()
            } ?: document
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completo" -> SAnime.COMPLETED
            "Em Progresso" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(key: String): String? {
        val element = selectFirst("div.anime_info:has(b:contains($key))")
        val genres = element?.select("a")
        val text = if (genres?.size == 0) {
            element.ownText()
        } else {
            genres?.eachText()?.joinToString()
        }
        return text?.ifEmpty { null }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video>(
                { it.quality.startsWith(quality) },
                { PREF_QUALITY_ENTRIES.indexOf(it.quality.substringBefore(" ")) },
            ).thenByDescending { it.quality },
        ).reversed()
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(this)?.time
        }.getOrNull() ?: 0L
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private val DATE_FORMATTER by lazy { SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH) }

        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"

        private const val PREF_AUTHCODE_KEY = "authcode"
        private const val PREF_AUTHCODE_SUMMARY = "Código de Autenticação"
        private const val PREF_AUTHCODE_DEFAULT = ""
        private const val PREF_FILE4GO_KEY = "file4go"
        private const val PREF_FILE4GO_SUMMARY = "Usar File4Go como mirror"
        private const val PREF_FILE4GO_DEFAULT = false
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_ENTRIES = arrayOf("SD", "HD", "FULLHD")
    }
}
