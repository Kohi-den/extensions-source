package eu.kanade.tachiyomi.animeextension.id.oploverz

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
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
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Oploverz : ConfigurableAnimeSource, AnimeHttpSource() {
    override val name: String = "Oploverz"
    override val baseUrl: String = "https://oploverz.media"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/anime/?page=$page&status=&type=&sub=&order=popular")

    override fun popularAnimeParse(response: Response): AnimesPage =
        getAnimeParse(response, "article[itemscope=itemscope]")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/anime/?page=$page&status=&type=&sub=&order=latest")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        getAnimeParse(response, "article[itemscope=itemscope]")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = OploverzFilters.getSearchParameters(filters)
        return GET("$baseUrl/page/$page/?s=$query${params.filter}", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        getAnimeParse(response, "article[itemscope=itemscope]")

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = OploverzFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val detail = doc.selectFirst("div.info-content > div.spe")!!
        return SAnime.create().apply {
            author = detail.getInfo("Studio")
            status = parseStatus(detail.getInfo("Status"))
            title = doc.selectFirst("h1.entry-title")!!.text()
            thumbnail_url =
                doc.selectFirst("div.thumb > img")!!
                    .attr("src")
            description =
                doc.select("div.entry-content > p")
                    .joinToString("\n\n") { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select("div.eplister > ul > li").map {
            val episode = it.selectFirst("a")!!
            SEpisode.create().apply {
                setUrlWithoutDomain(episode.attr("href"))
                episode_number = it.selectFirst("div.epl-num")!!.text().toFloatOrNull() ?: 1F
                name = it.selectFirst("div.epl-title")!!.text()
                date_upload = it.selectFirst("div.epl-date")!!.text().toDate()
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videoList = mutableListOf<Video>()

        doc.select("select.mirror > option[value]").forEach { opt ->
            val decoded = if (opt.attr("value").isEmpty()) {
                doc.selectFirst("iframe")!!.attr("src")
            } else {
                Jsoup.parse(
                    String(Base64.decode(opt.attr("value"), Base64.DEFAULT)),
                ).select("iframe").attr("src")
            }

            when {
                decoded.contains("blogger.com") -> {
                    videoList.addAll(getVideosFromEmbed(decoded))
                }
            }
        }

        return videoList.sort()
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareByDescending { it.quality.contains(quality) })
    }

    private fun String?.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(this?.trim() ?: "")?.time }
            .getOrNull() ?: 0L
    }

    private fun Element.getInfo(info: String, cut: Boolean = true): String {
        return selectFirst("span:has(b:contains($info))")!!.text()
            .let {
                when {
                    cut -> it.substringAfter(" ")
                    else -> it
                }.trim()
            }
    }

    private fun getAnimeParse(response: Response, query: String): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(query).map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.selectFirst("a.tip")!!.attr("href"))
                title = it.selectFirst("div.tt > h2")!!.text()
                thumbnail_url = it.selectFirst("div.limit > img")!!.attr("src")
            }
        }
        val hasNextPage = try {
            val pagination = doc.selectFirst("div.pagination")!!
            val totalPage = pagination.selectFirst("span:nth-child(1)")!!.text().split(" ").last()
            val currentPage = pagination.selectFirst("span.page-numbers.current")!!.text()
            currentPage.toInt() < totalPage.toInt()
        } catch (_: Exception) {
            false
        }
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseStatus(status: String?): Int {
        return when (status?.trim()?.lowercase()) {
            "completed" -> SAnime.COMPLETED
            "ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun getVideosFromEmbed(link: String): List<Video> {
        return when {
            "blogger" in link -> {
                client.newCall(GET(link)).execute().body.string().let {
                    val json = JSONObject(it.substringAfter("= ").substringBefore("<"))
                    val streams = json.getJSONArray("streams")
                    val videoList = mutableListOf<Video>()
                    for (i in 0 until streams.length()) {
                        val stream = streams.getJSONObject(i)
                        val url = stream.getString("play_url")
                        val quality = when (stream.getString("format_id")) {
                            "18" -> "Google - 360p"
                            "22" -> "Google - 720p"
                            else -> "Unknown Resolution"
                        }
                        videoList.add(Video(url, quality, url))
                    }
                    videoList
                }
            }

            else -> emptyList()
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            summary = "%s"
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID"))
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("720p", "360p")
    }
}
