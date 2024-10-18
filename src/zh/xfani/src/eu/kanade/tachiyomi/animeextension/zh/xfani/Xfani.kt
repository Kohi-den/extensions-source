package eu.kanade.tachiyomi.animeextension.zh.xfani

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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Xfani : AnimeHttpSource(), ConfigurableAnimeSource {
    override val baseUrl: String
        get() = "https://dick.xfani.com"
    override val lang: String
        get() = "zh"
    override val name: String
        get() = "稀饭动漫"
    override val supportsLatest: Boolean
        get() = true

    private val json by injectLazy<Json>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val numberRegex = Regex("\\d+")

    private val selectedVideoSource
        get() = preferences.getString(PREF_KEY_VIDEO_SOURCE, DEFAULT_VIDEO_SOURCE)!!.toInt()

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val result = jsoup.select("ul.anthology-list-play.size")
        val episodeList = if (result.size > selectedVideoSource) {
            result[selectedVideoSource]
        } else {
            result[0]
        }.select("li > a")
        return episodeList.map {
            SEpisode.create().apply {
                name = it.text()
                url = it.attr("href")
                episode_number = numberRegex.find(name)?.value?.toFloat() ?: -1F
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val script = response.asJsoup().select("script:containsData(player_aaaa)").first()!!.data()
        val info = script.substringAfter("player_aaaa=").let { json.parseToJsonElement(it) }
        val url = info.jsonObject["url"]!!.jsonPrimitive.content
        return listOf(Video(url, "SingleFile", videoUrl = url))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return vodListToAnimePageList(response)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage {
        return vodListToAnimePageList(response)
    }

    override fun popularAnimeRequest(page: Int): Request =
        searchAnimeRequest(page, "", AnimeFilterList(SortFilter().apply { state = 1 }))

    private fun vodListToAnimePageList(response: Response): AnimesPage {
        val vodResponse = json.decodeFromString<VodResponse>(response.body.string())
        val animeList = vodResponse.list.map {
            SAnime.create().apply {
                url = "/bangumi/${it.vodId}.html"
                thumbnail_url = it.vodPicThumb.ifEmpty { it.vodPic }
                title = it.vodName
                author = it.vodActor
                description = it.vodBlurb
                genre = it.vodClass
            }
        }
        return AnimesPage(
            animeList,
            animeList.isNotEmpty() && vodResponse.page * vodResponse.limit < vodResponse.total,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val items = jsoup.select("div.public-list-box.search-box.flex.rel")
        val animeList = items.map { item ->
            SAnime.create().apply {
                title = item.select(".thumb-txt").text()
                url = item.select("div.left.public-list-bj a.public-list-exp").attr("href")
                thumbnail_url =
                    item.select("div.left.public-list-bj img[data-src]").attr("data-src")
                author = item.select("div.thumb-actor").text().removeSuffix("/")
                artist = item.select("div.thumb-director").text().removeSuffix("/")
                description = item.select(".thumb-blurb").text()
                genre = item.select("div.thumb-else").text()
                val statusString = item.select("div.left.public-list-bj .public-list-prb").text()
                status = STATUS_STR_MAPPING.getOrElse(statusString) { SAnime.ONGOING }
            }
        }
        val tip = jsoup.select("div.pages div.page-tip").text()
        return AnimesPage(animeList, tip.isNotEmpty() && hasMorePage(tip))
    }

    private fun hasMorePage(tip: String): Boolean {
        val pageIndicator = tip.substringAfter("当前").substringBefore("页")
        val numbers = pageIndicator.split("/")
        return numbers.size == 2 && numbers[0] != numbers[1]
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            AnimeFilter.Header("设置筛选后搜索关键字搜索会失效"),
            TypeFilter(),
            ClassFilter(),
            VersionFilter(),
            LetterFilter(),
            SortFilter(),
        )
    }

    private fun doSearch(page: Int, query: String): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        if (page <= 1) {
            url.addPathSegment("search.html")
                .addQueryParameter("wd", query)
        } else {
            url.addPathSegments("search/wd/")
                .addPathSegment(query)
                .addPathSegments("page/$page.html")
        }
        return GET(url.build())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return doSearch(page, query)
        }
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("index.php/api/vod")
            .build()
        val time = System.currentTimeMillis() / 1000
        val formBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("page", "$page")
            .addFormDataPart("time", "$time")
            .addFormDataPart("key", generateKey(time))
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> formBody.addFormDataPart("type", filter.selected)
                is ClassFilter -> formBody.addFormDataPart("class", filter.selected)
                is VersionFilter -> formBody.addFormDataPart("version", filter.selected)
                is LetterFilter -> formBody.addFormDataPart("letter", filter.selected)
                is SortFilter -> formBody.addFormDataPart("by", filter.selected)
                else -> {}
            }
        }
        if (filters.filterIsInstance<TypeFilter>().isEmpty()) {
            formBody.addFormDataPart("type", "1")
        }
        return POST(url.toString(), body = formBody.build())
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_KEY_VIDEO_SOURCE
                title = "请设置首选视频源线路"
                entries = arrayOf("主线-1", "主线-2", "备用-1")
                entryValues = arrayOf("0", "1", "2")
                setDefaultValue(DEFAULT_VIDEO_SOURCE)
                summary = "当前选择：${entries[selectedVideoSource]}"
                setOnPreferenceChangeListener { _, newValue ->
                    summary = "当前选择 ${entries[(newValue as String).toInt()]}"
                    true
                }
            },
        )
    }

    companion object {
        const val PREF_KEY_VIDEO_SOURCE = "PREF_KEY_VIDEO_SOURCE"

        const val DEFAULT_VIDEO_SOURCE = "0"

        val STATUS_STR_MAPPING = mapOf(
            "已完结" to SAnime.COMPLETED,
        )
    }
}
