package eu.kanade.tachiyomi.animeextension.es.asialiveaction

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AsiaLiveAction : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AsiaLiveAction"

    override val baseUrl = "https://asialiveaction.com"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vk"
        private val SERVER_LIST = arrayOf(
            "Filemoon",
            "StreamWish",
            "VidGuard",
            "Amazon",
            "AmazonES",
            "FileLions",
            "Vk",
            "Okru",
        )

        private val ALL_VIDEOS_REGEX = Regex("var\\s+allVideos\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL)
    }

    override fun popularAnimeSelector(): String = "ul.navegacion-grid li"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/navegacion/"
        } else {
            "$baseUrl/navegacion/?pagina=$page"
        }
        return GET(url, headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a")!!
        anime.setUrlWithoutDomain(link.attr("href"))
        anime.title = element.selectFirst("h5, h4, h3.Title, a.Title, span.Title")?.text()?.trim().orEmpty()
        val image = element.selectFirst("img")
        anime.thumbnail_url = image?.imageUrl()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = ".paginado a:has(i.fa-angle-right)"

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("div.Poster")?.attr("style")?.extractBackgroundUrl()
                ?: document.selectFirst("figure img")?.attr("abs:src")?.getHdImg()
            title = document.selectFirst("h2.Title, h1.Title")?.text()?.trim().orEmpty()
            val descriptionText = document.select("section article > p")
                .joinToString("\n\n") { it.text().trim() }
            description = descriptionText.takeIf { it.isNotBlank() }
            val genreText = document.select("footer a.tag").joinToString { it.text() }
            genre = genreText.takeIf { it.isNotBlank() }
            val artistText = document.select("#elenco .actor-nm").joinToString { it.text() }
            artist = artistText.takeIf { it.isNotBlank() }
            val statusText = document.selectFirst(".categorias .estado")?.text()?.lowercase()
            status = when {
                statusText?.contains("finalizado") == true -> SAnime.COMPLETED
                statusText?.contains("publicacion") == true -> SAnime.ONGOING
                statusText?.contains("publicación") == true -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.lista-episodios div.episodio-unico"

    override fun episodeFromElement(element: Element): SEpisode {
        val link = element.selectFirst("a") ?: throw Exception("Episode link not found")
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(link.attr("href"))
        val episodeLabel = element.selectFirst("b.numero-episodio")?.text().orEmpty()
        val epNum = getNumberFromEpsString(episodeLabel)
        episode.episode_number = epNum.toFloatOrNull() ?: 1f
        val title = element.selectFirst("span.episodio-serie")?.text()?.trim().orEmpty()
        episode.name = when {
            title.isNotBlank() && episodeLabel.isNotBlank() -> "$title - ${episodeLabel.trim()}"
            title.isNotBlank() -> title
            episodeLabel.isNotBlank() -> episodeLabel
            else -> link.attr("href").substringAfterLast('/').ifBlank { "Episodio" }
        }
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String = epsStr.filter { it.isDigit() }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text)
            .map {
                it.value.trim()
                    .removeSurrounding("\"")
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
            }
            .toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val scriptData = document.selectFirst("script:containsData(var allVideos)")?.data()
        val allVideosUrls = scriptData?.let { data ->
            val allVideosJson = ALL_VIDEOS_REGEX.find(data)?.groupValues?.get(1)
                ?: return@let emptyList()

            buildList {
                val jsonObject = JSONObject(allVideosJson)
                jsonObject.keys().forEach { key ->
                    val videosArray = jsonObject.optJSONArray(key) ?: return@forEach
                    for (index in 0 until videosArray.length()) {
                        val videoEntry = videosArray.optJSONArray(index) ?: continue
                        val url = videoEntry.optString(1)
                        if (url.isNotBlank()) add(url)
                    }
                }
            }
        } ?: emptyList()

        val videoUrls = allVideosUrls.ifEmpty {
            document.select("script:containsData(var videos)")
                .flatMap { fetchUrls(it.data()) }
        }

        return videoUrls.distinct()
            .parallelCatchingFlatMapBlocking { serverVideoResolver(it) }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        return when {
            arrayOf("vk").any(url) -> vkExtractor.videosFromUrl(url)
            arrayOf("ok.ru", "okru").any(url) -> okruExtractor.videosFromUrl(url)
            arrayOf("wishembed", "streamwish", "strwish", "wish").any(url) -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            arrayOf("filemoon", "moonplayer").any(url) -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url)
            arrayOf("filelions", "lion", "fviplions").any(url) -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            !url.contains("disable") && (arrayOf("amazon", "amz").any(url)) -> {
                val body = client.newCall(GET(url)).execute().asJsoup()
                return if (body.select("script:containsData(var shareId)").toString().isNotBlank()) {
                    val shareId = body.selectFirst("script:containsData(var shareId)")!!.data()
                        .substringAfter("shareId = \"").substringBefore("\"")
                    val amazonApiJson = client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
                        .execute().asJsoup()
                    val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
                    val amazonApi =
                        client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
                            .execute().asJsoup()
                    val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
                    listOf(Video(videoUrl, "Amazon", videoUrl))
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> {
                val urlBuilder = baseUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                if (page > 1) urlBuilder.addQueryParameter("pagina", page.toString())
                GET(urlBuilder.build(), headers)
            }
            genreFilter.state != 0 -> {
                val urlBuilder = "$baseUrl/navegacion/".toHttpUrl().newBuilder()
                urlBuilder.addQueryParameter("genero[]", genreFilter.toUriPart())
                if (page > 1) urlBuilder.addQueryParameter("pagina", page.toString())
                GET(urlBuilder.build(), headers)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", "all"),
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Drama", "drama"),
            Pair("Deporte", "deporte"),
            Pair("Erótico", "erotico"),
            Pair("Escolar", "escolar"),
            Pair("Extraterrestres", "extraterrestres"),
            Pair("Fantasía", "fantasia"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Lucha", "lucha"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Yaoi / BL", "yaoi-bl"),
            Pair("Yuri / GL", "yuri-gl"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    private fun String?.getHdImg(): String? {
        if (this.isNullOrEmpty() || !this.contains("tmdb")) return this

        val pattern = """(https:\/\/image\.tmdb\.org\/t\/p\/)([\w_]+)(\/[^\s]*)""".toRegex()
        return pattern.replace(this) { matchResult ->
            "${matchResult.groupValues[1]}w500${matchResult.groupValues[3]}"
        }
    }

    private fun Element.imageUrl(): String? {
        val dataSrc = absUrl("data-src").takeIf { it.isNotBlank() }
        val src = absUrl("src").takeIf { it.isNotBlank() }
        val rawDataSrc = attr("data-src").takeIf { it.isNotBlank() }?.toAbsoluteUrl()
        val rawSrc = attr("src").takeIf { it.isNotBlank() }?.toAbsoluteUrl()
        return dataSrc ?: src ?: rawDataSrc ?: rawSrc
    }

    private fun String.extractBackgroundUrl(): String? {
        val match = """url\((['\"]?)(.+?)\1\)""".toRegex().find(this) ?: return null
        return match.groupValues[2].toAbsoluteUrl()
    }

    private fun String.toAbsoluteUrl(): String {
        return when {
            startsWith("http://") || startsWith("https://") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$baseUrl$this"
            else -> "$baseUrl/$this"
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
    }
}
