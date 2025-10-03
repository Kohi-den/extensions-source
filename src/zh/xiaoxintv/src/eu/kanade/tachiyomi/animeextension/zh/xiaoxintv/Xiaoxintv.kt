package eu.kanade.tachiyomi.animeextension.zh.xiaoxintv

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

private object HotSortFilter :
    PathFilter(FilterType.SORT.title, arrayOf(SearchSort("人气", "hits")))

class Xiaoxintv : AnimeHttpSource() {
    override val baseUrl: String
        get() = "https://xiaoxintv.cc"
    override val lang: String
        get() = "zh"
    override val name: String
        get() = "小宝影院"
    override val supportsLatest: Boolean
        get() = true

    private val majorSearchTypeSet: Array<SearchType>
        get() = defaultMajorSearchTypeSet
    private val searchSortTypeSet: Array<SearchSort>
        get() = defaultSortTypeSet
    private val filterUpdateRecord by lazy {
        majorSearchTypeSet.associateWith {
            false
        }.toMutableMap()
    }
    private val json by injectLazy<Json>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            thumbnail_url =
                document.select(".myui-vodlist__thumb.picture img").attr("data-original")
            url = document.select(".myui-vodlist__thumb.picture").attr("href")
            title = document.select(".myui-content__detail .title").text()
            author = document.selectFirst("p.data:contains(主演：)")?.text()
            artist = document.selectFirst("p.data:contains(导演：)")?.text()
            description = document.selectFirst("p.data:contains(简介：)")?.ownText()
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("#playlist1 ul li").mapIndexed { index, element ->
            SEpisode.create().apply {
                url = element.select("a").attr("href")
                name = element.attr("title")
                episode_number = index.toFloat()
            }
        }.reversed()
    }

    private fun findVideoUrl(document: Document): String {
        val script = document.select("script:containsData(player_aaaa)").first()!!.data()
        val info = script.substringAfter("player_aaaa=").let { json.parseToJsonElement(it) }
        return info.jsonObject["url"]!!.jsonPrimitive.content
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoUrl = findVideoUrl(document)
        return listOf(Video(videoUrl, "小宝影院", videoUrl))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return searchFilterParse(response)
    }

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(
        page,
        "",
        AnimeFilterList(
            majorSearchTypeSet[0].toFilter(),
        ),
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        return searchFilterParse(response)
    }

    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(
        page,
        "",
        AnimeFilterList(
            majorSearchTypeSet[0].toFilter(),
            HotSortFilter,
        ),
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val requestUrl = response.request.url.toString()
        if (requestUrl.contains("/vod/search")) {
            return searchKeywordParse(response)
        }
        return searchFilterParse(response)
    }

    private fun searchFilterParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        tryUpdateFilters(response.request, document)
        val items = document.select(".myui-vodlist__box").map {
            SAnime.create().apply {
                val thumbNode = it.select(".myui-vodlist__thumb")
                url = thumbNode.attr("href")
                thumbnail_url = thumbNode.attr("data-original")
                title = thumbNode.attr("title")
            }
        }
        val nextPageUrl = document.select(".myui-page a:contains(下一页)").attr("href")
        return AnimesPage(items, !response.request.url.toString().endsWith(nextPageUrl))
    }

    private fun tryUpdateFilters(request: Request, document: Document) {
        val requestUrl = request.url.toString()
        val match = filterUpdateRecord.firstNotNullOfOrNull {
            if (requestUrl.endsWith(it.key.toPath())) {
                it.key
            } else {
                null
            }
        }
        if (match == null || filterUpdateRecord[match] == true) {
            return
        }
        filterUpdateRecord[match] = true
        var typeList: List<SearchType> = emptyList()
        var langList: List<SearchLang> = emptyList()
        var yearList: List<SearchYear> = emptyList()
        var regionList: List<SearchRegion> = emptyList()
        var classList: List<SearchClass> = emptyList()
        document.select(".myui-panel_bd .myui-screen__list").forEach {
            val li = it.select("li a")
            val key = li[0].text()
            val options = li.drop(1)
            when (key) {
                FilterType.TYPE.title -> {
                    typeList = options.mapIndexed { index, element ->
                        SearchType(
                            element.text(),
                            element.attr("href").substringAfter("id/")
                                .substringBefore("/").removeSuffix(".html"),
                            ignore = index == 0,
                        )
                    }
                }

                FilterType.LANG.title -> {
                    langList = options.mapIndexed { index, element ->
                        SearchLang(element.text(), ignore = index == 0)
                    }
                }

                FilterType.YEAR.title -> {
                    yearList = options.mapIndexed { index, element ->
                        SearchYear(element.text(), ignore = index == 0)
                    }
                }

                FilterType.REGION.title -> {
                    regionList = options.mapIndexed { index, element ->
                        SearchRegion(element.text(), ignore = index == 0)
                    }
                }

                FilterType.CLASS.title -> {
                    classList = options.mapIndexed { index, element ->
                        SearchClass(element.text(), ignore = index == 0)
                    }
                }

                else -> {}
            }
        }
        val config = SearchFilterConfig(typeList, classList, yearList, langList, regionList)
        if (config.isEmpty()) {
            return
        }
        preferences.saveSearchFilterConfig(match.id, config, json)
    }

    private fun searchKeywordParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("#searchList li").map {
            SAnime.create().apply {
                val thumbNode = it.select("a.myui-vodlist__thumb")
                url = thumbNode.attr("href")
                thumbnail_url = thumbNode.attr("data-original")
                title = thumbNode.attr("title")
            }
        }
        val nextPageUrl = document.select(".myui-page a:contains(下一页)").attr("href")
        return AnimesPage(items, !response.request.url.toString().endsWith(nextPageUrl))
    }

    private fun keywordQuery(page: Int, query: String): Request {
        val searchUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("index.php/vod/search")
        if (page > 1) {
            searchUrl.addPathSegments("page/$page/wd/$query")
        } else {
            searchUrl.addQueryParameter("wd", query)
        }
        return GET(searchUrl.build())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return keywordQuery(page, query)
        }
        val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegments("index.php/vod/show")
        val filterPath = filters.flatMap {
            if (it is GroupFilter) {
                it.state
            } else {
                listOf(it)
            }
        }.filterIsInstance<PathFilter>().map { it.selected }.toPath()
        if (filterPath.isEmpty()) {
            searchUrl.addPathSegments(majorSearchTypeSet[0].toPath().removePrefix("/"))
        } else {
            searchUrl.addPathSegments(filterPath)
        }
        if (page > 1) {
            searchUrl.addPathSegments("page/$page")
        }
        return GET(searchUrl.build())
    }

    private fun List<SearchBean>.toFilter(name: String): PathFilter? {
        if (isEmpty()) {
            return null
        }
        return PathFilter(name, toTypedArray())
    }

    override fun getFilterList(): AnimeFilterList {
        val groupFilters = majorSearchTypeSet.map { majorType ->
            val config = preferences.findSearchFilterConfig(majorType.id, json)
            val filters = listOfNotNull(
                config.type.toFilter(FilterType.TYPE.title),
                config.region.toFilter(FilterType.REGION.title),
                config.category.toFilter(FilterType.CLASS.title),
                config.year.toFilter(FilterType.YEAR.title),
                config.lang.toFilter(FilterType.LANG.title),
            )
            GroupFilter(majorType.name, filters)
        }.toTypedArray()
        return AnimeFilterList(
            PathFilter("主分类", majorSearchTypeSet),
            PathFilter(FilterType.SORT.title, searchSortTypeSet),
            AnimeFilter.Header("展开下方与主分类对应分组可进行更多设置"),
            *groupFilters,
        )
    }
}
