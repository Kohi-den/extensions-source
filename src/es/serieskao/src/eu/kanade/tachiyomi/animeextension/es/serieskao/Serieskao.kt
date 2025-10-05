package eu.kanade.tachiyomi.animeextension.es.serieskao

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamsilkextractor.StreamSilkExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import kotlin.text.RegexOption

open class Serieskao : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "SeriesKao"

    override val baseUrl = "https://serieskao.top"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "VidGuard",
        )

        private const val AES_KEY = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"
        private val DATA_LINK_REGEX = """dataLink\s*=\s*([^;]+);""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val VIDEO_SOURCES_REGEX = """var\s+videoSources\s*=\s*\[(.+?)]\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val SOURCE_URL_REGEX = """['\"]([^'\"]+)['\"]""".toRegex()
    }

    override fun popularAnimeSelector(): String = "a.poster-card"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val rawTitle = element.selectFirst(".poster-card__title")?.text().orEmpty()
            val normalizedTitle = rawTitle.takeIf { it.isNotBlank() }
                ?: element.attr("title").removePrefix("VER").trim()
            title = normalizedTitle.ifBlank { element.text() }

            val image = element.selectFirst("img")
            val thumb = image?.attr("src").orEmpty().ifBlank { image?.attr("data-src").orEmpty() }
            thumbnail_url = thumb.replace("/w154/", "/w500/")
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val url = response.request.url.toString()

        if (url.contains("/pelicula/")) {
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1F
                    name = "PELÍCULA"
                    setUrlWithoutDomain(url)
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        val seasonTabs = doc.select("#season-tabs li a[data-tab]")
        val numberRegex = Regex("\\d+")

        if (seasonTabs.isEmpty()) {
            doc.select(".episodes-list a.episode-item").forEachIndexed { index, element ->
                val episodeNumber = element.selectFirst(".episode-number")?.text()
                    ?.let { numberRegex.find(it)?.value } ?: (index + 1).toString()
                val episodeTitle = element.selectFirst(".episode-title")?.text()
                    ?.ifBlank { "Episodio $episodeNumber" } ?: "Episodio $episodeNumber"

                episodes += SEpisode.create().apply {
                    episode_number = episodeNumber.toFloatOrNull() ?: 0F
                    name = "T1 - Episodio $episodeNumber: $episodeTitle"
                    setUrlWithoutDomain(element.attr("href"))
                }
            }
        } else {
            seasonTabs.forEachIndexed { index, tab ->
                val seasonId = tab.attr("data-tab")
                val seasonNumber = numberRegex.find(tab.text())?.value
                    ?: numberRegex.find(seasonId)?.value
                    ?: (index + 1).toString()

                val seasonPane = doc.selectFirst("#$seasonId") ?: return@forEachIndexed

                seasonPane.select(".episodes-list a.episode-item").forEach { element ->
                    val episodeNumber = element.selectFirst(".episode-number")?.text()
                        ?.let { numberRegex.find(it)?.value } ?: "0"
                    val episodeTitle = element.selectFirst(".episode-title")?.text()
                        ?.ifBlank { "Episodio $episodeNumber" } ?: "Episodio $episodeNumber"

                    episodes += SEpisode.create().apply {
                        episode_number = episodeNumber.toFloatOrNull() ?: 0F
                        name = "T$seasonNumber - Episodio $episodeNumber: $episodeTitle"
                        setUrlWithoutDomain(element.attr("href"))
                    }
                }
            }
        }

        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptData = document.select("script")
            .asSequence()
            .map(Element::data)
            .firstOrNull { it.contains("var videoSources") }
            ?: return emptyList()

        val sourcesBlock = VIDEO_SOURCES_REGEX.find(scriptData)?.groupValues?.get(1) ?: return emptyList()
        val videoUrls = SOURCE_URL_REGEX.findAll(sourcesBlock)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
            .toList()

        if (videoUrls.isEmpty()) return emptyList()

        val referer = response.request.url.toString()
        val headers = headersBuilder().set("Referer", referer).build()
        val videos = mutableListOf<Video>()

        videoUrls.forEach { videoUrl ->
            runCatching {
                client.newCall(GET(videoUrl, headers)).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (body.isBlank()) return@use

                    val bodyDoc = Jsoup.parse(body)
                    val parsedLinks = extractNewExtractorLinks(bodyDoc, body)

                    if (parsedLinks.isNullOrEmpty()) {
                        Log.w("SeriesKao", "Sin enlaces descifrados para $videoUrl")
                        return@use
                    }

                    parsedLinks.forEach { (url, lang) ->
                        runCatching {
                            videos += serverVideoResolver(url, lang)
                        }.onFailure {
                            Log.e("SeriesKao", "Error al procesar URL de video: $url", it)
                        }
                    }
                }
            }.onFailure {
                Log.e("SeriesKao", "Error al obtener cuerpo de videoUrl: $videoUrl", it)
            }
        }

        return videos
    }

    private fun extractNewExtractorLinks(doc: Document, htmlContent: String): List<Pair<String, String>>? {
        val links = mutableListOf<Pair<String, String>>()

        val scriptData = doc.select("script")
            .asSequence()
            .map(Element::data)
            .firstOrNull { it.contains("dataLink") }

        val rawExpression = scriptData?.let {
            getFirstMatch(DATA_LINK_REGEX, it)
        } ?: getFirstMatch(DATA_LINK_REGEX, htmlContent)

        val jsonPayload = resolveDataLink(rawExpression) ?: return null

        val items = runCatching {
            json.decodeFromString<List<Item>>(jsonPayload)
        }.getOrElse {
            Log.e("SeriesKao", "No se pudo parsear dataLink", it)
            return null
        }

        val idiomas = mapOf("LAT" to "[LAT]", "ESP" to "[CAST]", "SUB" to "[SUB]")

        items.forEach { item ->
            val languageKey = item.video_language?.uppercase() ?: ""
            val languageCode = idiomas[languageKey] ?: "unknown"

            item.sortedEmbeds.forEach { embed ->
                if (!"video".equals(embed.type, ignoreCase = true)) return@forEach

                val decryptedLink = decryptEmbedLink(embed.link)
                decryptedLink?.let { links.add(it to languageCode) }
            }
        }

        return links.ifEmpty { null }
    }

    private fun resolveDataLink(rawExpression: String?): String? {
        if (rawExpression.isNullOrBlank()) return null

        var expr = rawExpression.trim().trimEnd(';')

        fun String.removeOuterCall(prefix: String): String? {
            if (!startsWith(prefix, ignoreCase = true) || !endsWith(')')) return null
            val start = indexOf('(')
            val end = lastIndexOf(')')
            if (start == -1 || end == -1 || end <= start) return null
            return substring(start + 1, end).trim()
        }

        fun String.trimMatchingQuotes(): String {
            return if ((startsWith('"') && endsWith('"')) || (startsWith('\'') && endsWith('\''))) {
                substring(1, length - 1)
            } else {
                this
            }
        }

        while (true) {
            expr.removeOuterCall("JSON.parse")?.let {
                expr = it
            }
            expr.removeOuterCall("window.JSON.parse")?.let {
                expr = it
            }
            expr.removeOuterCall("decodeURIComponent")?.let {
                expr = runCatching { URLDecoder.decode(it.trimMatchingQuotes(), "UTF-8") }
                    .getOrElse { return null }
            }
            expr.removeOuterCall("window.decodeURIComponent")?.let {
                expr = runCatching { URLDecoder.decode(it.trimMatchingQuotes(), "UTF-8") }
                    .getOrElse { return null }
            }
            expr.removeOuterCall("atob")?.let {
                expr = runCatching {
                    String(Base64.decode(it.trimMatchingQuotes(), Base64.DEFAULT))
                }.getOrElse { return null }
            }
            expr.removeOuterCall("window.atob")?.let {
                expr = runCatching {
                    String(Base64.decode(it.trimMatchingQuotes(), Base64.DEFAULT))
                }.getOrElse { return null }
            }
            break
        }

        expr = expr.trim().trimMatchingQuotes()

        return expr.takeIf { it.isNotBlank() }
    }

    private fun decryptEmbedLink(rawLink: String?): String? {
        if (rawLink.isNullOrBlank()) return null

        val link = rawLink.trim()
        if (link.startsWith("http", true)) return link

        CryptoAES.decryptCbcIV(link, AES_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        CryptoAES.decrypt(link, AES_KEY).takeIf { it.isNotBlank() }?.let { return it }

        decodeJwtLink(link)?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private fun decodeJwtLink(token: String): String? {
        val segments = token.split('.')
        if (segments.size < 2) return null

        val payload = segments[1].padBase64Url()

        return runCatching {
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
            val element = json.parseToJsonElement(String(decoded))
            val obj = element.jsonObject

            val link = obj["link"]?.jsonPrimitive?.contentOrNull
            val nestedLink = obj["data"]?.jsonObject?.get("link")?.jsonPrimitive?.contentOrNull

            link ?: nestedLink
        }.getOrNull()
    }

    private fun String.padBase64Url(): String {
        val padding = (4 - length % 4) % 4
        return this + "=".repeat(padding)
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        return runCatching {
            when {
                arrayOf("voe").any(url) -> voeExtractor.videosFromUrl(url, "$prefix ")
                arrayOf("ok.ru", "okru").any(url) -> okruExtractor.videosFromUrl(url, prefix)
                arrayOf("filemoon", "moonplayer").any(url) -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
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
                        listOf(Video(videoUrl, "$prefix Amazon", videoUrl))
                    } else {
                        emptyList()
                    }
                }
                arrayOf("uqload").any(url) -> uqloadExtractor.videosFromUrl(url, prefix)
                arrayOf("mp4upload").any(url) -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                arrayOf("wishembed", "streamwish", "strwish", "wish").any(url) -> {
                    streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                }
                arrayOf("doodstream", "dood.", "ds2play", "doods.").any(url) -> {
                    val url2 = url.replace("https://doodstream.com/e/", "https://d0000d.com/e/")
                    doodExtractor.videosFromUrl(url2, "$prefix DoodStream")
                }
                arrayOf("streamlare").any(url) -> streamlareExtractor.videosFromUrl(url, prefix)
                arrayOf("yourupload", "upload").any(url) -> yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")
                arrayOf("burstcloud", "burst").any(url) -> burstCloudExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")
                arrayOf("fastream").any(url) -> fastreamExtractor.videosFromUrl(url, prefix = "$prefix Fastream:")
                arrayOf("upstream").any(url) -> upstreamExtractor.videosFromUrl(url, prefix = "$prefix ")
                arrayOf("streamsilk").any(url) -> streamSilkExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamSilk:$it" })
                arrayOf("streamtape", "stp", "stape").any(url) -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")
                arrayOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide").any(url) -> streamHideVidExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamHideVid:$it" })
                arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")
                else -> emptyList()
            }
        }.getOrNull() ?: emptyList()
    }

    private fun getFirstMatch(regex: Regex, input: String): String {
        return regex.find(input)?.groupValues?.get(1) ?: ""
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val tagFilter = filterList.find { it is YearFilter } as YearFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            tagFilter.state != 0 -> GET("$baseUrl/series?year=${tagFilter.toUriPart()}&page=$page")
            else -> GET("$baseUrl/peliculas?page=$page")
        }
    }
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1.m-b-5")?.text()?.ifBlank { "Sin título" } ?: "Sin título"
            thumbnail_url = document.selectFirst("div.card-body div.row div.col-sm-3 img.img-fluid")
                ?.attr("src")?.replace("/w154/", "/w500/")
                ?: ""
            description = document.selectFirst("div.col-sm-4 div.text-large")?.ownText() ?: ""
            genre = document.select("div.p-v-20.p-h-15.text-center a span").joinToString { it.text() }
            status = SAnime.COMPLETED
        }
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro de año"),
        GenreFilter(),
        AnimeFilter.Header("Busqueda por año"),
        YearFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Doramas", "generos/dorama"),
            Pair("Animes", "animes"),
            Pair("Acción", "generos/accion"),
            Pair("Animación", "generos/animacion"),
            Pair("Aventura", "generos/aventura"),
            Pair("Ciencia Ficción", "generos/ciencia-ficcion"),
            Pair("Comedia", "generos/comedia"),
            Pair("Crimen", "generos/crimen"),
            Pair("Documental", "generos/documental"),
            Pair("Drama", "generos/drama"),
            Pair("Fantasía", "generos/fantasia"),
            Pair("Foreign", "generos/foreign"),
            Pair("Guerra", "generos/guerra"),
            Pair("Historia", "generos/historia"),
            Pair("Misterio", "generos/misterio"),
            Pair("Pelicula de Televisión", "generos/pelicula-de-la-television"),
            Pair("Romance", "generos/romance"),
            Pair("Suspense", "generos/suspense"),
            Pair("Terror", "generos/terror"),
            Pair("Western", "generos/western"),
        ),
    )
    private class YearFilter : UriPartFilter(
        "Año",
        arrayOf(Pair("<selecionar>", "")) +
            (2024 downTo 1979).map {
                Pair(it.toString(), it.toString())
            }.toTypedArray(),
    )

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    @Serializable
    data class Item(
        val file_id: Int? = null,
        val video_language: String? = null,
        val sortedEmbeds: List<Embed> = emptyList(),
    )

    @Serializable
    data class Embed(
        val servername: String? = null,
        val link: String? = null,
        val type: String? = null,
    )

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

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
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
