
package eu.kanade.tachiyomi.animeextension.es.katanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.katanime.extractors.UnpackerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Katanime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Katanime"

    override val baseUrl = "https://katanime.net"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        const val DECRYPTION_PASSWORD = "hanabi"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "VidGuard"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "VidGuard",
            "Filemoon",
            "StreamTape",
            "FileLions",
            "DoodStream",
            "Sendvid",
            "LuluStream",
            "Mp4Upload",
        )

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".comics-title")?.ownText() ?: ""
            description = document.selectFirst("#sinopsis p")?.ownText()
            genre = document.select(".anime-genres a").joinToString { it.text() }
            status = with(document.select(".details-by #estado").text()) {
                when {
                    contains("Finalizado", true) -> SAnime.COMPLETED
                    contains("Emision", true) -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            }
        }
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/populares", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("#article-div .full > a")
        val nextPage = document.select(".pagination .active ~ li:not(.disabled)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst("img")!!.attr("alt")
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return GET("$baseUrl/animes?fecha=$currentYear&p=$page")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = KatanimeFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&p=$page", headers)
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&p=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        return jsoup.select("#c_list .cap_list").map {
            SEpisode.create().apply {
                name = it.selectFirst(".entry-title-h2")?.ownText() ?: ""
                episode_number = it.selectFirst(".entry-title-h2")?.ownText()?.substringAfter("Capítulo")?.trim()?.toFloat() ?: 0F
                date_upload = it.selectFirst(".timeago")?.attr("datetime")?.toDate() ?: 0L
                setUrlWithoutDomain(it.attr("abs:href"))
            }
        }.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("[data-player]:not([data-player-name=\"Mega\"])").forEach { element ->
            runCatching {
                val dataPlayer = element.attr("data-player")
                val playerDocument = client.newCall(GET("$baseUrl/reproductor?url=$dataPlayer"))
                    .execute()
                    .asJsoup()

                val encryptedData = playerDocument
                    .selectFirst("script:containsData(var e =)")?.data()
                    ?.substringAfter("var e = '")?.substringBefore("';")
                    ?: return emptyList()

                val json = encryptedData.parseAs<CryptoDto>()
                val decryptedLink = CryptoAES.decryptWithSalt(json.ct!!, json.s!!, DECRYPTION_PASSWORD)
                    .replace("\\/", "/").replace("\"", "")

                serverVideoResolver(decryptedLink).also(videoList::addAll)
            }
        }
        return videoList
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val unpackerExtractor by lazy { UnpackerExtractor(client, headers) }

    private fun serverVideoResolver(url: String): List<Video> {
        return when {
            arrayOf("wishembed", "streamwish", "strwish", "wish").any(url) -> StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            arrayOf("doodstream", "dood.", "ds2play", "doods.").any(url) -> doodExtractor.videosFromUrl(url, "DoodStream")
            arrayOf("streamtape", "stp", "stape").any(url) -> streamTapeExtractor.videosFromUrl(url, quality = "StreamTape")
            arrayOf("filemoon", "moonplayer").any(url) -> filemoonExtractor.videosFromUrl(url, prefix = "Filemoon:")
            arrayOf("sendvid").any(url) -> sendvidExtractor.videosFromUrl(url)
            arrayOf("lulu").any(url) -> unpackerExtractor.videosFromUrl(url)
            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url)
            arrayOf("mp4upload").any(url) -> mp4uploadExtractor.videosFromUrl(url, headers)
            else -> emptyList()
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

    override fun getFilterList(): AnimeFilterList = KatanimeFilters.FILTER_LIST

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

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L

    private fun org.jsoup.nodes.Element.getImageUrl(): String? {
        return when {
            isValidUrl("data-src") -> attr("abs:data-src")
            isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
            isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
            isValidUrl("src") -> attr("abs:src")
            else -> ""
        }
    }

    private fun org.jsoup.nodes.Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("data:image/")
    }

    @Serializable
    data class CryptoDto(
        @SerialName("ct") var ct: String? = null,
        @SerialName("iv") var iv: String? = null,
        @SerialName("s") var s: String? = null,
    )
}
