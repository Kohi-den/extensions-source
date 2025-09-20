package eu.kanade.tachiyomi.animeextension.es.pelisforte

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.text.ifEmpty
import kotlin.text.lowercase

open class PelisForte : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "PelisForte"

    override val baseUrl = "https://www1.pelisforte.se"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape",
            "Fastream", "Filemoon", "StreamWish", "Okru",
            "VidGuard",
        )
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/todas-las-peliculas/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("#movies-a li[id*=post-]")
        val nextPage = document.select(".pagination .nav-links .current ~ a:not(.page-link)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("article > a")?.attr("abs:href") ?: "")
                title = element.selectFirst("article .entry-header .entry-title")?.text() ?: ""
                thumbnail_url = element.selectFirst("article .post-thumbnail figure img")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    protected open fun org.jsoup.nodes.Element.getImageUrl(): String? {
        return if (hasAttr("srcset")) {
            try {
                fetchUrls(attr("abs:srcset")).maxOrNull()
            } catch (_: Exception) {
                attr("abs:src")
            }
        } else {
            attr("abs:src")
        }
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page?s=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".alg-cr .entry-header .entry-title")?.text() ?: ""
            description = document.select(".alg-cr .description").text()
            thumbnail_url = document.selectFirst(".alg-cr .post-thumbnail img")?.getImageUrl()
            genre = document.select(".genres a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }

        document.select(".cast-lst li").map {
            if (it.select("span").text().contains("Director", true)) {
                animeDetails.author = it.selectFirst("p > a")?.text()
            }
            if (it.select("span").text().contains("Actores", true)) {
                animeDetails.artist = it.selectFirst("p > a")?.text()
            }
        }
        return animeDetails
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Película"
                episode_number = 1F
            },
        )
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(".video-player iframe").parallelCatchingFlatMapBlocking {
            val id = it.parent()?.attr("id")
            val idTab = document.selectFirst("[href=\"#$id\"]")?.closest(".lrt")?.attr("id")
            val lang = document.select("[tab=$idTab]").text()
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            val key = src.substringAfter("/?h=")
            val player = "https://${src.toHttpUrl().host}/r.php?h=$key"

            val prefix = when {
                lang.contains("Latino", true) -> "[LAT]"
                lang.contains("Subtitulado", true) -> "[SUB]"
                lang.contains("Castellano", true) -> "[CAST]"
                else -> ""
            }

            val locationsDdh = client.newCall(
                GET(player, headers = headers.newBuilder().add("referer", src).build()),
            ).execute().networkResponse.toString()

            fetchUrls(locationsDdh).flatMap { serverVideoResolver(it, prefix, src) }
        }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }

    private fun serverVideoResolver(url: String, prefix: String = "", referer: String): List<Video> {
        return runCatching {
            val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
            val newHeaders = headers.newBuilder().add("Referer", referer).build()
            when (matched) {
                "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
                "okru" -> okruExtractor.videosFromUrl(url, prefix, headers = newHeaders)
                "filemoon" -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
                "uqload" -> uqloadExtractor.videosFromUrl(url, prefix)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, newHeaders, prefix = "$prefix ")
                "streamwish" -> StreamWishExtractor(client, newHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                "doodstream" -> doodExtractor.videosFromUrl(url, "$prefix DoodStream")
                "streamlare" -> streamlareExtractor.videosFromUrl(url, prefix)
                "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers = newHeaders, prefix = "$prefix ")
                "burstcloud" -> burstCloudExtractor.videoFromUrl(url, headers = newHeaders, prefix = "$prefix ")
                "fastream" -> fastreamExtractor.videosFromUrl(url, prefix = "$prefix Fastream:")
                "upstream" -> upstreamExtractor.videosFromUrl(url, prefix = "$prefix ")
                "streamtape" -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")
                "vidguard" -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")
                else -> emptyList()
            }
        }.getOrNull() ?: emptyList()
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "uqload" to listOf("uqload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "streamlare" to listOf("streamlare", "slmaxed"),
        "yourupload" to listOf("yourupload", "upload"),
        "burstcloud" to listOf("burstcloud", "burst"),
        "fastream" to listOf("fastream"),
        "upstream" to listOf("upstream"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "bembed"),
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Acción", "peliculas/accion-p01"),
            Pair("Animación", "peliculas/animacion-p04"),
            Pair("Aventura", "peliculas/aventura"),
            Pair("Bélicas", "peliculas/belica"),
            Pair("Ciencia ficción", "peliculas/ciencia-ficcion"),
            Pair("Comedia", "peliculas/comedia"),
            Pair("Crimen", "peliculas/crimen"),
            Pair("Documentales", "peliculas/documental"),
            Pair("Drama", "peliculas/drama"),
            Pair("Familia", "peliculas/familia-p01"),
            Pair("Fantasía", "peliculas/fantasia-p01"),
            Pair("Historia", "peliculas/historia"),
            Pair("Misterio", "peliculas/misterio"),
            Pair("Música", "peliculas/musica"),
            Pair("Navidad", "peliculas/navidad"),
            Pair("Romance", "peliculas/romance"),
            Pair("Suspenso", "peliculas/suspense"),
            Pair("Terror", "peliculas/terror"),
            Pair("Western", "peliculas/western"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
