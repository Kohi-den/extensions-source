package eu.kanade.tachiyomi.animeextension.zh.nivod

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
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.util.Calendar

class Nivod : AnimeHttpSource() {
    override val baseUrl: String
        get() = "https://www.nivod.cc"
    override val lang: String
        get() = "zh"
    override val name: String
        get() = "泥视频"
    override val supportsLatest: Boolean
        get() = true

    private val json by injectLazy<Json>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            thumbnail_url = baseUrl + doc.select(".left-img-c img").attr("src")
            title = doc.select(".right-title").text()
            genre =
                doc.select(".right-label-c .right-label").joinToString { it.text() }
            author = doc.select(".right-type-c:nth-child(4) .right-label").text()
            artist = doc.select(".right-type-c:nth-child(5) .right-label").text()
            description = doc.select(".right-type-c:nth-child(6) .right-label").text()
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select(".list-ruku a").map {
            SEpisode.create().apply {
                url = it.attr("href")
                name = it.select(".item").text()
            }
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val doc = client.newCall(super.videoListRequest(episode)).execute().asJsoup()
        val path = doc.selectFirst("script:containsData(xhr_playinfo)")!!.data()
            .substringAfter("url = '").substringBefore("'")
        return GET(baseUrl + path)
    }

    override fun videoListParse(response: Response): List<Video> {
        val playInfo = response.parseAs<PlayInfo>()
        return playInfo.pdatas.map {
            Video(it.playUrl, it.from.substring(0, 2).uppercase() + "云", it.playUrl)
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnimeList(response.asJsoup().selectFirst(".tl-layout:nth-of-type(1)")!!)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnimeList(response.asJsoup().selectFirst(".tl-layout:nth-of-type(2)")!!)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/class.html?channel=anime")

    private fun parseAnimeList(element: Element): AnimesPage {
        return AnimesPage(
            element.select(".qy-mod-img.vertical").map {
                SAnime.create().apply {
                    url = it.select("a.qy-mod-link").attr("href")
                    title = it.select(".title-wrap .main a").text()
                    thumbnail_url = baseUrl + it.select("picture img").attr("src")
                }
            },
            false,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        if (response.request.url.toString().contains("filter.html")) {
            // update filter config
            val regions = doc.select("#filter_regions .category-item").associate {
                it.text() to it.attr("onclick").substringAfter("Click('").substringBefore("'")
            }
            val types = doc.select("#filter_type .category-item").associate {
                it.text() to it.attr("onclick").substringAfter("Click('").substringBefore("'")
            }
            val years = doc.select("#filter_yearRanges .category-item").associate {
                it.text() to it.attr("onclick").substringAfter("Click('").substringBefore("'")
            }
            val filterConfig = FilterConfig(regions = regions, types = types, years = years)
            val target = response.request.url.queryParameter("channel")
            when (target) {
                "anime" -> saveFilterConfig(PREF_KEY_ANIME_FILTER, filterConfig)
                "tv" -> saveFilterConfig(PREF_KEY_TV_FILTER, filterConfig)
                "movie" -> saveFilterConfig(PREF_KEY_MOVIE_FILTER, filterConfig)
                "show" -> saveFilterConfig(PREF_KEY_SHOW_FILTER, filterConfig)
            }
        }
        return AnimesPage(
            doc.select(".qy-list-img.vertical").map {
                SAnime.create().apply {
                    url = it.select("a.qy-mod-link").attr("href")
                    title = it.select(".title-wrap .main a").text()
                    thumbnail_url =
                        baseUrl + it.select("div.qy-mod-cover").attr("style").substringAfter("url(")
                            .substringBefore(")")
                }
            },
            false,
        )
    }

    private fun keywordSearch(page: Int, query: String): Request {
        return GET(
            "https://e.kortw.cc/vodsearch/-------------.html?keyword=${
                URLEncoder.encode(
                    query,
                    "UTF-8",
                )
            }",
        )
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            return keywordSearch(page, query)
        }
        val filterUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("filter.html")
        filters.list
            .flatMap {
                when (it) {
                    is AnimeFilter.Group<*> -> it.state
                    else -> listOf(it)
                }
            }
            .filterIsInstance<QueryFilter>()
            .forEach {
                if (it.selected.isNotEmpty()) {
                    filterUrl.addQueryParameter(it.key, it.selected)
                }
            }
        return GET(filterUrl.build())
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            ChannelFilter(),
            AnimeFilter.Header("详细筛选设置"),
            generateGroupFilter(PREF_KEY_ANIME_FILTER, "动漫"),
            generateGroupFilter(PREF_KEY_MOVIE_FILTER, "电影"),
            generateGroupFilter(PREF_KEY_TV_FILTER, "电视剧"),
            generateGroupFilter(PREF_KEY_SHOW_FILTER, "综艺"),
        )
    }

    private fun generateGroupFilter(
        key: String,
        name: String,
    ): AnimeFilter.Group<QueryFilter> {
        return object : AnimeFilter.Group<QueryFilter>(name, generateFilters(key)) {}
    }

    private fun generateFilters(key: String): List<QueryFilter> {
        val config = preferences.getString(key, null)
        return if (config.isNullOrEmpty()) {
            createDefaultFilterConfig(key)
        } else {
            json.decodeFromString(config)
        }.let {
            listOf(
                RegionFilter(it.regions.toList()),
                TypeFilter(it.types.toList()),
                YearFilter(it.years.toList()),
            )
        }
    }

    private fun saveFilterConfig(key: String, config: FilterConfig) {
        preferences.edit().putString(key, json.encodeToString(config)).apply()
    }

    private fun createDefaultFilterConfig(key: String): FilterConfig {
        return when (key) {
            PREF_KEY_TV_FILTER -> {
                FilterConfig(
                    regions = mapOf(
                        "全部地区" to "",
                        "大陆" to "cn",
                        "台湾" to "tw",
                        "日本" to "jp",
                    ),
                    types = mapOf(
                        "全部类型" to "",
                        "剧情" to "ju-qing",
                        "动作" to "dong-zuo",
                        "历史" to "li-shi",
                        "历险" to "mao-xian",
                    ),
                    years = createDefaultYearMap(),
                )
            }

            PREF_KEY_MOVIE_FILTER -> {
                FilterConfig(
                    regions = mapOf(
                        "全部地区" to "",
                        "大陆" to "cn",
                        "台湾" to "tw",
                        "日本" to "jp",
                    ),
                    types = mapOf(
                        "全部类型" to "",
                        "冒险" to "mao-xian",
                        "剧情" to "ju-qing",
                        "动作" to "dong-zuo",
                    ),
                    years = createDefaultYearMap(),
                )
            }

            PREF_KEY_SHOW_FILTER -> {
                FilterConfig(
                    regions = mapOf(
                        "全部地区" to "",
                        "大陆" to "cn",
                        "韩国" to "kr",
                        "欧美" to "west",
                        "其他" to "other",
                    ),
                    types = mapOf(
                        "全部类型" to "",
                        "搞笑" to "gao-xiao",
                        "音乐" to "yin-yue",
                        "真人秀" to "zhen-ren-xiu",
                        "脱口秀" to "tuo-kou-xiu",
                    ),
                    years = createDefaultYearMap(),
                )
            }

            else -> {
                FilterConfig(
                    regions = mapOf(
                        "全部地区" to "",
                        "大陆" to "cn",
                        "日本" to "jp",
                        "欧美" to "west",
                    ),
                    types = mapOf(
                        "全部类型" to "",
                        "冒险" to "mao-xian",
                        "动画电影" to "movie",
                        "推理" to "tui-li",
                        "校园" to "xiao-yuan",
                        "治愈" to "zhi-yu",
                        "泡面" to "pao-mian",
                    ),
                    years = createDefaultYearMap(),
                )
            }
        }
    }

    private fun createDefaultYearMap(): Map<String, String> {
        var year = Calendar.getInstance().get(Calendar.YEAR)
        val result = mutableMapOf<String, String>()
        result["全部年份"] = ""
        repeat(10) {
            result["${year--}"] = "$year"
        }
        result["更早"] = "lt__$year"
        return result
    }

    companion object {
        private const val PREF_KEY_ANIME_FILTER = "anime_filter"
        private const val PREF_KEY_SHOW_FILTER = "show_filter"
        private const val PREF_KEY_TV_FILTER = "tv_filter"
        private const val PREF_KEY_MOVIE_FILTER = "movie_filter"
    }
}
