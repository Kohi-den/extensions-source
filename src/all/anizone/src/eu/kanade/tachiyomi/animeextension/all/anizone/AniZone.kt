package eu.kanade.tachiyomi.animeextension.all.anizone

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup.parseBodyFragment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class AniZone : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AniZone"

    override val baseUrl = "https://anizone.to"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private var token: String = ""

    private val snapShots: MutableMap<String, String> = mutableMapOf(
        ANIME_SNAPSHOT_KEY to "",
        EPISODE_SNAPSHOT_KEY to "",
        VIDEO_SNAPSHOT_KEY to "",
    )

    private var loadCount: Int = 0

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return if (page == 1) {
            loadCount = 0
            snapShots[ANIME_SNAPSHOT_KEY] = ""

            val updates = buildJsonObject {
                put("sort", "title-asc")
            }
            val calls = buildJsonArray { }

            createLivewireReq(ANIME_SNAPSHOT_KEY, updates, calls)
        } else {
            val updates = buildJsonObject { }
            val calls = buildJsonArray {
                addJsonObject {
                    put("path", "")
                    put("method", "loadMore")
                    putJsonArray("params") { }
                }
            }

            createLivewireReq(ANIME_SNAPSHOT_KEY, updates, calls)
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val html = response.parseAs<LivewireDto>().getHtml(ANIME_SNAPSHOT_KEY)

        val animeList = html.select("div.grid > div").drop(loadCount)
            .map(::animeFromElement)
        val hasNextPage = html.selectFirst("div[x-intersect~=loadMore]") != null

        loadCount += animeList.size

        return AnimesPage(animeList, hasNextPage)
    }

    private fun animeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            thumbnail_url = element.selectFirst("img")!!.attr("src")
            with(element.selectFirst("a.inline")!!) {
                setUrlWithoutDomain(attr("href"))
                title = text()
            }
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1) {
            loadCount = 0
            snapShots[ANIME_SNAPSHOT_KEY] = ""

            val updates = buildJsonObject {
                put("sort", "release-desc")
            }
            val calls = buildJsonArray { }

            createLivewireReq(ANIME_SNAPSHOT_KEY, updates, calls)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sortFilter = filters.filterIsInstance<SortFilter>().first()

        return if (page == 1) {
            loadCount = 0
            snapShots[ANIME_SNAPSHOT_KEY] = ""

            val updates = buildJsonObject {
                if (query.isNotEmpty()) {
                    put("search", query)
                }
                put("sort", sortFilter.toUriPart())
            }
            val calls = buildJsonArray { }

            createLivewireReq(ANIME_SNAPSHOT_KEY, updates, calls)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(SortFilter())
    }

    private class SortFilter : UriPartFilter(
        "Sort",
        arrayOf(
            Pair("A-Z", "title-asc"),
            Pair("Z-A", "title-desc"),
            Pair("Earliest Release", "release-asc"),
            Pair("Latest Release", "release-desc"),
            Pair("First Added", "added-asc"),
            Pair("Last Added", "added-desc"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val infoDiv = document.select("div.flex.items-start > div")[1]

        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("div.flex.items-start img")!!.attr("abs:img")

            with(infoDiv) {
                title = selectFirst("h1")!!.text()
                status = select("span.flex")[1].parseStatus()
                description = selectFirst("div:has(>h3:contains(Synopsis)) > div")?.html()
                    ?.replace("<br>", "\n")
                    ?.replace(MULTILINE_REGEX, "\n\n")
                genre = select("div > a").joinToString { it.text() }
            }
        }
    }

    private fun Element.parseStatus(): Int = when (this.text().lowercase()) {
        "completed" -> SAnime.COMPLETED
        "ongoing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    private fun getPredefinedSnapshots(slug: String): String {
        return when (slug) {
            "/anime/uyyyn4kf" -> """{"data":{"anime":[null,{"class":"anime","key":68,"s":"mdl"}],"title":null,"search":"","listSize":1104,"sort":"release-asc","sortOptions":[{"release-asc":"First Aired","release-desc":"Last Aired"},{"s":"arr"}],"view":"list","paginators":[{"page":1},{"s":"arr"}]},"memo":{"id":"GD1OiEMOJq6UQDQt1OBt","name":"pages.anime-detail","path":"anime\/uyyyn4kf","method":"GET","children":[],"scripts":[],"assets":[],"errors":[],"locale":"en"},"checksum":"5800932dd82e4862f34f6fd72d8098243b32643e8accb8da6a6a39cd0ee86acd"}"""
            else -> ""
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        snapShots[EPISODE_SNAPSHOT_KEY] = getPredefinedSnapshots(anime.url)

        val updates = buildJsonObject {
            put("sort", "release-desc")
        }
        val calls = buildJsonArray { }

        return createLivewireReq(EPISODE_SNAPSHOT_KEY, updates, calls, anime.url)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.parseAs<LivewireDto>().getHtml(EPISODE_SNAPSHOT_KEY)
        val episodeList = document.select(episodeSelector)
            .map(::episodeFromElement)
            .toMutableList()
        loadCount = episodeList.size

        var hasMore = document.selectFirst("div[x-intersect~=loadMore]") != null

        while (hasMore) {
            val updates = buildJsonObject { }
            val calls = buildJsonArray {
                addJsonObject {
                    put("path", "")
                    put("method", "loadMore")
                    putJsonArray("params") { }
                }
            }

            val resp = client.newCall(
                createLivewireReq(EPISODE_SNAPSHOT_KEY, updates, calls),
            ).execute().parseAs<LivewireDto>().getHtml(EPISODE_SNAPSHOT_KEY)

            val episodes = resp.select(episodeSelector)
                .drop(loadCount)
                .map(::episodeFromElement)

            episodeList.addAll(episodes)
            loadCount += episodes.size

            hasMore = resp.selectFirst("div[x-intersect~=loadMore]") != null
        }

        return episodeList
    }

    private val episodeSelector = "ul > li"

    private fun episodeFromElement(element: Element): SEpisode {
        val url = element.selectFirst("a[href]")!!.attr("abs:href")

        return SEpisode.create().apply {
            setUrlWithoutDomain(url)
            name = element.selectFirst("h3")!!.text()
            date_upload = element.select("div.flex-row > span").getOrNull(1)
                ?.text()
                ?.let { parseDate(it) }
                ?: 0L
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    private val playlistUtils: PlaylistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverSelects = document.select("button[wire:click]")
            .filter { video ->
                video.attr("wire:click").contains("setVideo")
            }

        val subtitles = document.select("track[kind=subtitles]").map {
            Track(it.attr("src"), it.attr("label"))
        }

        val m3u8List = mutableListOf(
            VideoData(
                url = document.selectFirst("media-player")!!.attr("src"),
                name = serverSelects.first().text(),
                subtitles = subtitles,
            ),
        )
        snapShots[VIDEO_SNAPSHOT_KEY] = document.getSnapshot()

        serverSelects.drop(1).forEach { video ->
            val regex = "setVideo\\('(\\d+)'\\)".toRegex()
            val matchResult = regex.find(video.attr("wire:click"))
            val videoId = if (matchResult != null && matchResult.groupValues.size == 1) {
                matchResult.groupValues[1]
            } else {
                "0"
            }
            val updates = buildJsonObject { }
            val calls = buildJsonArray {
                add(
                    buildJsonObject {
                        put("path", "")
                        put("method", "setVideo")
                        putJsonArray("params") {
                            add(videoId.toInt())
                        }
                    },
                )
            }

            val doc = client.newCall(
                createLivewireReq(VIDEO_SNAPSHOT_KEY, updates, calls, response.request.url.encodedPath),
            ).execute().parseAs<LivewireDto>().getHtml(VIDEO_SNAPSHOT_KEY)

            val subs = doc.select("track[kind=subtitles]").map {
                Track(it.attr("src"), it.attr("label"))
            }

            doc.selectFirst("media-player")?.attr("src")?.also {
                m3u8List.add(
                    VideoData(
                        url = it,
                        name = video.text(),
                        subtitles = subs,
                    ),
                )
            }
        }

        val serverList = if (preferences.dub) {
            m3u8List
        } else {
            m3u8List.reversed()
        }

        return serverList.flatMap {
            playlistUtils.extractFromHls(
                playlistUrl = it.url,
                referer = "$baseUrl/",
                videoNameGen = { q -> "${it.name} - $q" },
                subtitleList = it.subtitles,
            )
        }
    }

    data class VideoData(
        val url: String,
        val name: String,
        val subtitles: List<Track>,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================= Utilities ==============================

    private fun LivewireDto.getHtml(mapKey: String): Document {
        val data = this.components.first()

        snapShots[mapKey] = data.snapshot.replace("\\\"", "\"")

        return parseBodyFragment(
            data.effects.html.replace("\\\"", "\"")
                .replace("\\n", ""),
        )
    }

    private fun Document.getSnapshot(): String {
        return this.selectFirst("main > div[wire:snapshot]")!!
            .attr("wire:snapshot")
            .replace("&quot;", "\"")
    }

    private fun createLivewireReq(
        mapKey: String,
        updates: JsonObject,
        calls: JsonArray,
        initialSlug: String = "/anime",
    ): Request {
        val firstSnapshot = snapShots[mapKey] ?: ""

        if (firstSnapshot.isEmpty() || token.isEmpty()) {
            val doc = client.newCall(GET(baseUrl + initialSlug, headers)).execute()
                .asJsoup()

            snapShots[mapKey] = doc.getSnapshot()

            token = doc.selectFirst("script[data-csrf]")
                ?.attr("data-csrf")
                ?.takeIf(String::isNotEmpty)
                ?: throw Exception("Failed to get csrf token")
        }

        val headers = headersBuilder().apply {
            add("X-Livewire", "")
        }.build()

        val body = buildJsonObject {
            put("_token", token)
            putJsonArray("components") {
                addJsonObject {
                    put("calls", calls)
                    put("snapshot", snapShots[mapKey])
                    put("updates", updates)
                }
            }
        }.toRequestBody()

        return POST("$baseUrl/livewire/update", headers, body)
    }

    private fun JsonObject.toRequestBody(): RequestBody {
        return json.encodeToString(this).toRequestBody(
            "application/json".toMediaType(),
        )
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            DATE_FORMAT.parse(dateStr)!!.time
        } catch (_: ParseException) {
            0L
        }
    }

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.dub
        get() = getBoolean(PREF_DUB_KEY, PREF_DUB_DEFAULT)

    companion object {
        private val MULTILINE_REGEX = Regex("""\n{2,}""")
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

        private const val ANIME_SNAPSHOT_KEY = "anime_snapshot_key"
        private const val EPISODE_SNAPSHOT_KEY = "episode_snapshot_key"
        private const val VIDEO_SNAPSHOT_KEY = "video_snapshot_key"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_ENTRY_VALUES = arrayOf("1080", "720", "480", "360")

        private const val PREF_DUB_KEY = "attempt_dub"
        private const val PREF_DUB_TITLE = "Attempt to prefer dub"
        private const val PREF_DUB_DEFAULT = false
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, new ->
                val index = findIndexOfValue(new as String)
                preferences.edit().putString(key, entryValues[index] as String).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DUB_KEY
            title = PREF_DUB_TITLE
            setDefaultValue(PREF_DUB_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)
    }
}
