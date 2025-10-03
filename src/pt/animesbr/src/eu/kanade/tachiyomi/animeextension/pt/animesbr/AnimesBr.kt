package eu.kanade.tachiyomi.animeextension.pt.animesbr

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesbr.extractors.FourNimesExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesbr.extractors.RuplayExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesBr : DooPlay(
    "pt-BR",
    "Animes BR",
    "https://animesbr.tv",
) {

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.w_item_b > a"

    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesNextPageSelector() = "div.pagination > a.arrow_pag > i.fa-caret-right"

    // =============================== Search ===============================

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun Document.getDescription(): String {
        return select("$additionalInfoSelector p")
            .first { !it.text().contains("Todos os Episódios") }
            ?.let { it.text() + "\n" }
            ?: ""
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
                }.replace("Todos os Episódios", "").trim()
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

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    override val prefQualityValues = arrayOf("360p", "720p")
    override val prefQualityEntries = prefQualityValues

    private val fourNimesExtractor by lazy { FourNimesExtractor(client) }
    private val ruplayExtractor by lazy { RuplayExtractor(client) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client) }

    private fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text()
            .run {
                if (this.lowercase().endsWith("dub")) {
                    "Dublado"
                } else {
                    "Legendado"
                }
            }

        val url = getPlayerUrl(player) ?: return emptyList()

        return when {
            "4nimes.com" in url -> fourNimesExtractor.videosFromUrl(url, "$name - ")
            "4youmovies" in url -> fourNimesExtractor.videosFromUrl(url, "$name - ")
            "vidmoly" in url -> vidMolyExtractor.videosFromUrl(url, "$name - ")
            "/embed/" in url -> ruplayExtractor.videosFromUrl(url, "$name - ")

            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String? {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute().body.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
            .takeIf(String::isNotBlank)
    }

    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/generos/", headers)
    override fun genresListSelector() = "div.wp-content a[href*=generos]"

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
