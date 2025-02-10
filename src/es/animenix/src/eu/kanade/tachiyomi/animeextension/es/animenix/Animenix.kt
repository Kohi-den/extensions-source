package eu.kanade.tachiyomi.animeextension.es.animenix

import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Animenix : DooPlay(
    "es",
    "Animenix",
    "https://animenix.com",
) {

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ratings/$page")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Search ===============================

    // ============================== Episodes ==============================
    override val episodeMovieText = "Película"

    override fun videoListParse(response: Response): List<Video> {
        val players = response.asJsoup().select("li.dooplay_player_option")
        return players.flatMap { player ->
            runCatching {
                val link = getPlayerUrl(player)
                getPlayerVideos(link)
            }.getOrElse { emptyList() }
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()
        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .let { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(headers = headers, client = client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun getPlayerVideos(link: String): List<Video> {
        return when {
            link.contains("filemoon") -> filemoonExtractor.videosFromUrl(link)
            link.contains("swdyu") -> streamWishExtractor.videosFromUrl(link)
            link.contains("wishembed") || link.contains("cdnwish") || link.contains("flaswish") || link.contains("sfastwish") || link.contains("streamwish") || link.contains("asnwish") -> streamWishExtractor.videosFromUrl(link)
            else -> universalExtractor.videosFromUrl(link, headers)
        }
    }

    // =========================== Anime Details ============================
    override fun Document.getDescription(): String {
        return select("$additionalInfoSelector div.wp-content p")
            .eachText()
            .joinToString("\n")
    }

    override val additionalInfoItems = listOf("Título", "Temporadas", "Episodios", "Duración media")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/ver/page/$page", headers)

    override fun latestUpdatesNextPageSelector() = "div.pagination > *:last-child:not(span):not(.current)"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimenixFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                if (params.genre in listOf("tendencias", "ratings")) {
                    "/" + params.genre
                } else {
                    "/genero/${params.genre}"
                }
            }
            params.language.isNotBlank() -> "/genero/${params.language}"
            params.year.isNotBlank() -> "/release/${params.year}"
            params.movie.isNotBlank() -> {
                if (params.movie == "pelicula") {
                    "/pelicula"
                } else {
                    "/genero/${params.movie}"
                }
            }
            else -> buildString {
                append(
                    when {
                        query.isNotBlank() -> "/?s=$query"
                        params.letter.isNotBlank() -> "/letra/${params.letter}/?"
                        else -> "/tendencias/?"
                    },
                )

                append(
                    if (contains("tendencias")) {
                        "&get=${when (params.type){
                            "anime" -> "serie"
                            "pelicula" -> "pelicula"
                            else -> "todos"
                        }}"
                    } else {
                        "&tipo=${params.type}"
                    },
                )

                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path")
        } else if (path.startsWith("/letra") || path.startsWith("/tendencias")) {
            val before = path.substringBeforeLast("/")
            val after = path.substringAfterLast("/")
            GET("$baseUrl$before/page/$page/$after")
        } else {
            GET("$baseUrl$path/page/$page")
        }
    }

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = AnimenixFilters.FILTER_LIST

    // ============================== Settings ==============================
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

        val vrfIterceptPref = CheckBoxPreference(screen.context).apply {
            key = PREF_VRF_INTERCEPT_KEY
            title = PREF_VRF_INTERCEPT_TITLE
            summary = PREF_VRF_INTERCEPT_SUMMARY
            setDefaultValue(PREF_VRF_INTERCEPT_DEFAULT)
        }

        screen.addPreference(vrfIterceptPref)
        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================
    override fun String.toDate() = 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "ES", "LAT")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "ES", "LAT")

        private const val PREF_VRF_INTERCEPT_KEY = "vrf_intercept"
        private const val PREF_VRF_INTERCEPT_TITLE = "Intercept VRF links (Requiere Reiniciar)"
        private const val PREF_VRF_INTERCEPT_SUMMARY = "Intercept VRF links and open them in the browser"
        private const val PREF_VRF_INTERCEPT_DEFAULT = false
    }
}
