package eu.kanade.tachiyomi.animeextension.es.detodopeliculas

import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
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
import org.jsoup.Jsoup
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
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

// ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val players = document.select("ul#playeroptionsul li")
        if (players.isEmpty()) return emptyList()

        return players.parallelFlatMapBlocking { player ->
            val flagSrc = sequenceOf(
                player.selectFirst("span.flag img")?.attr("data-lazy-src"),
                player.selectFirst("span.flag img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

            val lang = when {
                "sub" in flagSrc.lowercase() -> "[SUB]"
                "cas" in flagSrc.lowercase() -> "[CAST]"
                "lat" in flagSrc.lowercase() -> "[LAT]"
                else -> "UNKNOWN"
            }

            val url = getPlayerUrl(player, referer) ?: return@parallelFlatMapBlocking emptyList<Video>()
            extractVideos(url, lang, referer)
        }
    }

    private fun extractVideos(url: String, lang: String, referer: String, depth: Int = 0): List<Video> {
        if (depth >= 3) return emptyList()

        val normalized = normalizeUrl(url)
        // url log

        if (normalized.startsWith("$baseUrl/player")) {
            val decodedUrl = Uri.parse(normalized).getQueryParameter("id")
                ?.let { decodeBase64Url(it) ?: it }
                ?.let(::normalizeUrl)
                ?.takeIf { it.isNotBlank() }

            if (decodedUrl != null && decodedUrl != normalized) {
                return extractVideos(decodedUrl, lang, normalized, depth + 1)
            }
        }

        if (normalized.contains("trembed")) {
            val embedHeaders = headers.newBuilder()
                .add("Referer", referer)
                .add("Origin", baseUrl)
                .build()

            val embedBody = runCatching {
                client.newCall(GET(normalized, embedHeaders)).execute().use { response ->
                    if (!response.isSuccessful) "" else response.body.string()
                }
            }.getOrDefault("")

            if (embedBody.isBlank()) return emptyList()

            val iframeUrl = Jsoup.parse(embedBody)
                .selectFirst("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                ?.let { element ->
                    sequenceOf("src", "data-src", "data-lazy-src")
                        .map(element::attr)
                        .firstOrNull { it.isNotBlank() }
                }
                ?.let(::normalizeUrl)
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()

            return extractVideos(iframeUrl, lang, referer, depth + 1)
        }
        val vidHideDomains = listOf("vidhide", "vidhidepro", "luluvdo", "vidhideplus")

        return runCatching {
            vidHideDomains.firstOrNull { normalized.contains(it, ignoreCase = true) }
                ?.let { domain ->
                    streamHideVidExtractor.videosFromUrl(
                        normalized,
                        videoNameGen = { "$lang - ${domain.uppercase()} : $it" },
                    )
                }
                ?: when {
                    "uqload" in normalized -> uqloadExtractor.videosFromUrl(normalized, "$lang - ")
                    listOf("streamwish", "strwish", "wishembed").any { normalized.contains(it) } -> streamWishExtractor.videosFromUrl(normalized, "$lang - ")
                    listOf("vidguard", "listeamed", "guard", "listeam").any { normalized.contains(it) } -> vidGuardExtractor.videosFromUrl(normalized, "$lang - ")
                    "voe" in normalized -> voeExtractor.videosFromUrl(normalized, "$lang - ")
                    else -> emptyList()
                }
        }.getOrElse {
            it.printStackTrace()
            emptyList()
        }
    }

    private fun getPlayerUrl(player: Element, referer: String): String? {
        val directCandidate = sequenceOf(
            player.attr("data-option"),
            player.attr("data-player"),
            player.attr("data-src"),
            player.attr("data-url"),
            player.attr("data-video"),
            player.selectFirst("a[href]")?.attr("href"),
        ).firstOrNull { !it.isNullOrBlank() }

        if (!directCandidate.isNullOrBlank()) {
            return normalizeUrl(directCandidate)
        }

        val post = player.attr("data-post")
        val nume = player.attr("data-nume")
        val type = player.attr("data-type").ifBlank { "movie" }

        if (post.isBlank() || nume.isBlank()) return null

        val ajaxHeaders = headers.newBuilder()
            .add("Referer", referer)
            .add("Origin", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()

        val responseBody = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body)).execute().use { response ->
            response.body.string()
        }

        if (responseBody.isBlank()) return null

        val embedByRegex = embedUrlRegex.find(responseBody)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let(::normalizeUrl)
            ?.takeIf { it.isNotBlank() }
        if (embedByRegex != null) return embedByRegex

        val iframe = Jsoup.parse(responseBody).selectFirst("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            ?.let { element ->
                sequenceOf("src", "data-src", "data-lazy-src")
                    .map(element::attr)
                    .firstOrNull { it.isNotBlank() }
            }
            ?.let(::normalizeUrl)
            ?.takeIf { it.isNotBlank() }
        if (iframe != null) return iframe

        val source = Jsoup.parse(responseBody).selectFirst("source[src]")?.attr("src")
            ?.let(::normalizeUrl)
            ?.takeIf { it.isNotBlank() }

        return source
    }

    private fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$baseUrl$trimmed"
            else -> trimmed
        }
    }

    private val embedUrlRegex = Regex("\"embed_url\"\\s*:\\s*\"([^\"]+)\"")

    private fun decodeBase64Url(data: String): String? = runCatching {
        val sanitized = data
            .replace('-', '+')
            .replace('_', '/')
            .let { str ->
                val padding = str.length % 4
                if (padding == 0) str else str + "=".repeat(4 - padding)
            }
        String(Base64.decode(sanitized, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

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
