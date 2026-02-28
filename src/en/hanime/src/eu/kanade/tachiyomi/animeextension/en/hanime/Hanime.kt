package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Hanime : ConfigurableAnimeSource, AnimeHttpSource() {
    override val name = "hanime.tv"
    override val baseUrl = "https://hanime.tv"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val popularRequestHeaders = Headers.headersOf(
        "authority",
        "search.htv-services.com",
        "accept",
        "application/json, text/plain, */*",
        "content-type",
        "application/json;charset=UTF-8",
    )

    override fun popularAnimeRequest(page: Int): Request {
        return POST(
            "https://search.htv-services.com/",
            popularRequestHeaders,
            RequestBodyBuilder.searchRequestBody("", page, AnimeFilterList(), this),
        )
    }

    override fun popularAnimeParse(response: Response) = ResponseParser.parseSearchJson(response, this)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return POST(
            "https://search.htv-services.com/",
            popularRequestHeaders,
            RequestBodyBuilder.searchRequestBody(query, page, filters, this),
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = ResponseParser.parseSearchJson(response, this)

    override fun latestUpdatesRequest(page: Int): Request {
        return POST(
            "https://search.htv-services.com/",
            popularRequestHeaders,
            RequestBodyBuilder.latestSearchRequestBody(page),
        )
    }

    override fun latestUpdatesParse(response: Response) = ResponseParser.parseSearchJson(response, this)

    override fun animeDetailsParse(response: Response): SAnime = ResponseParser.parseAnimeDetails(response, this)

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$baseUrl/api/v8/video?id=$slug")
    }

    override fun episodeListParse(response: Response): List<SEpisode> = ResponseParser.parseEpisodeList(response, baseUrl)

    override fun videoListRequest(episode: SEpisode) = GET(episode.url)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (authCookie, sessionToken, userLicense) = getFreshAuthCookies()
        val videos = if (authCookie != null && sessionToken != null && userLicense != null) {
            VideoFetcher.fetchVideoListPremium(episode, client, headers, authCookie, sessionToken, userLicense)
        } else {
            VideoFetcher.fetchVideoListGuest(episode, client, headers)
        }
        return videos
    }

    private fun getFreshAuthCookies(): Triple<String?, String?, String?> {
        val cookieList = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        var authCookie: String? = null
        var sessionToken: String? = null
        var userLicense: String? = null
        cookieList.firstOrNull { it.name == "htv3session" }?.let {
            authCookie = "${it.name}=${it.value}"
            sessionToken = it.value
        }
        val licenseCookie = cookieList.firstOrNull { it.name == "x-user-license" }
        if (licenseCookie != null) {
            userLicense = licenseCookie.value
        }
        return Triple(authCookie, sessionToken, userLicense)
    }

    override fun videoListParse(response: Response): List<Video> = emptyList()

    override fun List<Video>.sort(): List<Video> = VideoSorter.sortVideos(this, preferences)

    override fun getFilterList() = FilterProvider.getFilterList()

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080p"
        val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
