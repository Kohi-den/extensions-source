package eu.kanade.tachiyomi.animeextension.pt.animesroll

import android.util.Log
import eu.kanade.tachiyomi.animeextension.pt.animesroll.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesROLL : DooPlay(
    "pt-BR",
    "Animes ROLL",
    "https://anroll.tv",
) {

    private val tag by lazy { javaClass.simpleName }

    override val versionId: Int = 2

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.items.featured article div.poster"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/", headers)

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div.result-item article div.thumbnail > a"
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String {
        return select("$additionalInfoSelector p")
            .eachText()
            .joinToString("\n")
    }

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

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    override val prefQualityValues = arrayOf("360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun getPlayerVideos(player: Element): List<Video> {
        val fullName = player.selectFirst("span.title")!!.text()
        val realName = fullName.substringAfter("(").substringBefore(")")
        val name = realName.lowercase()
        val url = getPlayerUrl(player) ?: return emptyList()
        Log.d(tag, "Fetching videos from: $url")

        var videos: List<Video> = when {
            "alibabacdn" in url -> emptyList()

            else -> emptyList()
        }

        if (videos.isEmpty()) {
            Log.d(tag, "Videos are empty, fetching videos from using universal extractor: $url")
            val newHeaders = headers.newBuilder().set("Referer", baseUrl).build()
            videos = universalExtractor.videosFromUrl(url, newHeaders, realName)
        }

        if (videos.isEmpty()) {
            Log.w(tag, "Videos not fount for $url")
        }

        return videos
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return try {
            client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num", headers))
                .execute()
                .use { response ->
                    response.body.string()
                        .substringAfter("\"embed_url\":\"")
                        .substringBefore("\",")
                        .replace("\\", "")
                }
        } catch (e: Exception) {
            ""
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
    private fun Element.tryGetAttr(vararg attributeKeys: String): String? {
        val attributeKey = attributeKeys.first { hasAttr(it) }
        return attr(attributeKey)
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
