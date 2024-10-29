package eu.kanade.tachiyomi.animeextension.es.pandrama

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder

class Pandrama : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Pandrama"

    override val baseUrl = "https://pandra.ma"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vk"
        private val SERVER_LIST = arrayOf("Vk", "Okru")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val details = SAnime.create()

        for (element in document.select(".hl-full-box ul li")) {
            val title = element.select("em").text()
            val content = element.select("span")

            when {
                title.contains("Director:") -> details.author = element.ownText().trim()
                title.contains("Estado:") -> details.status = parseStatus(content.text())
                title.contains("Protagonistas:") -> details.artist = element.selectFirst("a")?.text()
                title.contains("Género:") -> details.genre = element.select("a").joinToString { it.text() }
                title.contains("Sinopsis:") -> details.description = element.ownText().trim()
            }
        }

        return details
    }

    private fun parseStatus(status: String?) = when (status.orEmpty()) {
        "En Emisión" -> SAnime.ONGOING
        "Finalizado" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/vodshow/Dramas--------$page---/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".hl-vod-list li > a")
        val nextPage = document.select(".hl-page-wrap a:not(.hl-disad) span:contains(siguiente)").any()
        val animeList = elements.map { element ->
            val langTag = element.select(".hl-pic-tag").text().trim()
            val prefix = when {
                langTag.contains("Español LAT") -> "\uD83C\uDDF2\uD83C\uDDFD "
                langTag.contains("Español ES") -> "\uD83C\uDDEA\uD83C\uDDF8 "
                else -> ""
            }
            SAnime.create().apply {
                title = """$prefix ${element.attr("title")}""".trim()
                thumbnail_url = element.attr("abs:data-original")
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/vodshow/Dramas--hits------$page---/", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = when {
            query.isNotBlank() -> "$baseUrl/vodsearch/$query----------$page---/"
            else -> "$baseUrl/vodshow/Dramas--------$page---/"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        if (!document.location().contains("vodsearch")) return popularAnimeParse(response)
        val elements = document.select(".hl-one-list .hl-item-thumb")
        val nextPage = document.select(".hl-page-wrap a:not(.hl-disad) span:contains(siguiente)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                title = element.attr("title")
                thumbnail_url = element.attr("abs:data-original")
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(".hl-plays-list li > a").groupBy { it.text().trim() }.map {
            val urlList = json.encodeToString(it.value.map { it.attr("abs:href") })
            SEpisode.create().apply {
                name = it.key
                episode_number = it.key.substringAfter("Ep.").trim().toFloatOrNull() ?: 0F
                url = urlList
            }
        }.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val serverData = json.decodeFromString<List<String>>(episode.url)
        return serverData.parallelCatchingFlatMapBlocking {
            val page = client.newCall(GET(it)).execute().asJsoup()
            val jsonData = page.selectFirst("script:containsData(var player_aaaa)")
                ?.data()?.substringAfter("var player_aaaa=")?.trim()
                ?: return@parallelCatchingFlatMapBlocking emptyList()
            val player = json.decodeFromString<PlayerDto>(jsonData)
            val url = if (player.encrypt == 2) {
                URLDecoder.decode(base64decode(player.url ?: ""), "UTF-8")
            } else {
                URLDecoder.decode(player.url ?: "", "UTF-8")
            }
            serverVideoResolver(url)
        }
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> okruExtractor.videosFromUrl(url)
            embedUrl.contains("vk.") -> vkExtractor.videosFromUrl(url)
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%d"

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
            summary = "%d"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    private val base64DecodeChars = intArrayOf(
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26,
        27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42,
        43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
    )

    private fun base64decode(str: String): String {
        var c1: Int
        var c2: Int
        var c3: Int
        var c4: Int
        var i = 0
        val len = str.length
        val out = StringBuilder()
        while (i < len) {
            do {
                c1 = base64DecodeChars[str[i].toInt() and 255]
                i++
            } while (i < len && c1 == -1)
            if (c1 == -1) break
            do {
                c2 = base64DecodeChars[str[i].toInt() and 255]
                i++
            } while (i < len && c2 == -1)
            if (c2 == -1) break
            out.append(((c1 shl 2) or ((c2 and 48) shr 4)).toChar())
            do {
                c3 = str[i].toInt() and 255
                if (c3 == 61) return out.toString()
                c3 = base64DecodeChars[c3]
                i++
            } while (i < len && c3 == -1)
            if (c3 == -1) break
            out.append((((c2 and 15) shl 4) or ((c3 and 60) shr 2)).toChar())
            do {
                c4 = str[i].toInt() and 255
                if (c4 == 61) return out.toString()
                c4 = base64DecodeChars[c4]
                i++
            } while (i < len && c4 == -1)
            if (c4 == -1) break
            out.append((((c3 and 3) shl 6) or c4).toChar())
        }
        return out.toString()
    }

    @Serializable
    data class PlayerDto(
        @SerialName("flag") var flag: String? = null,
        @SerialName("encrypt") var encrypt: Int? = null,
        @SerialName("trysee") var trysee: Int? = null,
        @SerialName("points") var points: Int? = null,
        @SerialName("link") var link: String? = null,
        @SerialName("poster") var poster: String? = null,
        @SerialName("doblado") var doblado: String? = null,
        @SerialName("vod_en_py") var vodEnPy: String? = null,
        @SerialName("link_next") var linkNext: String? = null,
        @SerialName("link_pre") var linkPre: String? = null,
        @SerialName("vod_data") var vodData: VodData? = VodData(),
        @SerialName("url") var url: String? = null,
        @SerialName("url_next") var urlNext: String? = null,
        @SerialName("from") var from: String? = null,
        @SerialName("server") var server: String? = null,
        @SerialName("note") var note: String? = null,
        @SerialName("id") var id: String? = null,
        @SerialName("sid") var sid: Int? = null,
        @SerialName("nid") var nid: Int? = null,
    )

    @Serializable
    data class VodData(
        @SerialName("vod_name") var vodName: String? = null,
        @SerialName("vod_actor") var vodActor: String? = null,
        @SerialName("vod_director") var vodDirector: String? = null,
        @SerialName("vod_class") var vodClass: String? = null,
    )
}
