package eu.kanade.tachiyomi.animeextension.zh.xfani

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class FilterUpdateState {
    NONE,
    UPDATING,
    UPDATED,
    FAILED,
}

class Xfani : AnimeHttpSource(), ConfigurableAnimeSource {
    override val baseUrl: String
        get() = "https://dm.xifanacg.com"
    override val lang: String
        get() = "zh"
    override val name: String
        get() = "稀饭动漫"
    override val supportsLatest: Boolean
        get() = true

    private val json by injectLazy<Json>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val numberRegex = Regex("\\d+")
    private var filterState = FilterUpdateState.NONE

    private fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
        val naiveTrustManager =
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) =
                    Unit

                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) =
                    Unit
            }

        val insecureSocketFactory = SSLContext.getInstance("TLSv1.2").apply {
            val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
            init(null, trustAllCerts, SecureRandom())
        }.socketFactory

        sslSocketFactory(insecureSocketFactory, naiveTrustManager)
        hostnameVerifier { _, _ -> true }
        return this
    }

    override val client: OkHttpClient by lazy {
        if (preferences.getBoolean(PREF_KEY_IGNORE_SSL_ERROR, false)) {
            network.client.newBuilder().ignoreAllSSLErrors()
        } else {
            network.client.newBuilder().addInterceptor(::checkSSLErrorInterceptor)
        }.addInterceptor(::updateFiltersInterceptor).build()
    }

    private val selectedVideoSource
        get() = preferences.getString(PREF_KEY_VIDEO_SOURCE, DEFAULT_VIDEO_SOURCE)!!.toInt()

    private fun checkSSLErrorInterceptor(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (e: SSLHandshakeException) {
            throw SSLHandshakeException("SSL证书验证异常，可以尝试在设置中忽略SSL验证问题。")
        }
    }

    private fun updateFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterState == FilterUpdateState.NONE) {
            updateFilter()
        }
        return chain.proceed(chain.request())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            description = doc.select("#height_limit.text").text()
            title = doc.select(".slide-info-title").text()
            author = doc.select(".slide-info:contains(导演 :)").text().removePrefix("导演 :")
                .removeSuffix(",")
            artist = doc.select(".slide-info:contains(演员 :)").text().removePrefix("演员 :")
                .removeSuffix(",")
            genre = doc.select(".slide-info:contains(类型 :)").text().removePrefix("类型 :")
                .removeSuffix(",").replace(",", ", ")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val result = jsoup.select("ul.anthology-list-play.size")
        val episodeList = if (result.size > selectedVideoSource) {
            result[selectedVideoSource]
        } else {
            result[0]
        }.select("li > a")
        return episodeList.map {
            SEpisode.create().apply {
                name = it.text()
                url = it.attr("href")
            }
        }.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val requestUrl = response.request.url
        val currentPath = requestUrl.encodedPath
        val currentEpisodePathName = response.request.url.pathSegments.last()
        val document = response.asJsoup()
        val videoUrl = findVideoUrl(document)
        val allEpisodeElements =
            document.select(".player-anthology .anthology-list .anthology-list-box")
                .map { element ->
                    element.select(".anthology-list-play li a")
                }
        val currentEpisodeName = allEpisodeElements.firstNotNullOfOrNull { elements ->
            elements.firstOrNull { it.attr("href") == currentPath }?.select("span")?.text()
        }
        val targetEpisodeNumber =
            currentEpisodeName?.let { numberRegex.find(it)?.value?.toIntOrNull() } ?: -1
        val sourceList = allEpisodeElements.map { elements ->
            elements.findSourceOrNull { name, _ -> name == currentEpisodeName }
                ?: elements.findSourceOrNull { name, _ -> numberRegex.find(name)?.value?.toIntOrNull() == targetEpisodeNumber }
                ?: elements.findSourceOrNull { _, url -> url.endsWith(currentEpisodePathName) }
        }
        val sourceNameList = document.select(".anthology-tab .swiper-wrapper a").map {
            it.ownText().trim()
        }
        return sourceList.zip(sourceNameList) { source, name ->
            if (source == null) {
                Video("", "", null)
            } else if (source.second.endsWith(currentPath)) {
                Video("$baseUrl${source.second}", "$name-${source.first}", videoUrl = videoUrl)
            } else {
                Video("$baseUrl${source.second}", "$name-${source.first}", videoUrl = null)
            }
        }.filter { it.quality.isNotEmpty() }.sortedByDescending { it.videoUrl != null }
    }

    private fun Elements.findSourceOrNull(predicate: (name: String, url: String) -> Boolean): Pair<String, String>? {
        return firstNotNullOfOrNull {
            val name = it.selectFirst("span")?.text() ?: ""
            val url = it.attr("href")
            if (predicate(name, url)) {
                name to url
            } else {
                null
            }
        }
    }

    override fun videoUrlParse(response: Response): String {
        return findVideoUrl(response.asJsoup())
    }

    private fun findVideoUrl(document: Document): String {
        val script = document.select("script:containsData(player_aaaa)").first()!!.data()
        val info = script.substringAfter("player_aaaa=").let { json.parseToJsonElement(it) }
        return info.jsonObject["url"]!!.jsonPrimitive.content
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return vodListToAnimePageList(response)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage {
        return vodListToAnimePageList(response)
    }

    override fun popularAnimeRequest(page: Int): Request =
        searchAnimeRequest(page, "", AnimeFilterList(SortFilter().apply { state = 1 }))

    private fun vodListToAnimePageList(response: Response): AnimesPage {
        val vodResponse = json.decodeFromString<VodResponse>(response.body.string())
        val animeList = vodResponse.list.map {
            SAnime.create().apply {
                url = "/bangumi/${it.vodId}.html"
                thumbnail_url = it.vodPicThumb.ifEmpty { it.vodPic }
                title = it.vodName
                author = it.vodActor.replace(",,,", "")
                description = it.vodBlurb
                genre = it.vodClass.replace(",", ", ")
            }
        }
        return AnimesPage(
            animeList,
            animeList.isNotEmpty() && vodResponse.page * vodResponse.limit < vodResponse.total,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.toString().contains("api/vod")) {
            return vodListToAnimePageList(response)
        }
        val jsoup = response.asJsoup()
        val items = jsoup.select("div.search-list")
        val animeList = items.map { item ->
            SAnime.create().apply {
                title = item.select("div.detail-info > a").text()
                url = item.select("div.detail-info > a").attr("href")
                thumbnail_url =
                    item.select("div.detail-pic img[data-src]").attr("data-src")
            }
        }
        val tip = jsoup.select("div.pages div.page-tip").text()
        return AnimesPage(animeList, tip.isNotEmpty() && hasMorePage(tip))
    }

    private fun hasMorePage(tip: String): Boolean {
        val pageIndicator = tip.substringAfter("当前").substringBefore("页")
        val numbers = pageIndicator.split("/")
        return numbers.size == 2 && numbers[0] != numbers[1]
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun updateFilter() {
        filterState = FilterUpdateState.UPDATING
        val handler = CoroutineExceptionHandler { _, _ ->
            filterState = FilterUpdateState.FAILED
        }
        GlobalScope.launch(Dispatchers.IO + handler) {
            val jsoup = client.newCall(GET("$baseUrl/show/1/html")).awaitSuccess().asJsoup()
            // update class and year filter type
            val classList = jsoup.select("li[data-type=class]").eachAttr("data-val")
            val yearList = jsoup.select("li[data-type=year]").eachAttr("data-val")
            preferences.edit()
                .putString(PREF_KEY_FILTER_CLASS, classList.joinToString())
                .putString(PREF_KEY_FILTER_YEAR, yearList.joinToString())
                .apply()
            filterState = FilterUpdateState.UPDATED
        }
    }

    private fun SharedPreferences.createTagFilter(
        key: String,
        block: (tags: Array<String>) -> TagFilter?,
    ): TagFilter? {
        val savedTags = getString(key, "")!!
        if (savedTags.isBlank()) {
            return block(emptyArray())
        }
        val tags = savedTags.split(", ").toMutableList()
        if (tags[0].isBlank()) {
            tags[0] = "全部"
        }
        return block(tags.toTypedArray())
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            listOfNotNull(
                AnimeFilter.Header("以下筛选对搜索结果无效"),
                TypeFilter(),
                preferences.createTagFilter(PREF_KEY_FILTER_CLASS) {
                    if (it.isEmpty()) {
                        ClassFilter()
                    } else {
                        ClassFilter(it)
                    }
                },
                preferences.createTagFilter(PREF_KEY_FILTER_YEAR) {
                    if (it.isEmpty()) {
                        null
                    } else {
                        YearFilter(it)
                    }
                },
                VersionFilter(),
                LetterFilter(),
                SortFilter(),
            ),
        )
    }

    private fun doSearch(page: Int, query: String): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        if (page <= 1) {
            url.addPathSegment("search.html").addQueryParameter("wd", query)
        } else {
            url.addPathSegments("search/wd/").addPathSegment(query)
                .addPathSegments("page/$page.html")
        }
        return GET(url.build())
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return doSearch(page, query)
        }
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("index.php/api/vod").build()
        val time = System.currentTimeMillis() / 1000
        val formBody =
            MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("page", "$page")
                .addFormDataPart("time", "$time").addFormDataPart("key", generateKey(time))
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> formBody.addFormDataPart("type", filter.selected)
                is ClassFilter -> formBody.addFormDataPart("class", filter.selected)
                is YearFilter -> formBody.addFormDataPart("year", filter.selected)
                is VersionFilter -> formBody.addFormDataPart("version", filter.selected)
                is LetterFilter -> formBody.addFormDataPart("letter", filter.selected)
                is SortFilter -> formBody.addFormDataPart("by", filter.selected)
                else -> {}
            }
        }
        if (filters.filterIsInstance<TypeFilter>().isEmpty()) {
            formBody.addFormDataPart("type", "1")
        }
        return POST(url.toString(), body = formBody.build())
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(
                ListPreference(screen.context).apply {
                    key = PREF_KEY_VIDEO_SOURCE
                    title = "请设置首选视频源线路"
                    entries = arrayOf("主线-1", "主线-2", "备用-1")
                    entryValues = arrayOf("0", "1", "2")
                    setDefaultValue(DEFAULT_VIDEO_SOURCE)
                    summary = "当前选择：${entries[selectedVideoSource]}"
                    setOnPreferenceChangeListener { _, newValue ->
                        summary = "当前选择：${entries[(newValue as String).toInt()]}"
                        true
                    }
                },
            )
            addPreference(
                SwitchPreferenceCompat(screen.context).apply {
                    key = PREF_KEY_IGNORE_SSL_ERROR
                    title = "忽略SSL证书校验"
                    setDefaultValue(false)
                    setOnPreferenceChangeListener { _, _ ->
                        Toast.makeText(screen.context, "重启应用后生效", Toast.LENGTH_SHORT).show()
                        true
                    }
                },
            )
        }
    }

    companion object {
        const val PREF_KEY_VIDEO_SOURCE = "PREF_KEY_VIDEO_SOURCE"
        const val PREF_KEY_IGNORE_SSL_ERROR = "PREF_KEY_IGNORE_SSL_ERROR"

        const val PREF_KEY_FILTER_CLASS = "PREF_KEY_FILTER_CLASS"
        const val PREF_KEY_FILTER_YEAR = "PREF_KEY_FILTER_YEAR"

        const val DEFAULT_VIDEO_SOURCE = "0"
    }
}
