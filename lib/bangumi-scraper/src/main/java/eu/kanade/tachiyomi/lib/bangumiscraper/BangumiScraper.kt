package eu.kanade.tachiyomi.lib.bangumiscraper

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class BangumiSubjectType(val value: Int) {
    BOOK(1),
    ANIME(2),
    MUSIC(3),
    GAME(4),
    REAL(6),
}

enum class BangumiFetchType {
    /**
     * Give cover and summary info.
     */
    SHORT,

    /**
     * Give all require info include genre and author info.
     */
    ALL,
}

/**
 * A helper class to fetch anime details from Bangumi
 */
object BangumiScraper {
    private const val SEARCH_URL = "https://api.bgm.tv/search/subject"
    private const val SUBJECTS_URL = "https://api.bgm.tv/v0/subjects"

    /**
     * Fetch anime details info from Bangumi
     * @param fetchType check [BangumiFetchType] to get detail
     * @param subjectType check [BangumiSubjectType] to get detail
     * @param requestProducer used to custom request
     */
    suspend fun fetchDetail(
        client: OkHttpClient,
        keyword: String,
        fetchType: BangumiFetchType = BangumiFetchType.SHORT,
        subjectType: BangumiSubjectType = BangumiSubjectType.ANIME,
        requestProducer: (url: HttpUrl) -> Request = { url -> GET(url) },
    ): SAnime {
        val httpUrl = SEARCH_URL.toHttpUrl().newBuilder()
            .addPathSegment(keyword)
            .addQueryParameter(
                "responseGroup",
                if (fetchType == BangumiFetchType.ALL) {
                    "small"
                } else {
                    "medium"
                },
            )
            .addQueryParameter("type", "${subjectType.value}")
            .addQueryParameter("start", "0")
            .addQueryParameter("max_results", "1")
            .build()
        val searchResponse = client.newCall(requestProducer(httpUrl)).awaitSuccess()
            .checkErrorMessage().parseAs<SearchResponse>()
        return if (searchResponse.list.isEmpty()) {
            SAnime.create()
        } else {
            val item = searchResponse.list[0]
            if (fetchType == BangumiFetchType.ALL) {
                fetchSubject(client, "${item.id}", requestProducer)
            } else {
                SAnime.create().apply {
                    thumbnail_url = item.images.large
                    description = item.summary
                }
            }
        }
    }

    private suspend fun fetchSubject(
        client: OkHttpClient,
        id: String,
        requestProducer: (url: HttpUrl) -> Request,
    ): SAnime {
        val httpUrl = SUBJECTS_URL.toHttpUrl().newBuilder().addPathSegment(id).build()
        val subject = client.newCall(requestProducer(httpUrl)).awaitSuccess()
            .checkErrorMessage().parseAs<Subject>()
        return SAnime.create().apply {
            thumbnail_url = subject.images.large
            description = subject.summary
            genre = buildList {
                addAll(subject.metaTags)
                subject.findInfo("动画制作")?.let { add(it) }
                subject.findInfo("放送开始")?.let { add(it) }
            }.joinToString()
            author = subject.findAuthor()
            artist = subject.findArtist()
            if (subject.findInfo("播放结束") != null) {
                status = SAnime.COMPLETED
            } else if (subject.findInfo("放送开始") != null) {
                status = SAnime.ONGOING
            }
        }
    }

    private fun Response.checkErrorMessage(): String {
        val responseStr = body.string()
        val errorMessage =
            responseStr.parseAs<JsonElement>().jsonObject["error"]?.jsonPrimitive?.contentOrNull
        if (errorMessage != null) {
            throw BangumiScraperException(errorMessage)
        }
        return responseStr
    }
}



