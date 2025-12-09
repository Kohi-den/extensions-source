package eu.kanade.tachiyomi.animeextension.zh.cycity

import android.app.Application
import android.util.Base64
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
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Calendar

class Cycity : AnimeHttpSource(), ConfigurableAnimeSource {
    override val baseUrl = "https://www.cycani.org/search.html?wd="
    override val name = "次元城动漫"
    override val lang = "zh"
    override val supportsLatest = true

    private val realUrl = "https://www.cycani.org"
    private val apiUrl = "$realUrl/index.php/ds_api"
    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        val VIDEO_URL_REGEX = Regex("\\bplayer_aaaa[^<>]*\"url\": ?\"(.*?)\"[^<>]*\\}")
        val KEY_REGEX = Regex("now_(\\w+)")
        val URL_REGEX = Regex("\"url\": \"([^:]+?)\"")
        val CALENDAR: Calendar = Calendar.getInstance()
        val MD5: MessageDigest = MessageDigest.getInstance("MD5")
        const val PARSE_URL = "https://player.cycanime.com/?url="
        const val POPULAR_PREF = "POPULAR_DISPLAY"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = POPULAR_PREF
            title = "热门动画显示番剧周表"
            summary = "开启后，“热门”内容会显示当天应该更新的动画，但不一定更新"
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    /**
     * JavaScript implementation:
     *
     * function decrypt(src, key1, key2) {
     *     let prefix = new Array(key2.length);
     *     for (let i = 0; i < key2.length; i++) {
     *       prefix[key1[i]] = key2[i];
     *     }
     *     let a = CryptoJS.MD5(prefix.join("") + "YLwJVbXw77pk2eOrAnFdBo2c3mWkLtodMni2wk81GCnP94ZltW").toString(),
     *       key = CryptoJS.enc.Utf8.parse(a.substring(16)),
     *       iv = CryptoJS.enc.Utf8.parse(a.substring(0, 16)),
     *       dec = CryptoJS.AES.decrypt(src, key, {
     *         iv: iv,
     *         mode: CryptoJS.mode.CBC,
     *         padding: CryptoJS.pad.Pkcs7,
     *       });
     *     return dec.toString(CryptoJS.enc.Utf8);
     * }
     */
    private fun decrypt(url: String, k1: String, k2: String): String {
        val prefix = CharArray(10)
        k1.indices.forEach { prefix[k1[it] - '0'] = k2[it] }
        val txt = "${prefix.joinToString("")}YLwJVbXw77pk2eOrAnFdBo2c3mWkLtodMni2wk81GCnP94ZltW"
        val a = MD5.digest(txt.toByteArray()).joinToString("") { "%02x".format(it) }
        val ivBytes = a.substring(0, 16).toByteArray(Charsets.UTF_8)
        val keyBytes = a.substring(16).toByteArray(Charsets.UTF_8)
        return CryptoAES.decrypt(url, keyBytes, ivBytes)
    }

    // https://www.cycani.org/show/20/by/time/class/%E6%BC%AB%E7%94%BB%E6%94%B9/page/2/year/2024.html
    private fun vodListRequest(by: String, page: Int): Request {
        val url = realUrl.toHttpUrl().newBuilder()
            .addPathSegments("show/20/by/$by")
            .addPathSegments("page/$page.html")
        return POST(url.build().toString())
    }

    private fun vodListParse(response: Response) = response.asJsoup().let { doc ->
        val list = doc.select(".public-list-box").map {
            SAnime.create().apply {
                thumbnail_url = it.selectFirst("img")?.attr("data-src")
                it.selectFirst(".public-list-button a")!!.let { item ->
                    title = item.text()
                    setUrlWithoutDomain(item.absUrl("href"))
                }
            }
        }
        val tip = doc.selectFirst(".page-tip")?.text()
        val pages = tip?.substringAfter("当前")?.substringBefore("页")?.split("/")
        AnimesPage(list, pages != null && pages[0] != pages[1])
    }

    private fun weeklyScheduleRequest(): Request {
        val weekday = when (CALENDAR.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "一"
            Calendar.TUESDAY -> "二"
            Calendar.WEDNESDAY -> "三"
            Calendar.THURSDAY -> "四"
            Calendar.FRIDAY -> "五"
            Calendar.SATURDAY -> "六"
            Calendar.SUNDAY -> "日"
            else -> ""
        }
        val url = "$apiUrl/weekday".toHttpUrl().newBuilder()
        return POST(url.addQueryParameter("weekday", weekday).build().toString())
    }

    private fun weeklyScheduleParse(response: Response): AnimesPage {
        val data = response.parseAs<VodResponse>()
        return AnimesPage(data.list.map(VodInfo::toSAnime), false)
    }

    // Latest Updates ==============================================================================

    override fun latestUpdatesRequest(page: Int) = vodListRequest("time", page)

    override fun latestUpdatesParse(response: Response) = vodListParse(response)

    // Popular Anime ===============================================================================

    override fun popularAnimeRequest(page: Int): Request {
        val switch = preferences.getBoolean(POPULAR_PREF, false)
        return if (switch) weeklyScheduleRequest() else vodListRequest("hits", page)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        if (response.header("Content-Type")?.startsWith("text/html") == true) {
            return vodListParse(response)
        }
        return weeklyScheduleParse(response)
    }

    // Search Anime ================================================================================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("筛选条件（关键字搜索时无效）"),
        TypeFilter(),
        ClassFilter(),
        YearFilter(),
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = realUrl.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            url.addPathSegments("search/wd/$query")
        } else {
            url.addPathSegments("show/${filters[1]}")
            if (filters[2].toString() != "全部") url.addPathSegments("class/${filters[2]}")
            if (filters[3].toString() != "全部") url.addPathSegments("year/${filters[3]}")
        }
        url.addPathSegments("page/$page.html")
        return GET(url.build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.pathSegments.contains("search")) {
            val document = response.asJsoup()
            document.selectFirst(".ft6")?.let { throw Exception("请在 WebView 中输入验证码") }
            val items = document.select(".search-list")
            val animeList = items.map { item ->
                SAnime.create().apply {
                    setUrlWithoutDomain(item.select(".detail-info > a").attr("href"))
                    item.selectFirst(".detail-pic img[data-src]")?.let {
                        title = it.attr("alt")
                        thumbnail_url = it.attr("data-src")
                    }
                }
            }
            val tip = document.selectFirst(".page-tip")?.text()
            val pages = tip?.substringAfter("当前")?.substringBefore("页")?.split("/")
            return AnimesPage(animeList, pages != null && pages[0] != pages[1])
        }
        return vodListParse(response)
    }

    // Anime Details ===============================================================================

    override fun getAnimeUrl(anime: SAnime) = realUrl + anime.url

    override fun animeDetailsRequest(anime: SAnime) = GET(getAnimeUrl(anime))

    override fun animeDetailsParse(response: Response) = response.asJsoup().let { doc ->
        val infos = doc.select(".slide-info")
        val remark = doc.select(".slide-info-remarks").first()?.text()
        SAnime.create().apply {
            title = doc.selectFirst(".slide-info-title")!!.text()
            author = infos.getOrNull(1)?.selectFirst("a")?.text()
            genre = infos.getOrNull(3)?.select("a")?.joinToString { it.text() }
            description = doc.selectFirst("#height_limit.text")?.text()
            status = when {
                remark?.contains("|") == true -> SAnime.ONGOING
                remark == "已完结" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListRequest(anime: SAnime) = GET(getAnimeUrl(anime))

    override fun getEpisodeUrl(episode: SEpisode) = realUrl + episode.url

    override fun episodeListParse(response: Response) = response.asJsoup().let { doc ->
        val hosts = doc.select(".anthology-tab a").map {
            it.text().substringBefore(it.selectFirst("span")?.text() ?: "").trim()
        }
        doc.select(".anthology-list-play").mapIndexed { i, e ->
            e.select("a").map {
                SEpisode.create().apply {
                    setUrlWithoutDomain(it.absUrl("href"))
                    name = it.text()
                    scanlator = hosts[i]
                }
            }
        }.flatten().reversed()
    }

    // Video List ==================================================================================

    override fun videoListRequest(episode: SEpisode) = GET(getEpisodeUrl(episode))

    override fun videoListParse(response: Response) = response.asJsoup().let {
        val origin = VIDEO_URL_REGEX.find(it.select(".player-left").html())!!.groups[1]!!.value
        val base64 = Base64.decode(origin, Base64.DEFAULT).toString(Charsets.UTF_8)
        listOf(Video(URLDecoder.decode(base64, "UTF-8"), "默认", null))
    }

    override fun videoUrlRequest(video: Video) = GET(PARSE_URL + video.url)

    override fun videoUrlParse(response: Response): String {
        val body = response.body.string()
        val matches = KEY_REGEX.findAll(body).toList()
        check(matches.size == 2) { "视频URL解析失败！" }
        val url = URL_REGEX.find(body)!!.groups[1]!!.value
        return decrypt(url, matches[0].groups[1]!!.value, matches[1].groups[1]!!.value)
    }
}
