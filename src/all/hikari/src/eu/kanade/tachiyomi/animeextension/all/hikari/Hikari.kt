package eu.kanade.tachiyomi.animeextension.all.hikari

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.buzzheavierextractor.BuzzheavierExtractor
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.savefileextractor.SavefileExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Hikari : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Hikari"

    private val proxyUrl = "https://hikari.gg/hiki-proxy/extract/"
    private val apiUrl = "https://api.hikari.gg/api"
    override val baseUrl = "https://hikari.gg"

    override val lang = "all"

    override val versionId = 2

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/episode/new/".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "100")
            addQueryParameter("language", "EN")
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<CatalogResponseDto<LatestEpisodeDto>>()
        val preferEnglish = preferences.getTitleLang

        val animeList = data.results.distinctBy { it.uid }.map { it.toSAnime(preferEnglish) }
        return AnimesPage(animeList, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/anime/".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "created_at")
            addQueryParameter("order", "asc")
            addQueryParameter("page", page.toString())
            filters.filterIsInstance<UriFilter>().forEach {
                it.addToUri(this)
            }
            if (query.isNotEmpty()) {
                addQueryParameter("search", query)
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<CatalogResponseDto<AnimeDto>>()
        val preferEnglish = preferences.getTitleLang

        val animeList = data.results.map { it.toSAnime(preferEnglish) }
        val hasNextPage = data.next != null

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(),
        GenreFilter(),
        LanguageFilter(),
    )

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String {
        return "$baseUrl/info/${anime.url}"
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$apiUrl/anime/uid/${anime.url}/", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return response.parseAs<AnimeDto>().toSAnime(preferences.getTitleLang)
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$apiUrl/episode/uid/${anime.url}/", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val guid = response.request.url.pathSegments[3]

        return response.parseAs<List<EpisodeDto>>().map { it.toSEpisode(guid) }.reversed()
    }

    // ============================ Video Links =============================

    private val filemoonExtractor by lazy { FilemoonExtractor(client, preferences) }
    private val savefileExtractor by lazy { SavefileExtractor(client, preferences) }
    private val buzzheavierExtractor by lazy { BuzzheavierExtractor(client, headers) }
    private val chillxExtractor by lazy { ChillxExtractor(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    private fun getEmbedTypeName(type: String): String {
        return when (type) {
            "2" -> "[SUB] "
            "3" -> "[DUB] "
            "4" -> "[MULTI AUDIO] "
            "8" -> "[HARD-SUB] "
            else -> ""
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val (guid, epId) = episode.url.split("-")
        return GET("$apiUrl/embed/$guid/$epId/", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = response.parseAs<List<EmbedDto>>()

        val selectedProviders = preferences.getStringSet(PREF_PROVIDER_KEY, PREF_PROVIDERS_DEFAULT)?.map(String::lowercase)?.toSet() ?: emptySet()

        return data.parallelCatchingFlatMapBlocking { embed ->
            val embedName = embed.embedName.lowercase()

            if (embedName !in selectedProviders) return@parallelCatchingFlatMapBlocking emptyList()

            val prefix = getEmbedTypeName(embed.embedType) + embed.embedName

            when (embedName) {
                "streamwish" -> streamwishExtractor.videosFromUrl(embed.embedFrame, videoNameGen = { "$prefix - $it" })
                "filemoon" -> filemoonExtractor.videosFromUrl(embed.embedFrame, "$prefix - ")
                "sv" -> savefileExtractor.videosFromUrl(embed.embedFrame, "$prefix - ")
                "playerx" -> chillxExtractor.videoFromUrl(embed.embedFrame, "$prefix - ")
                "hiki" -> hikiExtraction(embed.embedFrame, "$prefix - ")
                else -> emptyList()
            }
        }
    }

    private fun hikiExtraction(url: String, prefix: String): List<Video> {
        val hikiMirror = preferences.getString(PREF_HIKI_KEY, PREF_HIKI_DEFAULT)!!

        if (hikiMirror == "hiki") {
            return buzzheavierExtractor.videosFromUrl(url, prefix, proxyUrl)
        }
        val id = url.toHttpUrl().pathSegments[0]
        val videoUrl = "https://$hikiMirror/$id"
        return buzzheavierExtractor.videosFromUrl(videoUrl, prefix)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.startsWith(type) },
                { it.quality.contains(quality) },
                { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                { it.quality.contains(hoster, true) },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================

    companion object {
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private const val PREF_ENGLISH_TITLE_KEY = "preferred_title_lang"
        private const val PREF_ENGLISH_TITLE_DEFAULT = true

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")
        private val PREF_QUALITY_ENTRIES = PREF_QUALITY_VALUES.map {
            "${it}p"
        }.toTypedArray()

        private val TYPE_LIST = arrayOf("[SUB] ", "[DUB] ", "[MULTI AUDIO] ", "[HARD-SUB] ")
        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_TYPE_DEFAULT = ""
        private val PREF_TYPE_VALUES = arrayOf("") + TYPE_LIST
        private val PREF_TYPE_ENTRIES = arrayOf("Any") + TYPE_LIST

        private val HOSTER_LIST = arrayOf("Streamwish", "Filemoon", "SV", "PlayerX", "Hiki")
        private const val PREF_HOSTER_KEY = "pref_hoster"
        private const val PREF_HOSTER_DEFAULT = ""
        private val PREF_HOSTER_VALUES = arrayOf("") + HOSTER_LIST
        private val PREF_HOSTER_ENTRIES = arrayOf("Any") + HOSTER_LIST

        private const val PREF_HIKI_KEY = "preferred_hiki_mirror"
        private const val PREF_HIKI_DEFAULT = "hiki"
        private val PREF_HIKI_VALUES = arrayOf("hiki", "buzzheavier.com", "bzzhr.co", "fuckingfast.net")
        private val PREF_HIKI_ENTRIES = PREF_HIKI_VALUES

        // Provider
        private const val PREF_PROVIDER_KEY = "provider_selection"
        private val PREF_PROVIDERS = arrayOf("Streamwish", "Filemoon", "SV", "PlayerX", "Hiki")

        private val PREF_PROVIDERS_VALUE = arrayOf("streamwish", "filemoon", "sv", "playerx", "hiki")

        private val PREF_DEFAULT_PROVIDERS_VALUE = arrayOf("streamwish", "filemoon", "sv", "playerx", "hiki")

        private val PREF_PROVIDERS_DEFAULT = PREF_DEFAULT_PROVIDERS_VALUE.toSet()
    }

    // ============================== Settings ==============================

    private val SharedPreferences.getTitleLang
        get() = getBoolean(PREF_ENGLISH_TITLE_KEY, PREF_ENGLISH_TITLE_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENGLISH_TITLE_KEY
            title = "Prefer english titles"
            setDefaultValue(PREF_ENGLISH_TITLE_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = "Preferred type"
            entries = PREF_TYPE_ENTRIES
            entryValues = PREF_TYPE_VALUES
            setDefaultValue(PREF_TYPE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Preferred hoster"
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_PROVIDER_KEY
            title = "Enable/Disable Video Providers"
            entries = PREF_PROVIDERS
            entryValues = PREF_PROVIDERS_VALUE
            setDefaultValue(PREF_PROVIDERS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_HIKI_KEY
            title = "Hiki provider mirrors"
            entries = PREF_HIKI_ENTRIES
            entryValues = PREF_HIKI_VALUES
            setDefaultValue(PREF_HIKI_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        FilemoonExtractor.addSubtitlePref(screen)
        SavefileExtractor.addSubtitlePref(screen)
    }
}
