package eu.kanade.tachiyomi.animeextension.pt.animesgratis

import eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors.NoaExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors.RuplayExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Q1N : DooPlay(
    "pt-BR",
    "Q1N",
    "https://q1n.net",
) {

    override val id: Long = 2969482460524685571L

    override val dateFormatter by lazy {
        SimpleDateFormat("dd/MM/yy", Locale("pt", "BR"))
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.items.featured article div.poster"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/a/", headers)

    // =============================== Latest ===============================
    override val latestUpdatesPath = "e"

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div.result-item article div.thumbnail > a"
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            doc.selectFirst("div#info")?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.selectFirst("span.se-t")?.text()
        return season.select(episodeListSelector()).mapNotNull { element ->
            runCatching {
                if (seasonName.isNullOrBlank()) {
                    episodeFromElement(element)
                } else {
                    episodeFromElement(element, seasonName)
                }
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }

    override fun episodeListSelector() = "ul.episodios > li > div.episodiotitle > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.text().also {
            name = it
            episode_number = it.substringAfter(" ").toFloatOrNull() ?: 0F
        }
        date_upload = element.parent()?.selectFirst(episodeDateSelector)
            ?.text()
            ?.toDate() ?: 0L
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    override val prefQualityValues = arrayOf("360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    private val ruplayExtractor by lazy { RuplayExtractor(client) }
    private val noaExtractor by lazy { NoaExtractor(client, headers) }
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    private fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text().lowercase()
        val url = getPlayerUrl(player) ?: return emptyList()
        return when {
            "ruplay" in name -> ruplayExtractor.videosFromUrl(url)
            "streamwish" in name -> streamWishExtractor.videosFromUrl(url)
            "filemoon" in name -> filemoonExtractor.videosFromUrl(url)
            "mixdrop" in name -> mixDropExtractor.videoFromUrl(url)
            "streamtape" in name -> streamTapeExtractor.videosFromUrl(url)
            "noa" in name -> noaExtractor.videosFromUrl(url)
            "mdplayer" in name -> noaExtractor.videosFromUrl(url, "MDPLAYER")
            "/player/" in url -> bloggerExtractor.videosFromUrl(url, headers)
            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String? {
        val playerId = player.attr("data-nume")
        val iframe = player.root().selectFirst("div#source-player-$playerId iframe")

        return iframe?.tryGetAttr("data-litespeed-src", "src")?.takeIf(String::isNotBlank)
            ?.let {
                when {
                    it.contains("/aviso/") ->
                        it.toHttpUrl().queryParameter("url")

                    else -> it
                }
            }
    }

    // ============================== Filters ===============================
    override fun genresListRequest() = popularAnimeRequest(0)
    override fun genresListSelector() = "div.filter > div.select:first-child option:not([disabled])"

    override fun genresListParse(document: Document): Array<Pair<String, String>> {
        val items = document.select(genresListSelector()).map {
            val name = it.text()
            val value = it.attr("value").substringAfter("$baseUrl/")
            Pair(name, value)
        }.toTypedArray()

        return if (items.isEmpty()) {
            items
        } else {
            arrayOf(Pair(selectFilterText, "")) + items
        }
    }

    // ============================= Utilities ==============================
    override fun getRealAnimeDoc(document: Document): Document {
        if (!document.location().contains("/e/")) return document

        return document.selectFirst("div.pag_episodes div.item > a:has(i.fa-th)")?.let {
            client.newCall(GET(it.attr("href"), headers)).execute()
                .asJsoup()
        } ?: document
    }

    private fun Element.tryGetAttr(vararg attributeKeys: String): String? {
        val attributeKey = attributeKeys.first { hasAttr(it) }
        return attributeKey?.let { attr(attributeKey) }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
    }
}
