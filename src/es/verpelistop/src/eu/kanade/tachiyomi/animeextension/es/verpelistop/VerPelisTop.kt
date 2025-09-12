package eu.kanade.tachiyomi.animeextension.es.verpelistop

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import kotlin.text.ifEmpty
import kotlin.text.lowercase

class VerPelisTop : DooPlay(
    "es",
    "VerPelisTop",
    "https://www1.verpelis.top",
) {

    override val supportsLatest = false

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/online/page/$page")

    override fun popularAnimeSelector() = "#archive-content article > div.poster"

    override fun popularAnimeNextPageSelector() = "#nextpagination"

    override fun videoListSelector() = "li.dooplay_player_option" // ul#playeroptionsul

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"

    override val prefQualityTitle = "Calidad preferida"

    override fun Document.getDescription(): String {
        return Jsoup.parse(select("$additionalInfoSelector p").joinToString(" ") { it.text() }).text().trim()
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        val genres = GenreFilter().getGenreList().map { it.first }
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }
            }
            genre = sheader.select("div.data > div.sgeneros > a")
                .map { it.text().lowercase().normalize() }
                .filter { genres.contains(it) }
                .joinToString()
            description = Jsoup.parse(doc.select("$additionalInfoSelector p").joinToString(" ") { it.text() }).text().trim()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelFlatMapBlocking { player ->
            serverVideoResolver(player)
        }
    }

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun serverVideoResolver(player: Element): List<Video> {
        val lang = player.selectFirst("span.title")!!.text()
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        val iframeSource = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute().body.string()
            .substringAfter("src='")
            .substringBefore("'")
            .replace("\\", "").ifEmpty { return emptyList() }

        val frameDoc = client.newCall(GET(iframeSource)).execute().asJsoup()

        return frameDoc.select("li[onclick]:not(.SLD_A)")
            .map { it.attr("onclick").substringAfter("('").substringBefore("')") }
            .flatMap { url ->
                val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
                runCatching {
                    when (matched) {
                        "uqload" -> uqloadExtractor.videosFromUrl(url, "$lang -")
                        "streamwish" -> streamWishExtractor.videosFromUrl(url, lang)
                        "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$lang StreamTape")
                        "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$lang Filemoon:")
                        "vidhide" -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$lang VidHide:$it" })
                        else -> universalExtractor.videosFromUrl(url, headers, prefix = lang)
                    }
                }.getOrDefault(emptyList())
            }
    }

    private val conventions = listOf(
        "uqload" to listOf("uqload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet", "earn"),
    )

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro de año"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("accion", "genero/accion"),
            Pair("amazon prime", "genero/amazon-prime"),
            Pair("animacion", "genero/animacion"),
            Pair("aventura", "genero/aventura"),
            Pair("biografia", "genero/biografia"),
            Pair("ciencia ficcion", "genero/ciencia-ficcion"),
            Pair("comedia", "genero/comedia"),
            Pair("corto", "genero/corto"),
            Pair("crimen", "genero/crimen"),
            Pair("deporte", "genero/deporte"),
            Pair("disney", "genero/disney"),
            Pair("documentales", "genero/documentales"),
            Pair("drama", "genero/drama"),
            Pair("familia", "genero/familia"),
            Pair("fantasia", "genero/fantasia"),
            Pair("hbo", "genero/hbo"),
            Pair("historia", "genero/historia"),
            Pair("horror", "genero/horror"),
            Pair("marvel", "genero/marvel"),
            Pair("misterio", "genero/misterio"),
            Pair("musica", "genero/musica"),
            Pair("netflix", "genero/netflix"),
            Pair("reality", "genero/reality"),
            Pair("romance", "genero/romance"),
            Pair("suspenso", "genero/suspenso"),
            Pair("terror", "genero/terror"),
            Pair("thriller", "genero/thriller"),
        ),
    )

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second

        fun getGenreList() = vals
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
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
        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================
    override fun String.toDate() = 0L

    private fun String.normalize(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "LATINO"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Uqload"
        private val PREF_LANG_ENTRIES = arrayOf("SUBTITULADO", "LATINO", "CASTELLANO")
        private val PREF_LANG_VALUES = arrayOf("SUBTITULADO", "LATINO", "CASTELLANO")
        private val SERVER_LIST = arrayOf("StreamWish", "Uqload")
    }
}
