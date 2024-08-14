package eu.kanade.tachiyomi.animeextension.es.veranimes

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.veranimes.extractors.VidGuardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VerAnimes : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "VerAnimes"

    override val baseUrl = "https://wwv.veranimes.net"

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
            "StreamWish",
            "Voe",
            "Okru",
            "YourUpload",
            "FileLions",
            "StreamHideVid",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".ti h1")?.text()?.trim() ?: ""
            status = SAnime.UNKNOWN
            description = document.selectFirst(".r .tx p")?.text()
            genre = document.select(".gn li a").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".info figure img")?.attr("abs:data-src")
            document.select(".info .u:not(.sp) > li").map { it.text() }.map { textContent ->
                when {
                    "Estudio" in textContent -> author = textContent.substringAfter("Estudio(s):").trim()
                    "Producido" in textContent -> artist = textContent.substringAfter("Producido por:").trim()
                }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes?orden=desc&pag=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article.li figure a")
        val nextPage = document.select(".pag li a[title*=Siguiente]").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img")!!.attr("abs:data-src")
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?estado=en-emision&orden=desc&pag=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?buscar=$query&pag=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/animes?genero=${genreFilter.toUriPart()}&orden=desc&pag=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val scriptEps = document.selectFirst("script:containsData(var eps =)")?.data() ?: return emptyList()
        val slug = document.select("*[data-sl]").attr("data-sl")
        return scriptEps.substringAfter("var eps = ").substringBefore(";").trim().parseAs<List<String>>().map {
            SEpisode.create().apply {
                episode_number = it.toFloat()
                name = "Episodio $it"
                setUrlWithoutDomain("/ver/$slug-$it")
            }
        }
    }

    private fun hex2a(hex: String): String {
        return StringBuilder(hex.length / 2).apply {
            for (i in hex.indices step 2) {
                val charCode = hex.substring(i, i + 2).toInt(16)
                append(charCode.toChar())
            }
        }.toString()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val opt = document.select(".opt").attr("data-encrypt")

        val mediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        val body = "acc=opt&i=$opt".toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://wwv.veranimes.net/process")
            .post(body)
            .addHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .addHeader("referer", document.location())
            .addHeader("x-requested-with", "XMLHttpRequest")
            .build()

        val serversDocument = client.newCall(request).execute().asJsoup()

        return serversDocument.select("li").parallelCatchingFlatMapBlocking {
            val link = hex2a(it.attr("encrypt"))
            serverVideoResolver(link)
        }
    }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> OkruExtractor(client).videosFromUrl(url)
            embedUrl.contains("filelions") || embedUrl.contains("lion") -> StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") -> {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            }
            embedUrl.contains("vidhide") || embedUrl.contains("streamhide") ||
                embedUrl.contains("guccihide") || embedUrl.contains("streamvid") -> StreamHideVidExtractor(client).videosFromUrl(url)
            embedUrl.contains("voe") -> VoeExtractor(client).videosFromUrl(url)
            embedUrl.contains("yourupload") || embedUrl.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            embedUrl.contains("vidguard") || embedUrl.contains("vgfplay") || embedUrl.contains("listeamed") -> VidGuardExtractor(client).videosFromUrl(url)
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

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Acción", "accion"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventuras", "aventuras"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Demencia", "demencia"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Harem", "harem"),
            Pair("Historico", "historico"),
            Pair("Infantil", "infantil"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
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
