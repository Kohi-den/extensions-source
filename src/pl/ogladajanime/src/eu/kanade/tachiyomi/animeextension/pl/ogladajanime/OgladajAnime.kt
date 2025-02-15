package eu.kanade.tachiyomi.animeextension.pl.ogladajanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class OgladajAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "OgladajAnime"

    override val baseUrl = "https://ogladajanime.pl"

    override val lang = "pl"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiHeaders = Headers.Builder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept-Language", "pl,en-US;q=0.7,en;q=0.3")
        .set("Host", baseUrl.toHttpUrl().host)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/search/page/$page", apiHeaders)
    }
    override fun popularAnimeSelector(): String = "div#anime_main div.card.bg-white"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            thumbnail_url = element.selectFirst("img")?.attr("data-srcset")
            title = element.selectFirst("h5.card-title > a")!!.text()
        }
    }
    override fun popularAnimeNextPageSelector(): String = "section:has(div#anime_main)" // To nie działa zostało to tylko dlatego by ładowało ale na końcu niestety wyskakuje ze "nie znaleziono" i tak zostaje zamiast zniknać możliwe ze zle fetchuje.

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/search/new/$page", apiHeaders)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/name/$query", apiHeaders)

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            // status = document.selectFirst("div.toggle-content > ul > li:contains(Status)")?.let { parseStatus(it.text()) } ?: SAnime.UNKNOWN // Nie pamietam kiedyś sie to naprawi.
            description = document.selectFirst("p#animeDesc")?.text()
            genre = document.select("div.row > div.col-12 > span.badge[href^=/search/name/]").joinToString(", ") {
                it.text()
            }
            author = document.select("div.row > div.col-12:contains(Studio:) > span.badge[href=#]").joinToString(", ") {
                it.text()
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val url = baseUrl + anime.url
        return GET(url, apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector(): String = "ul#ep_list > li:has(div > img)"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val episodeNumber = element.attr("value").toFloatOrNull() ?: 0f
        val episodeText = element.select("div > div > p").text()

        val episodeImg = element.select("div > img").attr("alt").uppercase()

        if (episodeText.isNotEmpty()) {
            episode.name = if (episodeImg == "PL") {
                "${episodeNumber.toInt()} $episodeText"
            } else {
                "${episodeNumber.toInt()} [$episodeImg] $episodeText"
            }
        } else {
            episode.name = if (episodeImg == "PL") {
                "${episodeNumber.toInt()} Odcinek"
            } else {
                "${episodeNumber.toInt()} [$episodeImg] Odcinek"
            }
        }

        episode.episode_number = episodeNumber
        episode.url = element.attr("ep_id")

        return episode
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl:8443/Player/${episode.url}", apiHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val players = json.decodeFromString<List<ApiPlayer>>(response.body.string())

        return players.map { player ->
            val host = Regex("""https?://(?:www\.)?([^/]+)""")
                .find(player.mainUrl)
                ?.groupValues
                ?.get(1)
                ?: "unknown-host"
            Video(
                url = player.mainUrl,
                quality = if (player.extra == "inv") "[Odwrócone Kolory] $host - ${player.res}p" else "$host - ${player.res}p",
                videoUrl = player.src,
                headers = null,
            )
        }
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    @Serializable
    data class ApiPlayer(
        val mainUrl: String,
        val label: String,
        val res: Int,
        val src: String,
        val type: String,
        val extra: String,
        val startTime: Int,
        val endTime: Int,
        val ageValidation: Boolean,
        val youtube: String? = null,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferowana jakość"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
    }
}
