package eu.kanade.tachiyomi.animeextension.de.animebase

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeBase : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime-Base"

    override val baseUrl = "https://anime-base.net"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    val limit = 24

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/api/top-on-animebase?offset=${limit * (page - 1)}&limit=$limit", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = Json.parseToJsonElement(response.body.string())
        val animes = mutableListOf<SAnime>()
        for (anime in json.jsonObject["data"]?.jsonArray!!.map { it.jsonObject }) {
            try {
                animes += SAnime.create().apply {
                    val cat = anime["category"]!!.jsonPrimitive.content
                    val slug = anime["nameSlug"]!!.jsonPrimitive.content
                    setUrlWithoutDomain("/$cat/$slug")
                    thumbnail_url = anime["image"]!!.jsonPrimitive.content
                    title = anime["name"]!!.jsonPrimitive.content
                }
            } catch (e: Exception) {
                Log.e("animebase", "Error parsing anime from JSON: $anime", e)
            }
        }
        val hasNext = json.jsonObject["meta"]!!.jsonObject["hasMore"]!!.jsonPrimitive.content.toBoolean()
        return AnimesPage(animes, hasNext)
    }

    override fun popularAnimeSelector() = "div.grid > div > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href").replace("/link/", "/anime/"))
        thumbnail_url = element.selectFirst("div img")?.absUrl("src")
        title = element.selectFirst("div > div.font-medium, div h3")!!.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/updates", headers)

    override fun latestUpdatesSelector() = "div.hidden > div > a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun getFilterList() = AnimeBaseFilters.FILTER_LIST

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/searching", headers)).execute()
            .asJsoup()
            .selectFirst("form > input[name=_token]")!!
            .attr("value")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeBaseFilters.getSearchParameters(filters)

        return when {
            params.list.isEmpty() -> {
                val body = FormBody.Builder()
                    .add("_token", searchToken)
                    .add("_token", searchToken)
                    .add("name_serie", query)
                    .add("jahr", params.year.toIntOrNull()?.toString() ?: "")
                    .apply {
                        params.languages.forEach { add("dubsub[]", it) }
                        params.genres.forEach { add("genre[]", it) }
                    }.build()
                POST("$baseUrl/searching", headers, body)
            }

            else -> {
                GET("$baseUrl/${params.list}${params.letter}?page=$page", headers)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()

        return when {
            doc.location().contains("/searching") -> {
                val animes = doc.select(searchAnimeSelector()).map(::searchAnimeFromElement)
                AnimesPage(animes, false)
            }
            else -> {
                Log.w("animebase", "unpredicted state")
                AnimesPage(emptyList(), false)
            }
        }
    }

    override fun searchAnimeSelector() = "div.col-lg-9.col-md-8 div.box-body > a"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = "ul.pagination li > a[rel=next]"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        // could also be done with data-page.value.props.serie...
        setUrlWithoutDomain(document.location())

        val boxBody = document.selectFirst(".border-zinc-700.border.p-6.shadow-lg.rounded-xl.to-zinc-950.from-zinc-800.bg-gradient-to-br")!!
        title = boxBody.selectFirst("h2")!!.text()
        thumbnail_url = boxBody.selectFirst("img")!!.absUrl("src")

        val statusBadge = boxBody.selectFirst("div > span.inline-block")
        status = parseStatus(statusBadge?.text())

        description = boxBody.selectFirst("div > div > div > div.text-zinc-300")?.text()
    }

    private fun parseStatus(status: String?) = when (status.orEmpty()) {
        "Laufend" -> SAnime.ONGOING
        "Abgeschlossen" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun Element.getInfo(selector: String) =
        selectFirst("strong:contains($selector) + p")?.text()?.trim()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val data = doc.selectFirst("div#app")?.attr("data-page") ?: return emptyList()
        val serie = Json.parseToJsonElement(data).jsonObject["props"]?.jsonObject?.get("serie") ?: return emptyList()
        val episodes = serie.jsonObject["episodes"]?.jsonArray ?: return emptyList()
        val episodeList = mutableListOf<SEpisode>()
        for (episode in episodes) {
            episodeList += SEpisode.create().apply {
                name = episode.jsonObject["name"]?.jsonPrimitive?.content ?: "Unknown Episode"
                scanlator = when (episode.jsonObject["dubsub"]?.jsonPrimitive?.intOrNull) {
                    0 -> "Subbed"
                    1 -> "Dubbed"
                    else -> "Unknown"
                }

                episode_number = episode.jsonObject["episode"]?.jsonPrimitive?.intOrNull?.toFloat() ?: 0.0f

                setUrlWithoutDomain(response.request.url.encodedPath + "?id=" + episode.jsonObject["id"]?.jsonPrimitive?.content)
            }
        }

        return episodeList.sortedByDescending { it.episode_number }
    }

    override fun episodeListSelector() = "div.tab-content > div > div.panel"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        Log.e("animebase", "This should not be called")
        val epname = element.selectFirst("h3")?.text() ?: "Episode 1"
        val language = when (element.selectFirst("button")?.attr("data-dubbed").orEmpty()) {
            "0" -> "Subbed"
            else -> "Dubbed"
        }

        name = epname
        scanlator = language
        episode_number = epname.substringBefore(":").substringAfter(" ").toFloatOrNull() ?: 0F
        val selectorClass = element.classNames().first { it.startsWith("episode-div") }
        setUrlWithoutDomain(element.baseUri() + "?selector=div.panel.$selectorClass")
        Log.d("animebase", url)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        Log.d("animebase", "videoListParse called")
        val data = doc.selectFirst("div#app")?.attr("data-page") ?: return emptyList()
        val serie = Json.parseToJsonElement(data).jsonObject["props"]?.jsonObject?.get("serie") ?: return emptyList()
        val episodes = serie.jsonObject["episodes"]?.jsonArray ?: return emptyList()
        Log.d("animebase", "searching for ID")
        val wantedId = response.request.url.queryParameter("id") ?: return emptyList()
        val episode = episodes.find { it.jsonObject["id"]?.jsonPrimitive?.content == wantedId } ?: return emptyList()
        Log.d("animebase", "found ID: $wantedId")
        val videos = mutableListOf<Video>()
        val links = episode.jsonObject.entries.filter { it.key.startsWith("link") }.map { it.value }
        for (link in links) {
            val url = link.jsonPrimitive.content
            when (Uri.parse(url).host) {
                "lulustream.com" -> {
                    videos += LuluExtractor(client, headers).videosFromUrl(url, "")
                }
                "voe.sx" -> {
                    videos += VoeExtractor(client, headers).videosFromUrl(url, "")
                }
                "byse.sx" -> {
                    videos += UpstreamExtractor(client).videosFromUrl(url, "")
                }
                null -> continue
                else -> {
                    Log.w(
                        "animebase",
                        "Unknown host for video link: ${Uri.parse(link.jsonPrimitive.content).host}",
                    )
                }
            }
        }
        return videos
//        val doc = response.asJsoup()
//        val selector = response.request.url.queryParameter("selector")
//            ?: return emptyList()
//
//        return doc.select("$selector div.panel-body > button").toList()
//            .filter { it.text() in hosterSettings.keys }
//            .parallelCatchingFlatMapBlocking {
//                val language = when (it.attr("data-dubbed")) {
//                    "0" -> "SUB"
//                    else -> "DUB"
//                }
//
//                getVideosFromHoster(it.text(), it.attr("data-streamlink"))
//                    .map { video ->
//                        Video(
//                            video.url,
//                            "$language ${video.quality}",
//                            video.videoUrl,
//                            video.headers,
//                            video.subtitleTracks,
//                            video.audioTracks,
//                        )
//                    }
//            }
    }

    override fun List<Video>.sort(): List<Video> {
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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

    // ============================= Utilities ==============================
    companion object {
        private const val PREF_LANG_KEY = "preferred_sub"
        private const val PREF_LANG_TITLE = "Standardmäßig Sub oder Dub?"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("Sub", "Dub")
        private val PREF_LANG_VALUES = arrayOf("SUB", "DUB")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
