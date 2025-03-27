package eu.kanade.tachiyomi.animeextension.all.newgrounds

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import tryParse
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

class NewGrounds : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val lang = "all"
    override val baseUrl = "https://www.newgrounds.com"
    override val name = "Newgrounds"
    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val videoListHeaders by lazy {
        headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "https://www.newgrounds.com")
            .build()
    }

    private fun creatorUrl(username: String) = baseUrl.replaceFirst("www", username)

    private fun animeFromElement(element: Element, section: String): SAnime {
        return if (section == PREF_SECTIONS["Your Feed"]) {
            SAnime.create().apply {
                title = element.selectFirst(".detail-title h4")!!.text()
                author = element.selectFirst(".detail-title strong")?.text()
                description = element.selectFirst(".detail-description")?.text()
                thumbnail_url = element.selectFirst(".item-icon img")?.absUrl("src")
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
            }
        } else {
            SAnime.create().apply {
                title = element.selectFirst(".card-title h4")!!.text()
                author = element.selectFirst(".card-title span")?.text()?.replace("By ", "")
                description = element.selectFirst("a")?.attr("title")
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
            }
        }
    }

    // Latest

    private val latestSection = preferences.getString("LATEST", PREF_SECTIONS["Latest"])!!

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$latestSection", headers)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesSelector(): String {
        return if (latestSection == PREF_SECTIONS["Your Feed"]) {
            "a.item-portalsubmission"
        } else {
            "a.inline-card-portalsubmission"
        }
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return animeFromElement(element, latestSection)
    }

//    override suspend fun getLatestUpdates(page: Int): AnimesPage {
//        val data = client.newCall(GET("$baseUrl")).awaitSuccess()
//        val document = data.parseAs<Document>()
//
//        val animeList = document.select(latestUpdatesSelector()).map { element ->
//            animeFromElement(element, latestSection)
//        }
//
//        return AnimesPage(animeList, hasNextPage = true)
//
//    }

    // Browse

    private val popularSection = preferences.getString("POPULAR", PREF_SECTIONS["Popular"])!!

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/$popularSection", headers)

    override fun popularAnimeNextPageSelector(): String? = null

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val offset = 20
        //TODO
        return super.getPopularAnime(page)
    }

    override fun popularAnimeSelector(): String {
        return if (latestSection == PREF_SECTIONS["Your Feed"]) {
            "a.item-portalsubmission"
        } else {
            "a.inline-card-portalsubmission"
        }
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        return animeFromElement(element, popularSection)
    }

    // Search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        TODO("Not yet implemented")
    }

    override fun searchAnimeNextPageSelector(): String? {
        TODO("Not yet implemented")
    }

    override fun searchAnimeSelector(): String {
        TODO("Not yet implemented")
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        TODO("Not yet implemented")
    }

    // Etc.

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h2[itemprop=\"name\"]")!!.text()
            description = document.selectFirst("meta[itemprop=\"description\"]")?.attr("content")
            author = document.selectFirst(".authorlinks > div:first-of-type .item-details-main")?.text()
            artist = document.select(".authorlinks > div:not(:first-of-type) .item-details-main").joinToString {
                it.text()
            }
            thumbnail_url = document.selectFirst("meta[itemprop=\"thumbnailUrl\"]")?.absUrl("content")
            genre = document.select(".tags li a").joinToString { it.text() }
            status = SAnime.COMPLETED
            setUrlWithoutDomain(document.selectFirst("meta[itemprop=\"url\"]")!!.absUrl("content"))
        }
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException("Not Used")

    private fun extractEpisodeIdFromScript(element: Element?): String? {
        val regex = """data-movie-id=\\\"(\d+)\\\""""
        val scriptContent = element!!.html().toString()

        val pattern = Pattern.compile(regex, Pattern.MULTILINE)
        val matcher = pattern.matcher(scriptContent)

        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeIdScript = document.selectFirst("#ng-global-video-player script")
        val episodeId = extractEpisodeIdFromScript(episodeIdScript)
        val dateString = document.selectFirst("#sidestats  > dl:nth-of-type(2) > dd:first-of-type")?.text()

        return listOf(
            SEpisode.create().apply {
                episode_number = 1f
                date_upload = dateFormat.tryParse(dateString)
                name = document.selectFirst("meta[name=\"title\"]")!!.attr("content")
                setUrlWithoutDomain("$baseUrl/portal/video/$episodeId")
            },
        )
    }

    override fun videoListRequest(episode: SEpisode): Request {
        Log.d("Tst", videoListHeaders.toString())
        return GET("$baseUrl${episode.url}", videoListHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        Log.d("Tst", response.toString())
        val responseBody = response.body.string()
        val json = JSONObject(responseBody)
        val sources = json.getJSONObject("sources")

        val videos = mutableListOf<Video>()

        for (quality in sources.keys()) {
            val qualityArray = sources.getJSONArray(quality)
            for (i in 0 until qualityArray.length()) {
                val videoObject = qualityArray.getJSONObject(i)
                val videoUrl = videoObject.getString("src")

                videos.add(
                    Video(
                        url = videoUrl,
                        quality = quality,
                        videoUrl = videoUrl,
                        headers = headers,
                    ),
                )
            }
        }

        return videos
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not Used")

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not Used")

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not Used")

    // ============================ Preferences =============================
    /*
        According to the labels on the website:
        Featured    -> /movies/featured
        Latest      -> /movies/browse
        Popular     -> /movies/popular
        Your Feed   -> /social/feeds/show/favorite-artists-movies
        Under Judgement -> /movies/browse?interval=all&artist-type=unjudged
     */

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "POPULAR"
            title = "Popular section content"
            entries = PREF_SECTIONS.keys.toTypedArray()
            entryValues = PREF_SECTIONS.values.toTypedArray()
            setDefaultValue(PREF_SECTIONS["Popular"])
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                Toast.makeText(screen.context, "Restart app to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "LATEST"
            title = "Latest section content"
            entries = PREF_SECTIONS.keys.toTypedArray()
            entryValues = PREF_SECTIONS.values.toTypedArray()
            setDefaultValue(PREF_SECTIONS["Latest"])
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private val PREF_SECTIONS = mapOf(
            "Featured" to "movies/featured",
            "Latest" to "movies/browse",
            "Popular" to "movies/popular",
            "Your Feed" to "social/feeds/show/favorite-artists-movies",
            "Under Judgment" to "movies/browse?interval=all&artist-type=unjudged",
        )
    }
}
