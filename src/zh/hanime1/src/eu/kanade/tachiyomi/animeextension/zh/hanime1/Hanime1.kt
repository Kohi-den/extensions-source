package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

enum class FilterUpdateState {
    NONE,
    UPDATING,
    COMPLETED,
    FAILED,
}

class Hanime1 : AnimeHttpSource(), ConfigurableAnimeSource {
    override val baseUrl: String
        get() = "https://hanime1.me"
    override val lang: String
        get() = "zh"
    override val name: String
        get() = "Hanime1.me"
    override val supportsLatest: Boolean
        get() = true

    override val client =
        network.client.newBuilder().addInterceptor(::checkFiltersInterceptor).build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val json by injectLazy<Json>()
    private var filterUpdateState = FilterUpdateState.NONE
    private val uploadDateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            genre = doc.select(".single-video-tag").not("[data-toggle]").eachText().joinToString()
            author = doc.select("#video-artist-name").text()
            doc.select("script[type=application/ld+json]").first()?.data()?.let {
                val info = json.decodeFromString<JsonElement>(it).jsonObject
                title = info["name"]!!.jsonPrimitive.content
                description = info["description"]!!.jsonPrimitive.content
                thumbnail_url = info["thumbnailUrl"]?.jsonArray?.get(0)?.jsonPrimitive?.content
            }
            val type = doc.select("a#video-artist-name + a").text().trim()
            if (type == "裏番" || type == "泡麵番") {
                // Use the series cover image for bangumi entries instead of the episode image.
                runBlocking {
                    try {
                        val animesPage =
                            getSearchAnime(
                                1,
                                title,
                                AnimeFilterList(GenreFilter(arrayOf("", type)).apply { state = 1 }),
                            )
                        thumbnail_url = animesPage.animes.first().thumbnail_url
                    } catch (e: Exception) {
                        Log.e(name, "Failed to get bangumi cover image")
                    }
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("#playlist-scroll").first()!!.select(">div")
        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (nodes.size - index).toFloat()
                name = element.select("div.card-mobile-title").text()
                if (href == response.request.url.toString()) {
                    // current video
                    jsoup.select("script[type=application/ld+json]").first()?.data()?.let {
                        val info = json.decodeFromString<JsonElement>(it).jsonObject
                        info["uploadDate"]?.jsonPrimitive?.content?.let { date ->
                            date_upload =
                                runCatching { uploadDateFormat.parse(date)?.time }.getOrNull() ?: 0L
                        }
                    }
                }
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sourceList = doc.select("video source")
        val preferQuality = preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)
        return sourceList.map {
            val quality = it.attr("size")
            val url = it.attr("src")
            Video(url, "${quality}P", videoUrl = url)
        }.filterNot { it.videoUrl?.startsWith("blob") == true }
            .sortedByDescending { preferQuality == it.quality }
            .ifEmpty {
                // Try to find the source from the script content.
                val videoUrl = doc.select("script[type=application/ld+json]").first()!!.data().let {
                    val info = json.decodeFromString<JsonElement>(it).jsonObject
                    info["contentUrl"]!!.jsonPrimitive.content
                }
                listOf(Video(videoUrl, "Raw", videoUrl = videoUrl))
            }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int) =
        searchAnimeRequest(page, "", AnimeFilterList(HotFilter))

    private fun String.appendInvisibleChar(): String {
        // The search result title will be same as one episode name of anime.
        // Adding extra char makes them has different title
        return "${this}\u200B"
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("div.search-doujin-videos.hidden-xs:not(:has(a[target=_blank]))")
        val list = if (nodes.isNotEmpty()) {
            nodes.map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select("a[class=overlay]").attr("href"))
                    thumbnail_url = it.select("img + img").attr("src")
                    title = it.select("div.card-mobile-title").text().appendInvisibleChar()
                    author = it.select(".card-mobile-user").text()
                }
            }
        } else {
            jsoup.select("a:not([target]) > .search-videos").map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.parent()!!.attr("href"))
                    thumbnail_url = it.select("img").attr("src")
                    title = it.select(".home-rows-videos-title").text().appendInvisibleChar()
                }
            }
        }
        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        if (query.isNotEmpty()) {
            searchUrl.addQueryParameter("query", query)
        }
        filters.list.flatMap {
            when (it) {
                is TagsFilter -> {
                    it.state.flatMap { inner ->
                        if (inner is CategoryFilter) {
                            inner.state
                        } else {
                            listOf(inner)
                        }
                    }
                }

                is AnimeFilter.Group<*> -> it.state
                else -> listOf(it)
            }
        }.forEach {
            when (it) {
                is QueryFilter -> {
                    if (it.selected.isNotEmpty()) {
                        searchUrl.addQueryParameter(it.key, it.selected)
                    }
                }

                is BroadMatchFilter -> {
                    if (it.state) {
                        searchUrl.addQueryParameter(it.key, "on")
                    }
                }

                is TagFilter -> {
                    if (it.state) {
                        searchUrl.addQueryParameter(it.key, it.name)
                    }
                }

                else -> {}
            }
        }
        if (page > 1) {
            searchUrl.addQueryParameter("page", "$page")
        }
        return GET(searchUrl.build())
    }

    private fun checkFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterUpdateState == FilterUpdateState.NONE) {
            updateFilters()
        }
        return chain.proceed(chain.request())
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun updateFilters() {
        filterUpdateState = FilterUpdateState.UPDATING
        val exceptionHandler =
            CoroutineExceptionHandler { _, _ -> filterUpdateState = FilterUpdateState.FAILED }
        GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
            val jsoup = client.newCall(GET("$baseUrl/search")).awaitSuccess().asJsoup()
            val genreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
            val sortList =
                jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
            val yearList = jsoup.select("select#year option").eachAttr("value")
                .map { it.ifEmpty { "全部年份" } }
            val monthList = jsoup.select("select#month option").eachAttr("value")
                .map { it.ifEmpty { "全部月份" } }
            val categoryDict = mutableMapOf<String, MutableList<String>>()
            var currentKey = ""
            jsoup.select("div#tags div.modal-body").first()?.children()?.forEach {
                if (it.tagName() == "h5") {
                    currentKey = it.text()
                }
                if (it.tagName() == "label") {
                    if (currentKey in categoryDict) {
                        categoryDict[currentKey]
                    } else {
                        categoryDict[currentKey] = mutableListOf()
                        categoryDict[currentKey]
                    }!!.add(it.select("input[name]").attr("value"))
                }
            }
            preferences.edit().putString(PREF_KEY_GENRE_LIST, genreList.joinToString())
                .putString(PREF_KEY_SORT_LIST, sortList.joinToString())
                .putString(PREF_KEY_YEAR_LIST, yearList.joinToString())
                .putString(PREF_KEY_MONTH_LIST, monthList.joinToString())
                .putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(categoryDict)).apply()
            filterUpdateState = FilterUpdateState.COMPLETED
        }
    }

    private fun <T : QueryFilter> createFilter(prefKey: String, block: (Array<String>) -> T): T {
        val savedOptions = preferences.getString(prefKey, "")
        if (savedOptions.isNullOrEmpty()) {
            return block(emptyArray())
        }
        return block(savedOptions.split(", ").toTypedArray())
    }

    private fun createCategoryFilters(): List<AnimeFilter<out Any>> {
        val result = mutableListOf<AnimeFilter<out Any>>(
            BroadMatchFilter(),
        )
        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, "")
        if (savedCategories.isNullOrEmpty()) {
            return result
        }
        json.decodeFromString<Map<String, List<String>>>(savedCategories).forEach {
            result.add(CategoryFilter(it.key, it.value.map { value -> TagFilter("tags[]", value) }))
        }
        return result
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            createFilter(PREF_KEY_GENRE_LIST) { GenreFilter(it) },
            createFilter(PREF_KEY_SORT_LIST) { SortFilter(it) },
            DateFilter(
                createFilter(PREF_KEY_YEAR_LIST) { YearFilter(it) },
                createFilter(PREF_KEY_MONTH_LIST) { MonthFilter(it) },
            ),
            TagsFilter(createCategoryFilters()),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_VIDEO_QUALITY
                    title = "設置首選畫質"
                    entries = arrayOf("1080P", "720P", "480P")
                    entryValues = entries
                    setDefaultValue(DEFAULT_QUALITY)
                    summary =
                        "當前選擇：${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
                    setOnPreferenceChangeListener { _, newValue ->
                        summary = "當前選擇：${newValue as String}"
                        true
                    }
                },
            )
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_LANG
                    title = "設置首選語言"
                    summary = "該設置僅影響影片字幕"
                    entries = arrayOf("繁體中文", "簡體中文")
                    entryValues = arrayOf("zh-CHT", "zh-CHS")
                    setOnPreferenceChangeListener { _, newValue ->
                        val baseHttpUrl = baseUrl.toHttpUrl()
                        client.cookieJar.saveFromResponse(
                            baseHttpUrl,
                            listOf(
                                Cookie.parse(
                                    baseHttpUrl,
                                    "user_lang=${newValue as String}",
                                )!!,
                            ),
                        )
                        true
                    }
                },
            )
        }
    }

    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val PREF_KEY_LANG = "PREF_KEY_LANG"

        const val PREF_KEY_GENRE_LIST = "PREF_KEY_GENRE_LIST"
        const val PREF_KEY_SORT_LIST = "PREF_KEY_SORT_LIST"
        const val PREF_KEY_YEAR_LIST = "PREF_KEY_YEAR_LIST"
        const val PREF_KEY_MONTH_LIST = "PREF_KEY_MONTH_LIST"
        const val PREF_KEY_CATEGORY_LIST = "PREF_KEY_CATEGORY_LIST"

        const val DEFAULT_QUALITY = "1080P"
    }
}
