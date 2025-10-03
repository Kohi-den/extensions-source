package eu.kanade.tachiyomi.animeextension.fr.frenchanime

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamhubextractor.StreamHubExtractor
import eu.kanade.tachiyomi.lib.streamvidextractor.StreamVidExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.multisrc.datalifeengine.DataLifeEngine
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FrenchAnime : DataLifeEngine(
    "French Anime",
    "https://french-anime.com",
    "fr",
) {

    override val categories = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Animes VF", "/animes-vf/"),
        Pair("Animes VOSTFR", "/animes-vostfr/"),
        Pair("Films VF et VOSTFR", "/films-vf-vostfr/"),
    )

    override val genres = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Action", "/genre/action/"),
        Pair("Aventure", "/genre/aventure/"),
        Pair("Arts martiaux", "/genre/arts-martiaux/"),
        Pair("Combat", "/genre/combat/"),
        Pair("Comédie", "/genre/comedie/"),
        Pair("Drame", "/genre/drame/"),
        Pair("Epouvante", "/genre/epouvante/"),
        Pair("Fantastique", "/genre/fantastique/"),
        Pair("Fantasy", "/genre/fantasy/"),
        Pair("Mystère", "/genre/mystere/"),
        Pair("Romance", "/genre/romance/"),
        Pair("Shonen", "/genre/shonen/"),
        Pair("Surnaturel", "/genre/surnaturel/"),
        Pair("Sci-Fi", "/genre/sci-fi/"),
        Pair("School life", "/genre/school-life/"),
        Pair("Ninja", "/genre/ninja/"),
        Pair("Seinen", "/genre/seinen/"),
        Pair("Horreur", "/genre/horreur/"),
        Pair("Tranche de vie", "/genre/tranchedevie/"),
        Pair("Psychologique", "/genre/psychologique/"),
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes-vostfr/page/$page/")

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val epsData = document.selectFirst("div.eps")?.text() ?: return emptyList()
        epsData.split(" ").filter { it.isNotBlank() }.forEach {
            val data = it.split("!", limit = 2)
            val episode = SEpisode.create()
            episode.episode_number = data[0].toFloatOrNull() ?: 0F
            episode.name = "Episode ${data[0]}"
            episode.url = data[1]
            episodeList.add(episode)
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val streamVidExtractor by lazy { StreamVidExtractor(client) }
    private val vidoExtractor by lazy { VidoExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamHubExtractor by lazy { StreamHubExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val list = episode.url.split(",").filter { it.isNotBlank() }.parallelCatchingFlatMap {
            with(it) {
                when {
                    contains("dood") ||
                        contains("d0000d") -> doodExtractor.videosFromUrl(this)
                    contains("upstream") -> upstreamExtractor.videosFromUrl(this)
                    contains("vudeo") -> vudeoExtractor.videosFromUrl(this)
                    contains("uqload") -> uqloadExtractor.videosFromUrl(this)
                    contains("guccihide") ||
                        contains("streamhide") -> streamHideVidExtractor.videosFromUrl(this)
                    contains("streamvid") -> streamVidExtractor.videosFromUrl(this)
                    contains("vido") -> vidoExtractor.videosFromUrl(this)
                    contains("sibnet") -> sibnetExtractor.videosFromUrl(this)
                    contains("ok.ru") -> okruExtractor.videosFromUrl(this)
                    contains("streamhub.gg") -> streamHubExtractor.videosFromUrl(this)
                    contains("vidmoly") -> vidmolyExtractor.videosFromUrl(this)
                    contains("voe.sx") -> voeExtractor.videosFromUrl(this)
                    else -> emptyList()
                }
            }
        }.sort()
        return list
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
