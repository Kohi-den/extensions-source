package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeOnlineNinja : DooPlay(
    "es",
    "AnimeOnline.Ninja",
    "https://ww3.animeonline.ninja",
) {
    override val client by lazy {
        if (preferences.getBoolean(PREF_VRF_INTERCEPT_KEY, PREF_VRF_INTERCEPT_DEFAULT)) {
            network.client.newBuilder()
                .addInterceptor(VrfInterceptor())
                .build()
        } else {
            network.client
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendencias/$page")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeOnlineNinjaFilters.getSearchParameters(filters)
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
                            "serie" -> "TV"
                            "pelicula" -> "movies"
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

    // ============================== Episodes ==============================
    override val episodeMovieText = "Película"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealAnimeDoc(response.asJsoup())
        val seasonList = doc.select(seasonListSelector)
        return if (seasonList.isEmpty()) {
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
                    episode_number = 1F
                    name = episodeMovieText
                },
            )
        } else {
            seasonList.reversed().flatMap { seasonElement ->
                getSeasonEpisodes(seasonElement).reversed()
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking { player ->
            val name = player.selectFirst("span.title")!!.text()
            val url = getPlayerUrl(player)
            extractVideos(url, name)
        }
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    private fun extractVideos(url: String, lang: String): List<Video> {
        try {
            return when {
                arrayOf("saidochesto.top").any(url) || "MULTISERVER" in lang.uppercase() -> extractFromMulti(url)
                arrayOf("filemoon", "filemooon", "moon").any(url) -> filemoonExtractor.videosFromUrl(url, "$lang Filemoon:", headers)
                arrayOf("doodstream", "dood.", "ds2play", "doods.").any(url) -> doodExtractor.videosFromUrl(url, "$lang DoodStream", false)
                arrayOf("streamtape", "stp", "stape").any(url) -> streamTapeExtractor.videosFromUrl(url, "$lang StreamTape")
                arrayOf("mixdrop").any(url) -> mixDropExtractor.videoFromUrl(url, prefix = "$lang ")
                arrayOf("uqload").any(url) -> uqloadExtractor.videosFromUrl(url, prefix = lang)
                "wolfstream" in url -> {
                    client.newCall(GET(url, headers)).execute()
                        .asJsoup()
                        .selectFirst("script:containsData(sources)")
                        ?.data()
                        ?.let { jsData ->
                            val videoUrl = jsData.substringAfter("{file:\"").substringBefore("\"")
                            listOf(Video(videoUrl, "$lang WolfStream", videoUrl, headers = headers))
                        }
                }
                arrayOf("mp4upload").any(url) -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$lang ")
                arrayOf("vidhide", "filelions.top", "vid.").any(url) -> vidHideExtractor.videosFromUrl(url) { "$lang VidHide:$it" }
                arrayOf("wishembed", "streamwish", "strwish", "wish").any(url) -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$lang StreamWish:$it" })
                else -> null
            } ?: emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    private fun extractFromMulti(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val prefLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val langSelector = when {
            prefLang.isBlank() -> "div"
            else -> "div.OD_$prefLang"
        }
        return document.select("div.ODDIV $langSelector > li").flatMap {
            val hosterUrl = it.attr("onclick").toString()
                .substringAfter("('")
                .substringBefore("')")
            val lang = when (langSelector) {
                "div" -> {
                    it.parent()?.attr("class").toString()
                        .substringAfter("OD_", "")
                        .substringBefore(" ")
                }
                else -> prefLang
            }
            extractVideos(hosterUrl, lang)
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v1/post/$id?type=$type&source=$num"))
            .execute()
            .let { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
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
    override val latestUpdatesPath = "episodio"

    override fun latestUpdatesNextPageSelector() = "div.pagination > *:last-child:not(span):not(.current)"

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = AnimeOnlineNinjaFilters.FILTER_LIST

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
    override val episodeNumberRegex by lazy { Regex("""(\d+(?:\.\d+)?)$""") }

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Uqload"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "ES", "LAT")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "ES", "LAT")
        private val SERVER_LIST = arrayOf("Filemoon", "DoodStream", "StreamTape", "MixDrop", "Uqload", "WolfStream", "saidochesto.top", "VidHide", "StreamWish", "Mp4Upload")

        private const val PREF_VRF_INTERCEPT_KEY = "vrf_intercept"
        private const val PREF_VRF_INTERCEPT_TITLE = "Intercept VRF links (Requiere Reiniciar)"
        private const val PREF_VRF_INTERCEPT_SUMMARY = "Intercept VRF links and open them in the browser"
        private const val PREF_VRF_INTERCEPT_DEFAULT = false
    }
}
