package eu.kanade.tachiyomi.animeextension.zh.anime1

import android.app.Application
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.bangumiscraper.BangumiFetchType
import eu.kanade.tachiyomi.lib.bangumiscraper.BangumiScraper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Anime1 : AnimeHttpSource(), ConfigurableAnimeSource {
    override val baseUrl: String
        get() = "https://anime1.me"
    override val lang: String
        get() = "zh-hant"
    override val name: String
        get() = "Anime1.me"
    override val supportsLatest: Boolean
        get() = true

    override fun headersBuilder() = super.headersBuilder().add("referer", "$baseUrl/")

    private val videoApiUrl = "https://v.anime1.me/api"
    private val dataUrl = "https://d1zquzjgwo9yb.cloudfront.net"
    private val uploadDateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }
    private lateinit var data: JsonArray
    private val cookieManager
        get() = CookieManager.getInstance()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return if (bangumiEnable) {
            BangumiScraper.fetchDetail(
                client,
                ZhTwConverterUtil.toSimple(anime.title.removeSuffixMark()),
                fetchType = bangumiFetchType,
            )
        } else {
            anime.thumbnail_url = FIX_COVER
            anime
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        var document: Document? = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        val requestUrl = response.request.url.toString()
        while (document != null) {
            val items = document.select("article.post").map {
                SEpisode.create().apply {
                    name = it.select(".entry-title").text()
                    val url = it.selectFirst(".entry-title a")?.attr("href") ?: requestUrl
                    setUrlWithoutDomain(url)
                    date_upload = it.select("time.updated").attr("datetime").let { date ->
                        runCatching { uploadDateFormat.parse(date)?.time }.getOrNull() ?: 0L
                    }
                }
            }
            episodes.addAll(items)
            val previousUrl = document.select(".nav-previous a").attr("href")
            document = if (previousUrl.isBlank()) {
                null
            } else {
                client.newCall(GET(previousUrl)).execute().asJsoup()
            }
        }
        return episodes
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val req = document.select("video").attr("data-apireq")
        val videoResponse: VideoResponse = client.newCall(
            POST(
                videoApiUrl,
                body = "d=$req".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()),
            ),
        ).execute().parseAs()
        return videoResponse.s.map {
            val videoUrl = "https:${it.src}"
            val newHeaders = cookieManager.getCookie(videoUrl)?.let { cookie ->
                headers.newBuilder().add("cookie", cookie).build()
            }
            Video(videoUrl, it.type, videoUrl, headers = newHeaders)
        }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (!this::data.isInitialized) {
            data = client.newCall(GET("$dataUrl/?_=${System.currentTimeMillis()}")).awaitSuccess()
                .parseAs()
        }
        val items = data.subList((page - 1) * PAGE_SIZE, (page * PAGE_SIZE).coerceAtMost(data.size))
        return AnimesPage(
            items.map {
                SAnime.create().apply {
                    val array = it.jsonArray
                    val id = array.getContent(0)!!
                    url = "?cat=$id"
                    title = array.getContent(1)!!
                    if (id == "0" || title.contains("</a>")) {
                        val doc = Jsoup.parse(title)
                        doc.selectFirst("a")?.let { link ->
                            url = link.attr("href")
                        }
                        title = doc.text()
                    }
                    status = if (array.getContent(2)?.contains("連載中") == true) {
                        SAnime.ONGOING
                    } else {
                        SAnime.COMPLETED
                    }
                    genre = listOfNotNull(
                        array.getContent(3),
                        array.getContent(4),
                        array.getContent(5),
                    ).joinToString()
                    thumbnail_url = FIX_COVER
                }
            },
            items.size == PAGE_SIZE,
        )
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        return getLatestUpdates(page)
    }

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage {
        // The search result is episode
        val document = response.asJsoup()
        val items = document.select("article.post .entry-title a").map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.attr("href"))
                title = it.ownText()
                thumbnail_url = FIX_COVER
            }
        }
        val previous = document.select(".nav-previous")
        return AnimesPage(items, previous.isNotEmpty())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        if (page > 1) {
            url.addPathSegments("page/$page")
        }
        url.addQueryParameter("s", query)
        return GET(url.build())
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val bangumiScraper = CheckBoxPreference(screen.context).apply {
            key = PREF_KEY_BANGUMI
            title = "啟用Bangumi刮削"
        }
        val bangumiFetchType = ListPreference(screen.context).apply {
            key = PREF_KEY_BANGUMI_FETCH_TYPE
            title = "詳情拉取設置"
            setVisible(bangumiEnable)
            entries = arrayOf("拉取部分數據", "拉取完整數據")
            entryValues = arrayOf(BangumiFetchType.SHORT.name, BangumiFetchType.ALL.name)
            setDefaultValue(entryValues[0])
            summary = when (bangumiFetchType) {
                BangumiFetchType.SHORT -> entries[0]
                BangumiFetchType.ALL -> entries[1]
                else -> entries[0]
            }
            setOnPreferenceChangeListener { _, value ->
                summary = when (value) {
                    BangumiFetchType.SHORT.name -> entries[0]
                    BangumiFetchType.ALL.name -> entries[1]
                    else -> entries[0]
                }
                true
            }
        }
        bangumiScraper.setOnPreferenceChangeListener { _, value ->
            bangumiFetchType.setVisible(value as Boolean)
            true
        }
        screen.apply {
            addPreference(bangumiScraper)
            addPreference(bangumiFetchType)
        }
    }

    private val bangumiEnable: Boolean
        get() = preferences.getBoolean(PREF_KEY_BANGUMI, false)
    private val bangumiFetchType: BangumiFetchType
        get() {
            val fetchTypeName =
                preferences.getString(PREF_KEY_BANGUMI_FETCH_TYPE, BangumiFetchType.SHORT.name)
            return when (fetchTypeName) {
                BangumiFetchType.SHORT.name -> BangumiFetchType.SHORT
                BangumiFetchType.ALL.name -> BangumiFetchType.ALL
                else -> BangumiFetchType.SHORT
            }
        }

    private fun JsonArray.getContent(index: Int): String? {
        return getOrNull(index)?.jsonPrimitive?.contentOrNull
    }

    private fun String.removeSuffixMark(): String {
        return removeBracket("(", ")").removeBracket("[", "]").trim()
    }

    private fun String.removeBracket(start: String, end: String): String {
        val seasonStart = indexOf(start)
        val seasonEnd = indexOf(end)
        if (seasonEnd > seasonStart) {
            return removeRange(seasonStart, seasonEnd + 1)
        }
        return this
    }

    companion object {
        const val PAGE_SIZE = 20
        const val FIX_COVER = "https://sta.anicdn.com/playerImg/8.jpg"

        const val PREF_KEY_BANGUMI = "PREF_KEY_BANGUMI"
        const val PREF_KEY_BANGUMI_FETCH_TYPE = "PREF_KEY_BANGUMI_FETCH_TYPE"
    }
}

@Serializable
data class VideoSource(val src: String, val type: String)

@Serializable
data class VideoResponse(val s: List<VideoSource>)
