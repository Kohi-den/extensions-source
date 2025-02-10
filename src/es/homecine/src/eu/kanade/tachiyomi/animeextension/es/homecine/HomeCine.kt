
package eu.kanade.tachiyomi.animeextension.es.homecine

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
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HomeCine : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "HomeCine"

    override val baseUrl = "https://homecine.cc"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "YourUpload"
        private val SERVER_LIST = arrayOf(
            "YourUpload",
            "BurstCloud",
            "Voe",
            "StreamWish",
            "Mp4Upload",
            "Fastream",
            "Upstream",
            "Filemoon",
        )
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/cartelera-series/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".post")
        val nextPage = document.select(".nav-links .current ~ a").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst(".lnk-blk")?.attr("abs:href") ?: "")
                title = element.selectFirst(".entry-header .entry-title")?.text() ?: ""
                description = element.select(".entry-content p").text() ?: ""
                thumbnail_url = element.selectFirst(".post-thumbnail figure img")?.let { getImageUrl(it) }
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/?s=$query", headers)

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("aside .entry-header .entry-title")?.text() ?: ""
            description = document.select("aside .description p:not([class])").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".post-thumbnail img")?.let { getImageUrl(it)?.replace("/w185/", "/w500/") }
            genre = document.select(".genres a").joinToString { it.text() }
            status = if (document.location().contains("pelicula")) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    private fun getImageUrl(element: Element): String? {
        return when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("src") -> element.attr("abs:src")
            else -> null
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        return if (referer.contains("pelicula")) {
            listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "Película"
                    setUrlWithoutDomain(referer)
                },
            )
        } else {
            val chunkSize = Runtime.getRuntime().availableProcessors()
            document.select(".sel-temp a")
                .sortedByDescending { it.attr("data-season") }
                .chunked(chunkSize).flatMap { chunk ->
                    chunk.parallelCatchingFlatMapBlocking { season ->
                        getDetailSeason(season, referer)
                    }
                }.sortedByDescending {
                    it.name.substringBeforeLast("-")
                }
        }
    }

    private fun getDetailSeason(element: Element, referer: String): List<SEpisode> {
        return try {
            val post = element.attr("data-post")
            val season = element.attr("data-season")
            val formBody = FormBody.Builder()
                .add("action", "action_select_season")
                .add("season", season)
                .add("post", post)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/wp-admin/admin-ajax.php")
                .post(formBody)
                .header("Origin", baseUrl)
                .header("Referer", referer)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()
            val detail = client.newCall(request).execute().asJsoup()

            detail.select(".post").reversed().mapIndexed { idx, it ->
                val epNumber = try {
                    it.select(".entry-header .num-epi").text().substringAfter("x").substringBefore("–").trim()
                } catch (_: Exception) { "${idx + 1}" }

                SEpisode.create().apply {
                    setUrlWithoutDomain(it.select("a").attr("abs:href"))
                    name = "T$season - Episodio $epNumber"
                    episode_number = epNumber.toFloat()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select(".aa-tbs-video a").forEach {
            val prefix = runCatching {
                val lang = it.select(".server").text().lowercase()
                when {
                    lang.contains("latino") -> "[LAT]"
                    lang.contains("castellano") -> "[CAST]"
                    lang.contains("sub") || lang.contains("vose") -> "[SUB]"
                    else -> ""
                }
            }.getOrDefault("")

            val ide = it.attr("href")
            var src = document.select("$ide iframe").attr("data-src").replace("#038;", "&").replace("&amp;", "")
            try {
                if (src.contains("home")) {
                    src = client.newCall(GET(src)).execute().asJsoup().selectFirst("iframe")?.attr("src") ?: ""
                }

                if (src.contains("fastream")) {
                    if (src.contains("emb.html")) {
                        val key = src.split("/").last()
                        src = "https://fastream.to/embed-$key.html"
                    }
                    FastreamExtractor(client, headers).videosFromUrl(src, needsSleep = false, prefix = "$prefix Fastream:").also(videoList::addAll)
                }
                if (src.contains("upstream")) {
                    UpstreamExtractor(client).videosFromUrl(src, prefix = "$prefix ").let { videoList.addAll(it) }
                }
                if (src.contains("yourupload")) {
                    YourUploadExtractor(client).videoFromUrl(src, headers, prefix = "$prefix ").let { videoList.addAll(it) }
                }
                if (src.contains("voe")) {
                    VoeExtractor(client).videosFromUrl(src, prefix = "$prefix ").also(videoList::addAll)
                }
                if (src.contains("wishembed") || src.contains("streamwish") || src.contains("wish")) {
                    StreamWishExtractor(client, headers).videosFromUrl(src) { "$prefix StreamWish:$it" }.also(videoList::addAll)
                }
                if (src.contains("mp4upload")) {
                    Mp4uploadExtractor(client).videosFromUrl(src, headers, prefix = "$prefix ").let { videoList.addAll(it) }
                }
                if (src.contains("burst")) {
                    BurstCloudExtractor(client).videoFromUrl(src, headers = headers, prefix = "$prefix ").let { videoList.addAll(it) }
                }
                if (src.contains("filemoon") || src.contains("moonplayer")) {
                    FilemoonExtractor(client).videosFromUrl(src, headers = headers, prefix = "$prefix Filemoon:").let { videoList.addAll(it) }
                }
            } catch (_: Exception) {}
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
