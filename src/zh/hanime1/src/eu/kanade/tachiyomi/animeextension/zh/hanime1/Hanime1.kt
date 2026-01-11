package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)

        return SAnime.create().apply {
            genre = doc.select(".single-video-tag").not("[data-toggle]").eachText().let { tags ->
                if (useEnglish) {
                    tags.map { chineseTag ->
                        Tags.getTranslatedTag(chineseTag) ?: chineseTag
                    }.joinToString()
                } else {
                    tags.joinToString()
                }
            }

            author = doc.select("#video-artist-name").text()

            doc.select("script[type=application/ld+json]").first()?.data()?.let {
                try {
                    val info = json.decodeFromString<JsonElement>(it).jsonObject
                    val originalTitle = info["name"]!!.jsonPrimitive.content
                    description = info["description"]!!.jsonPrimitive.content
                    thumbnail_url = info["thumbnailUrl"]?.jsonArray?.get(0)?.jsonPrimitive?.content

                    val duration = doc.select(".video-duration, .duration")
                        .firstOrNull()?.text()?.trim()
                    title = if (!duration.isNullOrBlank()) {
                        "$originalTitle [$duration]"
                    } else {
                        originalTitle
                    }
                } catch (e: Exception) {
                    Log.e(name, "Failed to parse JSON-LD: ${e.message}")
                }
            }

            if (title.isNullOrEmpty()) {
                val pageTitle = doc.select("h1, .title").firstOrNull()?.text() ?: ""
                val duration = doc.select(".video-duration, .duration")
                    .firstOrNull()?.text()?.trim()
                title = if (!duration.isNullOrBlank()) {
                    "$pageTitle [$duration]"
                } else {
                    pageTitle
                }
            }

            if (thumbnail_url.isNullOrEmpty()) {
                thumbnail_url = doc.select("meta[property=og:image]").attr("content").takeIf { it.isNotBlank() }
                    ?: doc.select(".single-video-thumbnail img").attr("src").takeIf { it.isNotBlank() }
                    ?: doc.select("img[src*=/thumbnail/]").attr("src").takeIf { it.isNotBlank() }
                    ?: ""
            }

            val type = doc.select("a#video-artist-name + a").text().trim()
            if (type == "裏番" || type == "泡麵番") {
                runBlocking {
                    try {
                        val animesPage = getSearchAnime(
                            1,
                            title.replace(" \\[.*\\]".toRegex(), ""),
                            AnimeFilterList(GenreFilter(arrayOf("", type)).apply { state = 1 }),
                        )
                        thumbnail_url = animesPage.animes.firstOrNull()?.thumbnail_url ?: thumbnail_url
                    } catch (e: Exception) {
                        Log.e(name, "Failed to get bangumi cover image: ${e.message}")
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

                val episodeTitle = element.select("div.card-mobile-title").text()
                val episodeDuration = element.select(".card-mobile-duration").firstOrNull {
                    it.text().contains(":")
                }?.text()?.trim()
                val episodeViews = element.select(".card-mobile-duration").firstOrNull {
                    it.text().contains("次")
                }?.text()?.trim()

                name = buildString {
                    append(episodeTitle.takeIf { it.isNotBlank() } ?: "Episode ${nodes.size - index}")
                    if (!episodeDuration.isNullOrBlank()) append(" [$episodeDuration]")
                    if (!episodeViews.isNullOrBlank()) append(" | $episodeViews")
                }

                if (href == response.request.url.toString()) {
                    jsoup.select("script[type=application/ld+json]").first()?.data()?.let {
                        try {
                            val info = json.decodeFromString<JsonElement>(it).jsonObject
                            info["uploadDate"]?.jsonPrimitive?.content?.let { date ->
                                date_upload = runCatching {
                                    uploadDateFormat.parse(date)?.time
                                }.getOrNull() ?: 0L
                            }
                        } catch (e: Exception) {
                            Log.e(name, "Failed to parse upload date: ${e.message}")
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

        val videos = sourceList.mapNotNull {
            val quality = it.attr("size").takeIf { size -> size.isNotBlank() }
            val url = it.attr("src").takeIf { src -> src.isNotBlank() }
            if (url != null && quality != null) {
                Video(url, "${quality}P", videoUrl = url)
            } else {
                null
            }
        }.filterNot { it.videoUrl?.startsWith("blob") == true }

        return if (videos.isNotEmpty()) {
            videos.sortedByDescending { preferQuality == it.quality }
        } else {
            val videoUrl = doc.select("script[type=application/ld+json]").firstOrNull()?.data()?.let {
                try {
                    val info = json.decodeFromString<JsonElement>(it).jsonObject
                    info["contentUrl"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
            } ?: ""

            if (videoUrl.isNotBlank()) {
                listOf(Video(videoUrl, "Raw", videoUrl = videoUrl))
            } else {
                emptyList()
            }
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request {
        val popularUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("sort", "本日排行")
        if (page > 1) {
            popularUrl.addQueryParameter("page", "$page")
        }
        return GET(popularUrl.build())
    }

    private fun String.appendInvisibleChar(): String {
        return "${this}\u200B"
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("div.search-doujin-videos.hidden-xs:not(:has(a[target=_blank]))")

        val list = if (nodes.isNotEmpty()) {
            nodes.map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.select("a[class=overlay]").attr("href"))
                    thumbnail_url = element.select("img + img").attr("src").takeIf { it.isNotBlank() }
                        ?: element.select("img").firstOrNull()?.attr("src") ?: ""
                    title = element.select("div.card-mobile-title").text().appendInvisibleChar()
                    author = element.select(".card-mobile-user").text()
                }
            }
        } else {
            jsoup.select("a:not([target]) > .search-videos").map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.parent()!!.attr("href"))
                    thumbnail_url = element.select("img").attr("src")
                    title = element.select(".home-rows-videos-title").text().appendInvisibleChar()
                }
            }
        }

        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)

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
                        val value = if (useEnglish) {
                            when (it.key) {
                                "genre" -> Tags.getOriginalGenre(it.selected) ?: it.selected
                                "sort" -> Tags.getOriginalSort(it.selected) ?: it.selected
                                "year" -> Tags.getOriginalYear(it.selected) ?: it.selected
                                "month" -> Tags.getOriginalMonth(it.selected) ?: it.selected
                                else -> it.selected
                            }
                        } else {
                            it.selected
                        }
                        searchUrl.addQueryParameter(it.key, value)
                    }
                }
                is BroadMatchFilter -> {
                    if (it.state) {
                        searchUrl.addQueryParameter(it.key, "on")
                    }
                }
                is TagFilter -> {
                    if (it.state) {
                        val tagValue = if (useEnglish) {
                            Tags.getOriginalTag(it.name) ?: it.name
                        } else {
                            it.name
                        }
                        searchUrl.addQueryParameter(it.key, tagValue)
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
            try {
                val jsoup = client.newCall(GET("$baseUrl/search")).awaitSuccess().asJsoup()
                val genreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
                val sortList = jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
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

                preferences.edit()
                    .putString(PREF_KEY_GENRE_LIST, genreList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_SORT_LIST, sortList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_YEAR_LIST, yearList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_MONTH_LIST, monthList.joinToString(SEPARATOR))
                    .putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(categoryDict))
                    .apply()
                filterUpdateState = FilterUpdateState.COMPLETED
            } catch (e: Exception) {
                Log.e(name, "Failed to update filters: ${e.message}")
                filterUpdateState = FilterUpdateState.FAILED
            }
        }
    }

    private fun <T : QueryFilter> createFilter(prefKey: String, block: (Array<String>) -> T): T {
        val savedOptions = preferences.getString(prefKey, "")
        return if (savedOptions.isNullOrEmpty()) {
            block(emptyArray())
        } else {
            block(savedOptions.split(SEPARATOR).toTypedArray())
        }
    }

    private fun createCategoryFilters(): List<AnimeFilter<out Any>> {
        val result = mutableListOf<AnimeFilter<out Any>>(
            BroadMatchFilter(),
        )
        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)

        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, "")
        if (savedCategories.isNullOrEmpty()) {
            return result
        }

        try {
            json.decodeFromString<Map<String, List<String>>>(savedCategories).forEach { (chineseCategory, chineseTags) ->
                val categoryName = if (useEnglish) {
                    Tags.getTranslatedCategory(chineseCategory) ?: chineseCategory
                } else {
                    chineseCategory
                }

                val tagFilters = chineseTags.map { chineseTag ->
                    val tagName = if (useEnglish) {
                        Tags.getTranslatedTag(chineseTag) ?: chineseTag
                    } else {
                        chineseTag
                    }
                    TagFilter("tags[]", tagName)
                }
                result.add(CategoryFilter(categoryName, tagFilters))
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to create category filters: ${e.message}")
        }

        return result
    }

    override fun getFilterList(): AnimeFilterList {
        val useEnglish = preferences.getBoolean(PREF_KEY_USE_ENGLISH, true)

        // Create filters with Chinese values
        val genreFilter = createFilter(PREF_KEY_GENRE_LIST) { GenreFilter(it) }
        val sortFilter = createFilter(PREF_KEY_SORT_LIST) { SortFilter(it) }
        val yearFilter = createFilter(PREF_KEY_YEAR_LIST) { YearFilter(it) }
        val monthFilter = createFilter(PREF_KEY_MONTH_LIST) { MonthFilter(it) }

        // If English is enabled, create new filters with translated values
        return if (useEnglish) {
            val translatedGenreValues = genreFilter.values.map {
                Tags.getTranslatedGenre(it) ?: it
            }.toTypedArray()
            val translatedSortValues = sortFilter.values.map {
                Tags.getTranslatedSort(it) ?: it
            }.toTypedArray()
            val translatedYearValues = yearFilter.values.map {
                Tags.getTranslatedYear(it) ?: it
            }.toTypedArray()
            val translatedMonthValues = monthFilter.values.map {
                Tags.getTranslatedMonth(it) ?: it
            }.toTypedArray()
            AnimeFilterList(
                GenreFilter(translatedGenreValues),
                SortFilter(translatedSortValues),
                DateFilter(
                    YearFilter(translatedYearValues),
                    MonthFilter(translatedMonthValues),
                ),
                TagsFilter(createCategoryFilters()),
            )
        } else {
            AnimeFilterList(
                genreFilter,
                sortFilter,
                DateFilter(yearFilter, monthFilter),
                TagsFilter(createCategoryFilters()),
            )
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(
                SwitchPreferenceCompat(context).apply {
                    key = PREF_KEY_USE_ENGLISH
                    title = "Use English filters"
                    summary = "Show filter names in English (also affects tags in anime details)"
                    setDefaultValue(true)
                },
            )
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_VIDEO_QUALITY
                    title = "設置首選畫質"
                    entries = arrayOf("1080P", "720P", "480P")
                    entryValues = entries
                    setDefaultValue(DEFAULT_QUALITY)
                    summary = "當前選擇：${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
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
                        try {
                            client.cookieJar.saveFromResponse(
                                baseHttpUrl,
                                listOf(
                                    Cookie.parse(
                                        baseHttpUrl,
                                        "user_lang=${newValue as String}",
                                    )!!,
                                ),
                            )
                        } catch (e: Exception) {
                            Log.e(name, "Failed to set language cookie: ${e.message}")
                        }
                        true
                    }
                },
            )
        }
    }

    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val PREF_KEY_LANG = "PREF_KEY_LANG"
        const val PREF_KEY_USE_ENGLISH = "PREF_KEY_USE_ENGLISH"
        const val PREF_KEY_GENRE_LIST = "PREF_KEY_GENRE_LIST"
        const val PREF_KEY_SORT_LIST = "PREF_KEY_SORT_LIST"
        const val PREF_KEY_YEAR_LIST = "PREF_KEY_YEAR_LIST"
        const val PREF_KEY_MONTH_LIST = "PREF_KEY_MONTH_LIST"
        const val PREF_KEY_CATEGORY_LIST = "PREF_KEY_CATEGORY_LIST"
        const val DEFAULT_QUALITY = "1080P"
        const val SEPARATOR = "|||"
    }
}
