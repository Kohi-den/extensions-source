package eu.kanade.tachiyomi.animeextension.en.myasiantv

import android.app.Application
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MyAsianTv"

class MyAsianTv : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    private lateinit var prefix: String

    override val lang = "en"
    override val supportsLatest = true
    override val name = "MyAsianTV"
    override val baseUrl by lazy {
        val domain = preferences.getString(prefKeyDomain, defaultDomain)!!
        // special case for .rest
        prefix = if (defaultDomain == domain) "drama-8" else "drama"
        "https://$domain"
    }

    override fun popularAnimeSelector() = primaryItemSelector
    override fun latestUpdatesSelector() = primaryItemSelector
    override fun searchAnimeSelector() = primaryItemSelector

    override fun popularAnimeNextPageSelector() = primaryNextSelector
    override fun latestUpdatesNextPageSelector() = primaryNextSelector
    override fun searchAnimeNextPageSelector() = "ul.pagination li.selected + li a"

    override fun episodeListSelector() = "ul.list-episode li a"
    override fun videoListSelector() = "ul.list-server-items li"

    override fun popularAnimeRequest(page: Int) = getDramaList(page, 4)
    override fun latestUpdatesRequest(page: Int) = getDramaList(page, 1)

    private fun getDramaList(page: Int, order: Int) = when (page) {
        1 -> GET("$baseUrl/$prefix/?selOrder=$order")
        else -> GET("$baseUrl/$prefix/page/$page/?selOrder=$order")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        when (page) {
            1 -> GET("$baseUrl/?s=$query")
            else -> GET("$baseUrl/search/$query/page/$page")
        }

    override fun popularAnimeFromElement(element: Element) = createPrimaryItem(element)
    override fun latestUpdatesFromElement(element: Element) = createPrimaryItem(element)
    override fun searchAnimeFromElement(element: Element) = createPrimaryItem(element)

    private fun createPrimaryItem(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("img")?.let {
            thumbnail_url = it.attr("src")
            it.attr("alt")
        } ?: "%08X".format(url.hashCode())
    }

    override fun animeDetailsRequest(anime: SAnime) = GET(baseUrl + anime.url)

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst("div.movie img.poster")?.run {
            title = attr("alt")
            thumbnail_url = absUrl("src")
        }
        document.selectFirst("div.movie")?.run {
            val plot = select("h3:contains(Plot) + div.info p").eachText()
                .joinToString("\n").takeUnless(String::isBlank)
            val source = plot?.substringAfterLast("(Source:", ")")
                ?.substringBefore(")").orEmpty()
            description = plot
            author = source.substringBeforeLast("||", source.substringBeforeLast(";"))
                .substringAfter("=").trim().takeUnless(String::isBlank)
            genre = selectFirst("p:contains(Genre) span").textNotBlank()
            status = selectFirst("p:contains(Status) span").textToStatus()
        }
    }

    private val episodeRegex = """\b(Ep|Episode)\b (\d+)(\.\d+)?""".toRegex()
    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val type = element.parent()?.previousElementSibling().textNotBlank() ?: "RAW"
        val match = episodeRegex.find(element.attr("title"))
        val epNum = when {
            null == match -> "0"
            match.groupValues[3].isEmpty() -> match.groupValues[2]
            else -> "${match.groupValues[2]}${match.groupValues[3]}"
        }
        name = if ("0" == epNum) "$type Movie" else "$type Episode $epNum"
        episode_number = epNum.toFloat()
        date_upload = element.parent()?.nextElementSibling().textToDate()
    }

    override fun videoListParse(response: Response): List<Video> {
        val iframe = response.asJsoup().selectFirst("iframe") ?: return emptyList()
        val dataSrc = iframe.attr("data-src")
            .takeUnless(String::isBlank) ?: return emptyList()
        return dataSrc.getSource().execute()
            .asJsoup().select(videoListSelector())
            .flatMap(::extractVideoList).sort()
    }

    private fun String.getSource() =
        client.newCall(GET(if (startsWith("//")) "https:$this" else this))

    private fun extractVideoList(element: Element) = runCatching {
        val url = element.attr("data-video")
        when (val host = element.attr("data-provider")) {
            "vidmoly" -> vidMolyExtractor.videosFromUrl(url)
            "streamhg" -> streamWishExtractor.videosFromUrl(url, "StreamHQ")
            else -> Video(url, "$host! Copy to Browser", url).let(::listOf)
        }
    }.getOrElse {
        it.printStackTrace()
        emptyList()
    }

    override fun List<Video>.sort() = toMutableList().apply {
        val quality = preferences.getString(prefKeyQuality, defaultQuality)!!
        sortWith(compareByDescending { it.quality.contains(quality) })
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = prefKeyDomain
            title = "Preferred Domain"
            entries = prefDomains
            entryValues = entries
            setDefaultValue(defaultDomain)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val entry = newValue as String
                Toast.makeText(screen.context, "Restart app to apply `$entry`", Toast.LENGTH_LONG)
                    .show()
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = prefKeyQuality
            title = "Preferred Quality"
            entries = prefQualities
            entryValues = entries
            setDefaultValue(defaultQuality)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val entry = newValue as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    private fun Element?.textNotBlank() = this?.ownText()?.takeUnless(String::isBlank)
    private fun Element?.textToStatus() = when (this?.ownText()?.trim()) {
        "Ongoing" -> SAnime.ONGOING
        "Completed" -> SAnime.COMPLETED
        "Upcoming" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
    private fun Element?.textToDate() = with(this?.ownText()) {
        if (isNullOrBlank()) return@with 0L
        runCatching { dateFormat.parse(this)!!.time }.getOrElse {
            System.currentTimeMillis() - when {
                contains(" second") -> substringBefore(' ').toInt().seconds
                contains(" minute") -> substringBefore(' ').toInt().minutes
                contains(" hour") -> substringBefore(' ').toInt().hours
                contains(" day") -> substringBefore(' ').toInt().days
                contains(" week") -> (substringBefore(' ').toInt() * 7).days
                contains(" month") -> (substringBefore(' ').toInt() * 30).days
                contains(" year") -> (substringBefore(' ').toInt() * 365).days
                else -> 0.milliseconds
            }.inWholeMilliseconds
        }
    }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client) }

    private val primaryItemSelector = "ul.items > li > a"
    private val primaryNextSelector = "ul.page-numbers li a.next"

    private val prefKeyDomain = "key_domain"
    private val defaultDomain = "myasiantv.rest"
    private val prefDomains by lazy {
        arrayOf("myasiantv.rest", "myasiantv.com.bz", "myasiantv9.xyz", "myasiantv9.sbs")
    }

    private val prefKeyQuality = "key_quality"
    private val defaultQuality = "720p"
    private val prefQualities by lazy {
        arrayOf("1080p", "720p", "480p", "360p", "240p")
    }
}
