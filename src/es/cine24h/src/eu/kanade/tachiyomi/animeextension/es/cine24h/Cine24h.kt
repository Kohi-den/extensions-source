package eu.kanade.tachiyomi.animeextension.es.cine24h

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

open class Cine24h : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Cine24h"

    override val baseUrl = "https://cine24h.online"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf("Voe", "Fastream", "Filemoon", "Doodstream")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            description = document.selectFirst(".Single .Description")?.text()
            genre = document.select(".Single .InfoList a").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".Single .Image img")?.getImageUrl()?.replace("/w185/", "/w500/")
            if (document.location().contains("/movie/")) {
                status = SAnime.COMPLETED
            } else {
                val statusText = document.select(".InfoList .AAIco-adjust").map { it.text() }
                    .find { "En Producción:" in it }?.substringAfter("En Producción:")?.trim()
                status = when (statusText) { "Sí" -> SAnime.ONGOING else -> SAnime.COMPLETED }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/peliculas-mas-vistas/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".TPost a:not([target])")
        val nextPage = document.select(".wp-pagenavi .next").any()
        val animeList = elements.map { element ->
            val lang = element.select(".Langu .sprite").attr("class").lowercase()
            val titleItem = element.selectFirst(".Title")?.text()?.trim() ?: ""
            val link = element.attr("abs:href")

            val prefix = when {
                lang.contains("sub") || titleItem.lowercase().contains("(sub)") || link.contains("-sub") -> "\uD83C\uDDFA\uD83C\uDDF8 "
                lang.contains("lat") || titleItem.lowercase().contains("(lat)") || link.contains("-lat") -> "\uD83C\uDDF2\uD83C\uDDFD "
                lang.contains("esp") || titleItem.lowercase().contains("(es)") || link.contains("-es") -> "\uD83C\uDDEA\uD83C\uDDF8 "
                else -> ""
            }
            SAnime.create().apply {
                title = prefix + titleItem
                thumbnail_url = element.selectFirst(".Image img")?.getImageUrl()?.replace("/w185/", "/w300/")
                setUrlWithoutDomain(link)
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return GET("$baseUrl/page/$page/?s=trfilter&trfilter=1&years%5B0%5D=$currentYear#038;trfilter=1&years%5B0%5D=$currentYear", headers)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.location().contains("/movie/")) {
            listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "PELÍCULA"
                    scanlator = document.select(".AAIco-date_range").text().trim()
                    setUrlWithoutDomain(document.location())
                },
            )
        } else {
            var episodeCounter = 1F
            document.select(".AABox").flatMap { season ->
                val noSeason = season.select(".Title").text().substringAfter("Temporada").trim()
                season.select(".AABox .TPTblCn tr").map { ep ->
                    SEpisode.create().apply {
                        episode_number = episodeCounter++
                        name = "T$noSeason - E${ep.select(".Num").text().trim()} - ${ep.select(".MvTbTtl a").text().trim()}"
                        scanlator = ep.select(".MvTbTtl span").text()
                        setUrlWithoutDomain(ep.select(".MvTbTtl a").attr("abs:href"))
                    }
                }
            }.reversed()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        return document.select(".TPlayerTb").map { org.jsoup.nodes.Entities.unescape(it.html()) }.parallelCatchingFlatMapBlocking {
            val urlEmbed = it.substringAfter("src=\"").substringBefore("\"").replace("&#038;", "&")
            val link = client.newCall(GET(urlEmbed)).execute().asJsoup().selectFirst("iframe")?.attr("src") ?: ""
            serverVideoResolver(link)
        }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("fastream") -> {
                val link = if (url.contains("emb.html")) "https://fastream.to/embed-${url.split("/").last()}.html" else url
                FastreamExtractor(client, headers).videosFromUrl(link)
            }
            arrayOf("filemoon", "moonplayer").any(url) -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            arrayOf("voe").any(url) -> voeExtractor.videosFromUrl(url)
            arrayOf("doodstream", "dood.", "ds2play", "doods.").any(url) -> doodExtractor.videosFromUrl(url)
            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url)
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Películas", "peliculas"),
            Pair("Series", "series"),
            Pair("Acción", "category/accion"),
            Pair("Animación", "category/animacion"),
            Pair("Anime", "category/anime"),
            Pair("Aventura", "category/aventura"),
            Pair("Bélica", "category/belica"),
            Pair("Ciencia ficción", "category/ciencia-ficcion"),
            Pair("Comedia", "category/comedia"),
            Pair("Crimen", "category/crimen"),
            Pair("Documental", "category/documental"),
            Pair("Drama", "category/drama"),
            Pair("Familia", "category/familia"),
            Pair("Fantasía", "category/fantasia"),
            Pair("Gerra", "category/gerra"),
            Pair("Historia", "category/historia"),
            Pair("Misterio", "category/misterio"),
            Pair("Música", "category/musica"),
            Pair("Navidad", "category/navidad"),
            Pair("Película de TV", "category/pelicula-de-tv"),
            Pair("Romance", "category/romance"),
            Pair("Suspenso", "category/suspense"),
            Pair("Terror", "category/terror"),
            Pair("Western", "category/western"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    protected open fun org.jsoup.nodes.Element.getImageUrl(): String? {
        return when {
            isValidUrl("data-src") -> attr("abs:data-src")
            isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
            isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
            isValidUrl("src") -> attr("abs:src")
            else -> ""
        }
    }

    protected open fun org.jsoup.nodes.Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("data:image/")
    }

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
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
}
