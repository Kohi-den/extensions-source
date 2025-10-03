package eu.kanade.tachiyomi.animeextension.es.cinecalidad

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.goodstramextractor.GoodStreamExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class CineCalidad : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "CineCalidad"

    override val baseUrl = "https://www.cinecalidad.ec"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
        )

        private val REGEX_EPISODE_NAME = "^S(\\d+)-E(\\d+)$".toRegex()
    }

    override fun popularAnimeSelector(): String = ".item[data-cf] .custom"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select("img").attr("alt")
            thumbnail_url = element.select("img").attr("data-src")
            setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
        }
    }

    override fun popularAnimeNextPageSelector(): String = ".nextpostslink"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.location().contains("ver-pelicula")) {
            listOf(
                SEpisode.create().apply {
                    name = "PELÍCULA"
                    episode_number = 1f
                    setUrlWithoutDomain(document.location())
                },
            )
        } else {
            document.select(".mark-1").mapIndexed { idx, it ->
                val epLink = it.select(".episodiotitle a").attr("href")
                val epName = it.select(".episodiotitle a").text()
                val nameSeasonEpisode = it.select(".numerando").text()
                val matchResult = REGEX_EPISODE_NAME.matchEntire(nameSeasonEpisode)

                val episodeName = if (matchResult != null) {
                    val season = matchResult.groups[1]?.value
                    val episode = matchResult.groups[2]?.value
                    "T$season - E$episode - $epName"
                } else {
                    "$nameSeasonEpisode $epName"
                }

                SEpisode.create().apply {
                    name = episodeName
                    episode_number = idx + 1f
                    setUrlWithoutDomain(epLink)
                }
            }.reversed()
        }
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("#playeroptionsul li").parallelCatchingFlatMapBlocking {
            val link = it.attr("data-option")
            serverVideoResolver(link)
        }
    }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return runCatching {
            when {
                embedUrl.contains("voe") -> VoeExtractor(client).videosFromUrl(url)
                embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> OkruExtractor(client).videosFromUrl(url)
                embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") -> {
                    val vidHeaders = headers.newBuilder()
                        .add("Origin", "https://${url.toHttpUrl().host}")
                        .add("Referer", "https://${url.toHttpUrl().host}/")
                        .build()
                    FilemoonExtractor(client).videosFromUrl(url, prefix = "Filemoon:", headers = vidHeaders)
                }
                !embedUrl.contains("disable") && (embedUrl.contains("amazon") || embedUrl.contains("amz")) -> {
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
                        listOf(Video(videoUrl, "Amazon", videoUrl))
                    } else {
                        emptyList()
                    }
                }
                embedUrl.contains("uqload") -> UqloadExtractor(client).videosFromUrl(url)
                embedUrl.contains("mp4upload") -> Mp4uploadExtractor(client).videosFromUrl(url, headers)
                embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") || embedUrl.contains("wishfast") -> {
                    val docHeaders = headers.newBuilder()
                        .add("Origin", "https://streamwish.to")
                        .add("Referer", "https://streamwish.to/")
                        .build()
                    StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
                }
                embedUrl.contains("doodstream") || embedUrl.contains("dood.") || embedUrl.contains("ds2play") || embedUrl.contains("doods.") -> {
                    DoodExtractor(client).videosFromUrl(url.replace("https://doodstream.com/e/", "https://dood.to/e/"), "DoodStream")
                }
                embedUrl.contains("streamlare") -> StreamlareExtractor(client).videosFromUrl(url)
                embedUrl.contains("yourupload") || embedUrl.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = headers)
                embedUrl.contains("burstcloud") || embedUrl.contains("burst") -> BurstCloudExtractor(client).videoFromUrl(url, headers = headers)
                embedUrl.contains("fastream") -> FastreamExtractor(client, headers).videosFromUrl(url, prefix = "Fastream:")
                embedUrl.contains("upstream") -> UpstreamExtractor(client).videosFromUrl(url)
                embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape") -> listOf(StreamTapeExtractor(client).videoFromUrl(url, quality = "StreamTape")!!)
                embedUrl.contains("ahvsh") || embedUrl.contains("streamhide") || embedUrl.contains("guccihide") || embedUrl.contains("streamvid") || embedUrl.contains("vidhide") -> StreamHideVidExtractor(client, headers).videosFromUrl(url)
                embedUrl.contains("goodstream") -> GoodStreamExtractor(client, headers).videosFromUrl(url, name = "GoodStream: ")
                else -> UniversalExtractor(client).videosFromUrl(url, headers)
            }
        }.getOrNull() ?: emptyList()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        val genreQuery = if (genreFilter.toUriPart().contains("fecha-de-lanzamiento")) {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            "$baseUrl/${genreFilter.toUriPart()}/$currentYear/page/$page"
        } else {
            "$baseUrl/${genreFilter.toUriPart()}/page/$page"
        }

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query")
            genreFilter.state != 0 -> GET(genreQuery)
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Series", "ver-serie"),
            Pair("Estrenos", "fecha-de-lanzamiento/"),
            Pair("Destacadas", "#destacado"),
            Pair("Acción", "genero-de-la-pelicula/accion"),
            Pair("Animación", "genero-de-la-pelicula/animacion"),
            Pair("Anime", "genero-de-la-pelicula/anime"),
            Pair("Aventura", "genero-de-la-pelicula/aventura"),
            Pair("Bélico", "genero-de-la-pelicula/belica"),
            Pair("Ciencia ficción", "genero-de-la-pelicula/ciencia-ficcion"),
            Pair("Crimen", "genero-de-la-pelicula/crimen"),
            Pair("Comedia", "genero-de-la-pelicula/comedia"),
            Pair("Documental", "genero-de-la-pelicula/documental"),
            Pair("Drama", "genero-de-la-pelicula/drama"),
            Pair("Familiar", "genero-de-la-pelicula/familia"),
            Pair("Fantasía", "genero-de-la-pelicula/fantasia"),
            Pair("Historia", "genero-de-la-pelicula/historia"),
            Pair("Música", "genero-de-la-pelicula/musica"),
            Pair("Misterio", "genero-de-la-pelicula/misterio"),
            Pair("Terror", "genero-de-la-pelicula/terror"),
            Pair("Suspenso", "genero-de-la-pelicula/suspense"),
            Pair("Romance", "genero-de-la-pelicula/romance"),
            Pair("Dc Comics", "genero-de-la-pelicula/peliculas-de-dc-comics-online-cinecalidad"),
            Pair("Marvel", "genero-de-la-pelicula/universo-marvel"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst(".single_left table img")?.attr("data-src")
            description = document.select(".single_left table p").text().removeSurrounding("\"").substringBefore("Títulos:")
            status = SAnime.UNKNOWN
            document.select(".single_left table p > span").map { it.text() }.map { textContent ->
                when {
                    "Género" in textContent -> genre = textContent.replace("Género:", "").trim().split(", ").joinToString { it }
                    "Creador" in textContent -> author = textContent.replace("Creador:", "").trim().split(", ").firstOrNull()
                    "Elenco" in textContent -> artist = textContent.replace("Elenco:", "").trim().split(", ").firstOrNull()
                }
            }
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return GET("$baseUrl/fecha-de-lanzamiento/$currentYear/page/$page")
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

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
}
