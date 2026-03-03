package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.content.SharedPreferences
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.resume

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
        var videos = emptyList<Video>()

        val slug = episode.url.substringAfter("?id=")
        val videoPageUrl = "$baseUrl/videos/hentai/$slug"

        val (signature, timestamp, videoId) = extractVideoDataWithWebView(videoPageUrl)

        if (signature.isNotEmpty() && timestamp > 0L) {
            if (authCookie != null && sessionToken != null && userLicense != null) {
                videos = try {
                    VideoFetcher.fetchVideoListPremium(
                        episode = episode,
                        client = client,
                        headers = headers,
                        authCookie = authCookie,
                        sessionToken = sessionToken,
                        userLicense = userLicense,
                        signature = signature,
                        timestamp = timestamp,
                        videoId = videoId,
                    )
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (videos.isEmpty()) {
                videos = VideoFetcher.fetchVideoListGuest(
                    episode = episode,
                    client = client,
                    headers = headers,
                    signature = signature,
                    timestamp = timestamp,
                    videoId = videoId,
                )
            }
        }

        return videos
    }

    private suspend fun extractVideoDataWithWebView(pageUrl: String): Triple<String, Long, String> {
        return withTimeout(15000L) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(Injekt.get<Application>())

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.loadWithOverviewMode = true
                webView.settings.useWideViewPort = true
                webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        webView.evaluateJavascript(
                            """
                            (function() {
                                return new Promise((resolve) => {
                                    const result = { signature: '', timestamp: 0, videoId: '' };
                                    const checkExisting = () => {
                                        if (window.ssignature && window.stime) {
                                            result.signature = window.ssignature;
                                            result.timestamp = window.stime;
                                            const videoIdMatch = document.documentElement.innerHTML.match(/\/api\/v8\/video\?id=([^"&\s]+)/);
                                            if (videoIdMatch) {
                                                result.videoId = videoIdMatch[1];
                                            }
                                            resolve(JSON.stringify(result));
                                            return true;
                                        }
                                        return false;
                                    };
                                    if (checkExisting()) return;
                                    const script = document.createElement('script');
                                    script.src = 'https://hanime-cdn.com/vhtv2/40c99ce.js';
                                    script.onload = () => {
                                        let attempts = 0;
                                        const checkInterval = setInterval(() => {
                                            attempts++;
                                            if (checkExisting() || attempts > 50) {
                                                clearInterval(checkInterval);
                                                if (attempts > 50 && !result.signature) {
                                                    resolve(JSON.stringify(result));
                                                }
                                            }
                                        }, 100);
                                    };
                                    script.onerror = () => {
                                        resolve(JSON.stringify(result));
                                    };
                                    document.head.appendChild(script);
                                });
                            })()
                            """.trimIndent()
                        ) { result ->
                            try {
                                val json = JSONObject(result)
                                continuation.resume(
                                    Triple(
                                        json.optString("signature", ""),
                                        json.optLong("timestamp", 0L),
                                        json.optString("videoId", ""),
                                    ),
                                )
                            } catch (e: Exception) {
                                continuation.resume(Triple("", 0L, ""))
                            } finally {
                                webView.destroy()
                            }
                        }
                    }
                }

                webView.loadUrl(pageUrl)
            }
        }
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
