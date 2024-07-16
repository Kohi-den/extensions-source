package eu.kanade.tachiyomi.animeextension.pt.animesonlinecc

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesOnlineCC : DooPlay(
    "pt-BR",
    "Animes Online CC",
    "https://animesonlinecc.to",
) {

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.w_item_b > a"

    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() =
        "div.pagination > a.arrow_pag > i.icon-caret-right"

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div#animation-2 > article > div.poster > a"
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

            description = doc.getDescription()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("#playex iframe")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    override val prefQualityValues = arrayOf("360p", "720p")
    override val prefQualityEntries = prefQualityValues

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = player.attr("src")

        val id = player.parent()!!.attr("id")
        var language =
            player.ownerDocument()!!
                .selectFirst("a.options[href=\"#$id\"]")
                ?.text()
                ?.trim().takeIf {
                    it?.lowercase() == "legendado" || it?.lowercase() == "dublado"
                } ?: ""

        return when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers, language)
            else -> emptyList()
        }
    }

    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/generos/", headers)
    override fun genresListSelector() = "a.genre-link"

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoLanguagePref = ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_ENTRIES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoLanguagePref)
        super.setupPreferenceScreen(screen)
    }

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.icon-bars"

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.lowercase().contains(language.lowercase()) },
                { it.quality.lowercase().contains(quality.lowercase()) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "Legendado"
        private const val PREF_LANGUAGE_TITLE = "Língua preferida"
        private val PREF_LANGUAGE_VALUES = arrayOf("Legendado", "Dublado")
        private val PREF_LANGUAGE_ENTRIES = PREF_LANGUAGE_VALUES
    }
}
