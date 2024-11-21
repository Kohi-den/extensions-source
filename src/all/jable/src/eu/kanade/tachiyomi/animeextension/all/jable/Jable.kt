package eu.kanade.tachiyomi.animeextension.all.jable

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Jable(override val lang: String) : AnimeHttpSource() {
    override val baseUrl: String
        get() = "https://jable.tv"
    override val name: String
        get() = "Jable"
    override val supportsLatest: Boolean
        get() = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val json by injectLazy<Json>()
    private var tagsUpdated = false

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl${anime.url}?lang=$lang", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            val info = doc.select(".info-header")
            title = info.select(".header-left h4").text()
            author = info.select(".header-left .model")
                .joinToString { it.select("span[title]").attr("title") }
            genre = doc.select(".tags a").joinToString { it.text() }
            update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
            status = SAnime.COMPLETED
            description = info.select(".header-right").text()
        }
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Episode"
                url = anime.url
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videoUrl = doc.selectFirst("script:containsData(var hlsUrl)")!!.data()
            .substringAfter("var hlsUrl = '").substringBefore("'")
        return listOf(Video(videoUrl, "Default", videoUrl))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        if (!tagsUpdated) {
            tagsUpdated = preferences.saveTags(
                doc.select("a.tag").associate {
                    it.ownText() to it.attr("href").substringAfter(baseUrl).removePrefix("/")
                        .removeSuffix("/")
                },
            )
        }
        return AnimesPage(
            doc.select(".container .video-img-box").map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select(".img-box a").attr("href"))
                    thumbnail_url = it.select(".img-box img").attr("data-src")
                    title = it.select(".detail .title").text()
                }
            },
            doc.select(".container .pagination .page-item .page-link.disabled").isNullOrEmpty(),
        )
    }

    override fun latestUpdatesRequest(page: Int) =
        searchRequest("latest-updates", page, latestFilter)

    override fun popularAnimeParse(response: Response): AnimesPage = latestUpdatesParse(response)

    override fun popularAnimeRequest(page: Int) =
        searchRequest("hot", page, popularFilter)

    override fun searchAnimeParse(response: Response) = latestUpdatesParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) {
            searchRequest(
                "search/$query",
                page,
                AnimeFilterList(filters.list + defaultSearchFunctionFilter),
                query = query,
            )
        } else {
            val path = filters.list.filterIsInstance<TagFilter>()
                .firstOrNull()?.selected?.second?.takeUnless { it.isEmpty() } ?: "hot"
            searchRequest(path, page, AnimeFilterList(filters.list + commonVideoListFuncFilter))
        }
    }

    private fun searchRequest(
        path: String,
        page: Int,
        filters: AnimeFilterList = AnimeFilterList(),
        query: String = "",
    ): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("$path/")
            .addQueryParameter("lang", lang)
        if (tagsUpdated) {
            // load whole page for update filter tags info
            urlBuilder.addQueryParameter("mode", "async")
        }
        filters.list.forEach {
            when (it) {
                is BlockFunctionFilter -> {
                    urlBuilder.addQueryParameter("function", it.selected.functionName)
                        .addQueryParameter("block_id", it.selected.blockId)
                }

                is SortFilter -> {
                    if (it.selected.second.isNotEmpty()) {
                        urlBuilder.addQueryParameter("sort_by", it.selected.second)
                    }
                }

                else -> {}
            }
        }
        if (query.isNotEmpty()) {
            urlBuilder.addQueryParameter("q", query)
        }
        urlBuilder.addQueryParameter("from", "%02d".format(page))
            .addQueryParameter("_", System.currentTimeMillis().toString())
        return GET(urlBuilder.build())
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            SortFilter(
                intl.filterPopularSortTitle,
                arrayOf(
                    "" to "",
                    intl.hotMonth to "video_viewed_month",
                    intl.hotWeek to "video_viewed_week",
                    intl.hotDay to "video_viewed_today",
                    intl.hotAll to "video_viewed",
                ),
            ),
            TagFilter(
                intl.filterTagTitle,
                buildList {
                    add("" to "")
                    preferences.getTags()?.forEach {
                        add(it.key to it.value)
                    }
                }.toTypedArray(),
            ),
            SortFilter(
                intl.filterTagsSortTitle,
                arrayOf(
                    "" to "",
                    intl.sortLatestUpdate to "post_date",
                    intl.sortMostView to "video_viewed",
                    intl.sortMostFavorite to "most_favourited",
                    intl.sortRecentBest to "post_date_and_popularity",
                ),
            ),
        )
    }

    private fun SharedPreferences.getTags(): Map<String, String>? {
        val savedStr = getString("${lang}_$PREF_KEY_TAGS", null)
        if (savedStr.isNullOrEmpty()) {
            return null
        }
        return json.decodeFromString<Map<String, String>>(savedStr)
    }

    private fun SharedPreferences.saveTags(tags: Map<String, String>): Boolean {
        if (tags.isNotEmpty()) {
            edit().putString("${lang}_$PREF_KEY_TAGS", json.encodeToString(tags)).apply()
            return true
        }
        return false
    }

    private val intl by lazy {
        JableIntl(lang)
    }

    private val commonVideoListFuncFilter by lazy {
        BlockFunctionFilter(
            intl.popular,
            arrayOf(BlockFunction(intl.popular, "list_videos_common_videos_list")),
        )
    }

    private val defaultSearchFunctionFilter by lazy {
        BlockFunctionFilter("", arrayOf(BlockFunction("", "list_videos_videos_list_search_result")))
    }

    private val popularFilter by lazy {
        AnimeFilterList(
            commonVideoListFuncFilter,
            SortFilter(
                intl.hotWeek,
                arrayOf(intl.hotWeek to "video_viewed_week"),
            ),
        )
    }

    private val latestFilter by lazy {
        AnimeFilterList(
            BlockFunctionFilter(
                intl.latestUpdate,
                arrayOf(BlockFunction(intl.latestUpdate, "list_videos_latest_videos_list")),
            ),
            SortFilter(
                intl.sortLatestUpdate,
                arrayOf(intl.sortLatestUpdate to "post_date"),
            ),
        )
    }

    companion object {
        const val PREF_KEY_TAGS = "pref_key_tags"
    }
}
