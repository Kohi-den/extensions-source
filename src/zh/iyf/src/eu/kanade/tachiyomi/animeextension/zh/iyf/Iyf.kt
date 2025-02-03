package eu.kanade.tachiyomi.animeextension.zh.iyf

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

class Iyf : AnimeHttpSource() {
    override val baseUrl: String
        get() = "https://www.iyf.tv"
    override val lang: String
        get() = "zh"
    override val name: String
        get() = "爱壹帆"
    override val supportsLatest: Boolean
        get() = true

    override fun headersBuilder(): Headers.Builder {
        // Force the use of desktop user agent.
        return super.headersBuilder().set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
        )
    }

    private val json by injectLazy<Json>()
    private val pConfig: PConfig by lazy {
        val doc = client.newCall(GET("$baseUrl/list", headers)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(injectJson)")!!.data()
        val pConfigStr =
            script.substringAfter("\"pConfig\":").let { it.substring(0, it.indexOf("}") + 1) }
        val pConfig = json.decodeFromString<JsonElement>(pConfigStr).jsonObject
        PConfig(
            publicKey = pConfig["publicKey"]!!.jsonPrimitive.content,
            privateKey = pConfig["privateKey"]!!.jsonArray[0].jsonPrimitive.content,
        )
    }
    private val popularAnimeFilterList = AnimeFilterList(
        TypeFilter(listOf("动漫" to "0,1,6")),
        SortFilter(listOf("人气高低" to "2")),
    )
    private val latestAnimeFilterList = AnimeFilterList(
        TypeFilter(listOf("动漫" to "0,1,6")),
        SortFilter(listOf("更新时间" to "1")),
    )
    private val episodeDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    }
    private val searchSortFilter by lazy {
        SortFilter(listOf("匹配度" to "4"))
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val vid = "$baseUrl${anime.url}".toHttpUrl().pathSegments.last()
        val detailUrl = "https://m10.iyf.tv/v3/video/detail".toHttpUrl().newBuilder()
        detailUrl.addQueryParameter("cinema", "1")
            .addQueryParameter("device", "1")
            .addQueryParameter("player", "CkPlayer")
            .addQueryParameter("tech", "HLS")
            .addQueryParameter("country", "HU")
            .addQueryParameter("lang", "cns")
            .addQueryParameter("v", "1")
            .addQueryParameter("id", vid)
            .addQueryParameter("region", "SG")
            .appendSignature()
        return GET(detailUrl.build(), headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val resp = response.parseAs<CommonResponse<VideoDetail>>()
        val detail = resp.data.info[0]
        return SAnime.create().apply {
            title = detail.title
            thumbnail_url = detail.imgPath
            author = detail.directors.joinToString()
            artist = detail.stars.joinToString()
            genre = detail.keyWord.split(",").joinToString()
            status = if (detail.serialCount == 0) {
                SAnime.COMPLETED
            } else {
                SAnime.ONGOING
            }
            description = """
                添加：${detail.addDate}
                更新：${detail.updateWeekly}
                简介：${detail.context}
            """.trimIndent()
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val httpUrl = "$baseUrl${anime.url}".toHttpUrl()
        val vid = httpUrl.pathSegments.last()
        val cid = httpUrl.fragment
        val playlistUrl = "https://m10.iyf.tv/v3/video/languagesplaylist".toHttpUrl().newBuilder()
        playlistUrl.addQueryParameter("cinema", "1")
            .addQueryParameter("vid", vid)
            .addQueryParameter("lsk", "1")
            .addQueryParameter("taxis", "0")
            .addQueryParameter("cid", cid)
            .appendSignature()
        return GET(playlistUrl.build(), headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val resp = response.parseAs<CommonResponse<PlayList>>()
        val playList = resp.data.info[0].playList
        return playList.map {
            SEpisode.create().apply {
                url = "/${it.key}"
                name = it.name
                date_upload =
                    runCatching { episodeDateFormat.parse(it.updateDate)?.time }.getOrNull() ?: 0L
            }
        }.asReversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val vid = "$baseUrl${episode.url}".toHttpUrl().pathSegments.last()
        val playUrl = "https://m10.iyf.tv/v3/video/play".toHttpUrl().newBuilder()
        playUrl.addQueryParameter("cinema", "1")
            .addQueryParameter("id", vid)
            .addQueryParameter("a", "0")
            .addQueryParameter("lang", "none")
            .addQueryParameter("usersign", "1")
            .addQueryParameter("region", "SG")
            .addQueryParameter("device", "1")
            .addQueryParameter("isMasterSupport", "1")
            .appendSignature()
        return GET(playUrl.build(), headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val resp = response.parseAs<CommonResponse<Play>>()
        val play = resp.data.info[0]
        return play.clarity.filter { it.path != null }.map {
            val url = it.path!!.rtmp.toHttpUrl().newBuilder().removeAllQueryParameters("us").build()
                .toString()
            Video(url, it.title, url)
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        searchAnimeRequest(page, "", latestAnimeFilterList)

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request =
        searchAnimeRequest(page, "", popularAnimeFilterList)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val resp = response.parseAs<CommonResponse<SearchResult>>()
        val result = resp.data.info[0].result
        return AnimesPage(
            result.map {
                SAnime.create().apply {
                    url = "/play/${it.vid}#${it.videoClassID}"
                    title = it.title
                    thumbnail_url = it.image
                }
            },
            result.size >= PAGE_SIZE,
        )
    }

    private fun keywordSearchRequest(page: Int, query: String): Request {
        val searchUrlBuilder = "https://rankv21.iyf.tv/v3/list/briefsearch".toHttpUrl().newBuilder()
        searchUrlBuilder.addQueryParameter("tags", query)
        val sortFilter = searchSortFilter
        searchUrlBuilder.addQueryParameter("orderby", sortFilter.orderBy)
            .addQueryParameter("page", "$page")
            .addQueryParameter("size", "$PAGE_SIZE")
            .addQueryParameter("desc", "${sortFilter.desc}")
            .addQueryParameter("isserial", "-1")
        val searchUrl = searchUrlBuilder.build()
        return POST(
            searchUrl.toString(),
            headers,
            FormBody.Builder().add("tags", query)
                .addEncoded("vv", signature(searchUrl.query ?: "", pConfig))
                .addEncoded("pub", pConfig.publicKey)
                .build(),
        )
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            return keywordSearchRequest(page, query)
        }
        val searchUrl = "https://m10.iyf.tv/api/list/Search".toHttpUrl().newBuilder()
        searchUrl.addQueryParameter("cinema", "1")
            .addQueryParameter("page", "$page")
            .addQueryParameter("size", "$PAGE_SIZE")
        filters.list.filterIsInstance<SortFilter>().firstOrNull()?.let {
            searchUrl.addQueryParameter("orderby", it.orderBy)
                .addQueryParameter("desc", "${it.desc}")
        }
        filters.list.filterIsInstance<PairSelectFilter>().forEach {
            if (it.selected.isNotEmpty()) {
                searchUrl.addQueryParameter(it.key, it.selected)
            }
        }
        searchUrl.addQueryParameter("isIndex", "-1")
            .addQueryParameter("isfree", "-1")
        // add signature
        searchUrl.appendSignature()
        return GET(searchUrl.build(), headers)
    }

    private fun HttpUrl.Builder.appendSignature(): HttpUrl.Builder {
        addQueryParameter("vv", signature(build().query ?: "", pConfig))
        addQueryParameter("pub", pConfig.publicKey)
        return this
    }

    private fun signature(query: String, pConfig: PConfig): String {
        val s = "${pConfig.publicKey}&${query.lowercase()}&${pConfig.privateKey}"
        return MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            TypeFilter(),
            SortFilter(),
            RegionFilter(),
            LangFilter(),
            YearFilter(),
            QualityFilter(),
            StatusFilter(),
        )
    }

    private class PConfig(
        val publicKey: String,
        val privateKey: String,
    )

    companion object {
        private const val PAGE_SIZE = 32
    }
}
