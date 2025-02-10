package eu.kanade.tachiyomi.animeextension.es.detodopeliculas

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class DeTodoPeliculas : DooPlay(
    "es",
    "DeTodo Peliculas",
    "https://detodopeliculas.nu",
) {
// ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/novedades/page/$page")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/peliculas-de-estreno/page/$page", headers)

    override fun videoListSelector() = "li.dooplay_player_option" // ul#playeroptionsul

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"
    override val prefQualityTitle = "Calidad preferida"

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }

// ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelFlatMapBlocking { player ->
            val flagSrc = player.selectFirst("span.flag img")?.attr("data-lazy-src") ?: ""
            val lang = when {
                "sub.png" in flagSrc -> "[SUB]"
                "cas.png" in flagSrc -> "[CAST]"
                "lat.png" in flagSrc -> "[LAT]"
                else -> "UNKNOWN"
            }
            val url = getPlayerUrl(player)
                ?: return@parallelFlatMapBlocking emptyList<Video>()
            extractVideos(url, lang)
        }
    }

    private fun extractVideos(url: String, lang: String): List<Video> {
        val vidHideDomains = listOf("vidhide", "VidHidePro", "luluvdo", "vidhideplus")
        try {
            val videos = vidHideDomains.firstOrNull { it in url }?.let { domain ->
                streamHideVidExtractor.videosFromUrl(url, videoNameGen = { "$lang - $domain : $it" })
            } ?: emptyList()
            return when {
                videos.isNotEmpty() -> videos
                "uqload" in url -> uqloadExtractor.videosFromUrl(url, "$lang - ")
                "strwish" in url -> streamWishExtractor.videosFromUrl(url, "$lang - ")
                "vidguard" in url || "listeamed" in url -> vidGuardExtractor.videosFromUrl(url, "$lang - ")
                "voe" in url -> voeExtractor.videosFromUrl(url, "$lang - ")

                else -> emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
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

// ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = DeTodoPeliculasFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DeTodoPeliculasFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                if (params.genre in listOf("peliculas-de-estreno", "novedades", "peliculas-recomendadas", "peliculas")) {
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
    }
}
