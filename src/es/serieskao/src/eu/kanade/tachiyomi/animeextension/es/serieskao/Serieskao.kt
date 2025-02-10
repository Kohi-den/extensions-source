package eu.kanade.tachiyomi.animeextension.es.serieskao

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class Serieskao : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "SeriesKao"

    override val baseUrl = "https://serieskao.top"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

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
    }

    override fun popularAnimeSelector(): String = "div.Posters a.Posters-link"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
            title = element.select("a div.listing-content p").text()
            thumbnail_url = element.select("a img").attr("src").replace("/w154/", "/w200/")
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
                setUrlWithoutDomain(response.request.url.toString())
            }
            episodes.add(episode)
        } else {
            jsoup.select("div.tab-content div a").forEachIndexed { index, element ->
                val episode = SEpisode.create().apply {
                    episode_number = (index + 1).toFloat()
                    name = element.text()
                    setUrlWithoutDomain(element.attr("abs:href"))
                }
                episodes.add(episode)
            }
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val data = document.selectFirst("script:containsData(video[1] = )")?.data() ?: return emptyList()

        // Extrae los enlaces de video de `video` en el script
        val videoUrls = Regex("video\\[\\d+\\] = '([^']+)'").findAll(data)
            .map { it.groupValues[1] }
            .toList()

        // Itera a través de los enlaces de video encontrados
        videoUrls.forEach { videoUrl ->
            runCatching {
                // Ejecuta la solicitud para obtener el cuerpo de la página del enlace de video
                val body = client.newCall(GET(videoUrl)).execute().asJsoup()

                // Extrae el idioma correspondiente basado en el contexto

                // Extrae y desencripta los enlaces con `extractNewExtractorLinks`
                extractNewExtractorLinks(body, body.toString())?.forEach { (url, lang) ->
                    runCatching {
                        // Envía el enlace desencriptado y el idioma al resolver
                        serverVideoResolver(url, lang).also { videoList.addAll(it) }
                    }.onFailure {
                        // Log para depuración si falla el proceso
                        Log.e("videoListParse", "Error al procesar URL de video: $url", it)
                    }
                }
            }.onFailure {
                // Log para depuración si falla la solicitud de video
                Log.e("videoListParse", "Error al obtener cuerpo de videoUrl: $videoUrl", it)
            }
        }

        return videoList
    }

    private fun extractNewExtractorLinks(doc: Document, htmlContent: String): List<Pair<String, String>>? {
        val links = mutableListOf<Pair<String, String>>()
        val jsLinksMatch = getFirstMatch("""dataLink = (\[.+?\]);""".toRegex(), htmlContent)
        if (jsLinksMatch.isEmpty()) return null

        val items = Json.decodeFromString<List<Item>>(jsLinksMatch)

        // Diccionario de idiomas
        val idiomas = mapOf("LAT" to "[LAT]", "ESP" to "[CAST]", "SUB" to "[SUB]")

        items.forEach { item ->
            val languageCode = idiomas[item.video_language] ?: "unknown"

            item.sortedEmbeds.forEach { embed ->
                val decryptedLink = CryptoAES.decrypt(embed.link, "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE")
                links.add(Pair(decryptedLink, languageCode))
            }
        }

        return links.ifEmpty { null }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client) }
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
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        return runCatching {
            Log.d("SoloLatino", "URL: $url")
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
            title = document.selectFirst("h1.m-b-5")!!.text()
            thumbnail_url = document.selectFirst("div.card-body div.row div.col-sm-3 img.img-fluid")!!
                .attr("src").replace("/w154/", "/w500/")
            description = document.selectFirst("div.col-sm-4 div.text-large")!!.ownText()
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
        val file_id: Int,
        val video_language: String, // Campo nuevo para almacenar el idioma
        val sortedEmbeds: List<Embed>,
    )

    @Serializable
    data class Embed(
        val servername: String,
        val link: String,
        val type: String,
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
