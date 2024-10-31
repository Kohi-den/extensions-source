package eu.kanade.tachiyomi.animeextension.zh.anime1

import android.webkit.CookieManager
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
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
import java.text.SimpleDateFormat
import java.util.Locale

class Anime1 : AnimeHttpSource() {
    override val baseUrl: String
        get() = "https://anime1.me"
    override val lang: String
        get() = "zh"
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
    private lateinit var data: JsonArray // real data
    private val cookieManager
        get() = CookieManager.getInstance()

    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return SAnime.create().apply {
            thumbnail_url = FIX_COVER
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        var document: Document? = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        while (document != null) {
            val items = document.select("article.post").map {
                SEpisode.create().apply {
                    name = it.select(".entry-title").text()
                    setUrlWithoutDomain(it.select(".entry-title a").attr("href"))
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

    private fun JsonArray.getContent(index: Int): String? {
        return getOrNull(index)?.jsonPrimitive?.contentOrNull
    }

    companion object {
        const val PAGE_SIZE = 20
        const val FIX_COVER = "https://sta.anicdn.com/playerImg/8.jpg"
    }
}

@Serializable
data class VideoSource(val src: String, val type: String)

@Serializable
data class VideoResponse(val s: List<VideoSource>)
