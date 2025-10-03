package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Pelisplusto(override val name: String, override val baseUrl: String) : Pelisplushd(name, baseUrl) {

    override val id: Long = 1705636111422561130L

    private val json: Json by injectLazy()

    override val supportsLatest = false

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
        )
    }

    override fun popularAnimeSelector(): String = "article.item"

    override fun popularAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("abs:href"))
        anime.title = element.select("a h2").text()
        anime.thumbnail_url = element.select("a .item__image picture img").attr("data-src")
        return anime
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst(".home__slider_content div h1.slugh1")!!.text()
        anime.description = document.selectFirst(".home__slider_content .description")!!.text()
        anime.genre = document.select(".home__slider_content div:nth-child(5) > a").joinToString { it.text() }
        anime.artist = document.selectFirst(".home__slider_content div:nth-child(7) > a")?.text()
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
                setUrlWithoutDomain(jsoup.location())
            }
            episodes.add(episode)
        } else {
            val jsonStrData = jsoup.selectFirst("script:containsData(const seasonUrl =)")?.data() ?: return emptyList()
            val jsonParse = json.decodeFromString<JsonObject>(jsonStrData.substringAfter("seasonsJson = ").substringBefore(";"))
            var index = 0
            jsonParse.entries.map {
                it.value.jsonArray.reversed().map { element ->
                    index += 1
                    val jsonElement = element.jsonObject
                    val season = jsonElement["season"]!!.jsonPrimitive.content
                    val title = jsonElement["title"]!!.jsonPrimitive.content
                    val ep = jsonElement["episode"]!!.jsonPrimitive.content
                    val episode = SEpisode.create().apply {
                        episode_number = index.toFloat()
                        name = "T$season - E$ep - $title"
                        setUrlWithoutDomain("${response.request.url}/season/$season/episode/$ep")
                    }
                    episodes.add(episode)
                }
            }
        }
        return episodes.reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/api/search/$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val regIsUrl = "https?:\\/\\/(www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&//=]*)".toRegex()
        return document.select(".bg-tabs ul li").flatMap {
            val prefix = it.parent()?.parent()?.selectFirst("button")?.ownText()?.lowercase()?.getLang()
            val decode = String(Base64.decode(it.attr("data-server"), Base64.DEFAULT))

            val url = if (!regIsUrl.containsMatchIn(decode)) {
                "$baseUrl/player/${String(Base64.encode(it.attr("data-server").toByteArray(), Base64.DEFAULT))}"
            } else { decode }

            val videoUrl = if (url.contains("/player/")) {
                val script = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(window.onload)")?.data() ?: ""
                fetchUrls(script).firstOrNull() ?: ""
            } else {
                url
            }.replace("https://sblanh.com", "https://lvturbo.com")
                .replace(Regex("([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https:\\/\\/ww3.pelisplus.to.*"), "")

            serverVideoResolver(videoUrl, prefix ?: "")
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

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por genero ignora los otros filtros"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Doramas", "doramas"),
            Pair("Animes", "animes"),
            Pair("Acción", "genres/accion"),
            Pair("Action & Adventure", "genres/action-adventure"),
            Pair("Animación", "genres/animacion"),
            Pair("Aventura", "genres/aventura"),
            Pair("Bélica", "genres/belica"),
            Pair("Ciencia ficción", "genres/ciencia-ficcion"),
            Pair("Comedia", "genres/comedia"),
            Pair("Crimen", "genres/crimen"),
            Pair("Documental", "genres/documental"),
            Pair("Dorama", "genres/dorama"),
            Pair("Drama", "genres/drama"),
            Pair("Familia", "genres/familia"),
            Pair("Fantasía", "genres/fantasia"),
            Pair("Guerra", "genres/guerra"),
            Pair("Historia", "genres/historia"),
            Pair("Horror", "genres/horror"),
            Pair("Kids", "genres/kids"),
            Pair("Misterio", "genres/misterio"),
            Pair("Música", "genres/musica"),
            Pair("Musical", "genres/musical"),
            Pair("Película de TV", "genres/pelicula-de-tv"),
            Pair("Reality", "genres/reality"),
            Pair("Romance", "genres/romance"),
            Pair("Sci-Fi & Fantasy", "genres/sci-fi-fantasy"),
            Pair("Soap", "genres/soap"),
            Pair("Suspense", "genres/suspense"),
            Pair("Terror", "genres/terror"),
            Pair("War & Politics", "genres/war-politics"),
            Pair("Western", "genres/western"),
        ),
    )

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
