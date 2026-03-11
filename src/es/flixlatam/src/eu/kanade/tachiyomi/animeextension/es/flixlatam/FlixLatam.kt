package eu.kanade.tachiyomi.animeextension.es.flixlatam

import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
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
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import kotlin.text.RegexOption

class FlixLatam : DooPlay(
    "es",
    "FlixLatam",
    "https://flixlatam.com",
) {
    private val json by lazy { Json { ignoreUnknownKeys = true } }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/pelicula/page/$page")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lanzamiento/2024/page/$page")

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"
    override val prefQualityTitle = "Calidad preferida"

    override fun videoListSelector() = "li.dooplay_player_option" // ul#playeroptionsul

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoSet = mutableSetOf<Video>()
        val players = document.select("ul#playeroptionsul li")
        val referer = response.request.url.toString()
        val embedHeaders = headersBuilder().set("Referer", referer).build()

        players.parallelFlatMapBlocking { player ->
            val url = getPlayerUrl(player)
                ?: return@parallelFlatMapBlocking emptyList<Video>()
            Log.i("bruh url", url)
            if (url.contains("embed69")) {
                client.newCall(GET(url, embedHeaders)).execute().use { res ->
                    val htmlContent = res.body.string()
                    if (htmlContent.isBlank()) return@parallelFlatMapBlocking emptyList()

                    val embedDoc = Jsoup.parse(htmlContent)
                    val links = extractNewExtractorLinks(embedDoc, htmlContent)
                        ?: return@parallelFlatMapBlocking emptyList<Video>()

                    links.forEach { (link, language) ->
                        videoSet.addAll(serverVideoResolver(link, " $language"))
                    }
                }
            }
            return@parallelFlatMapBlocking emptyList()
        }

        return videoSet.toList()
    }

    private fun getPlayerUrl(player: Element): String? {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute().body.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
            .takeIf(String::isNotBlank)
    }

    /*-------------------------------- Video extractors ------------------------------------*/
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
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "okru" -> okruExtractor.videosFromUrl(url, prefix)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, "$prefix Filemoon:")
                "amazon" -> extractAmazonVideo(url, prefix)
                "uqload" -> uqloadExtractor.videosFromUrl(url, prefix)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, "$prefix ")
                "streamwish" -> streamWishExtractor.videosFromUrl(url, "$prefix StreamWish:")
                "doodstream" -> doodExtractor.videosFromUrl(url.replace("https://doodstream.com/e/", "https://d0000d.com/e/"), "$prefix DoodStream")
                "streamlare" -> streamlareExtractor.videosFromUrl(url, prefix)
                "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers, "$prefix ")
                "burstcloud" -> burstCloudExtractor.videoFromUrl(url, headers, "$prefix ")
                "fastream" -> fastreamExtractor.videosFromUrl(url, "$prefix Fastream:")
                "upstream" -> upstreamExtractor.videosFromUrl(url, "$prefix ")
                "streamsilk" -> streamSilkExtractor.videosFromUrl(url, "$prefix StreamSilk:")
                "streamtape" -> streamTapeExtractor.videosFromUrl(url, "$prefix StreamTape")
                "vidhide" -> streamHideVidExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamHideVid:$it" })
                "vidguard" -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")
                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "amazon" to listOf("amazon", "amz"),
        "uqload" to listOf("uqload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "streamlare" to listOf("streamlare", "slmaxed"),
        "yourupload" to listOf("yourupload", "upload"),
        "burstcloud" to listOf("burstcloud", "burst"),
        "fastream" to listOf("fastream"),
        "upstream" to listOf("upstream"),
        "streamsilk" to listOf("streamsilk"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
        "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "bembed"),
    )

    private fun extractAmazonVideo(url: String, prefix: String): List<Video> {
        val body = client.newCall(GET(url)).execute().asJsoup()
        val shareId = body.selectFirst("script:containsData(var shareId)")
            ?.data()
            ?.substringAfter("shareId = \"")
            ?.substringBefore("\"") ?: return emptyList()

        val amazonApiJson = client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
            .execute().asJsoup()

        val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
        val amazonApi = client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
            .execute().asJsoup()

        val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
        return listOf(Video(videoUrl, "$prefix Amazon", videoUrl))
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
            Log.e("FlixLatam", "No se pudo parsear dataLink", it)
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

    private fun getFirstMatch(regex: Regex, input: String): String? {
        return regex.find(input)?.groupValues?.get(1)
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
            when {
                expr.removeOuterCall("JSON.parse") != null -> expr = expr.removeOuterCall("JSON.parse")!!
                expr.removeOuterCall("window.JSON.parse") != null -> expr = expr.removeOuterCall("window.JSON.parse")!!
                expr.removeOuterCall("decodeURIComponent") != null -> {
                    val inner = expr.removeOuterCall("decodeURIComponent")!!.trimMatchingQuotes()
                    expr = runCatching { URLDecoder.decode(inner, "UTF-8") }.getOrElse { return null }
                }
                expr.removeOuterCall("window.decodeURIComponent") != null -> {
                    val inner = expr.removeOuterCall("window.decodeURIComponent")!!.trimMatchingQuotes()
                    expr = runCatching { URLDecoder.decode(inner, "UTF-8") }.getOrElse { return null }
                }
                expr.removeOuterCall("atob") != null -> {
                    val inner = expr.removeOuterCall("atob")!!.trimMatchingQuotes()
                    expr = runCatching { String(Base64.decode(inner, Base64.DEFAULT)) }.getOrElse { return null }
                }
                expr.removeOuterCall("window.atob") != null -> {
                    val inner = expr.removeOuterCall("window.atob")!!.trimMatchingQuotes()
                    expr = runCatching { String(Base64.decode(inner, Base64.DEFAULT)) }.getOrElse { return null }
                }
                else -> break
            }
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

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = FlixLatamFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = FlixLatamFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                if (params.genre in listOf("ratings", "tendencias", "pelicula")) {
                    "/${params.genre}"
                } else {
                    "/genero/${params.genre}"
                }
            }
            else -> buildString {
                append(
                    when {
                        query.isNotBlank() -> "/?s=$query"
                        else -> "/"
                    },
                )

                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path")
        } else {
            GET("$baseUrl$path/page/$page")
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
        val download: String? = null,
    )

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
        private const val PREF_LANG_DEFAULT = "[LAT]"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Uqload"
        private val PREF_LANG_ENTRIES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val PREF_LANG_VALUES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val SERVER_LIST = arrayOf("StreamWish", "Uqload", "VidGuard", "StreamHideVid", "Voe")
        private const val AES_KEY = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"
        private val DATA_LINK_REGEX = """dataLink\s*=\s*([^;]+);""".toRegex(RegexOption.DOT_MATCHES_ALL)
    }
}
