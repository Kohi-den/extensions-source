package eu.kanade.tachiyomi.animeextension.pl.docchi

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
import eu.kanade.tachiyomi.lib.cdaextractor.CdaPlExtractor
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.lycorisextractor.LycorisCafeExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Docchi : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Docchi"

    override val baseUrl = "https://docchi.pl"

    private val baseApiUrl = "https://api.docchi.pl"

    override val lang = "pl"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) =
        GET("$baseApiUrl/v1/series/list?limit=20&before=${(page - 1) * 20}")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeArray: List<ApiList> = json.decodeFromString(response.body.string())
        val entries = animeArray.map { animeDetail ->
            SAnime.create().apply {
                title = animeDetail.title
                url = "$baseUrl/production/as/${animeDetail.slug}"
                thumbnail_url = animeDetail.cover
            }
        }
        val hasNextPage = animeArray.isNotEmpty()

        return AnimesPage(entries, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseApiUrl/v1/series/list?limit=20&before=${(page - 1) * 20}&sort=DESC")

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseApiUrl/v1/series/related/$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val animeArray: List<ApiSearch> = json.decodeFromString(response.body.string())
        val entries = animeArray.map { animeDetail ->
            SAnime.create().apply {
                title = animeDetail.title
                url = "$baseUrl/production/as/${animeDetail.slug}"
                thumbnail_url = animeDetail.cover
            }
        }
        return AnimesPage(entries, false)
    }
    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseApiUrl/v1/episodes/count/${anime.url.substringAfterLast("/")}")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList: List<EpisodeList> = json.decodeFromString(response.body.string())
        return episodeList.map { episode ->
            SEpisode.create().apply {
                name = "${episode.anime_episode_number.toInt()} Odcinek"
                url = "$baseUrl/production/as/${episode.anime_id}/${episode.anime_episode_number}"
                episode_number = episode.anime_episode_number
                // date_upload = episode.created_at.toLong()
            }
        }.reversed()
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseApiUrl/v1/series/find/${anime.url.substringAfterLast("/")}")

    override fun animeDetailsParse(response: Response): SAnime {
        val animeDetail: ApiDetail = json.decodeFromString(response.body.string())

        return SAnime.create().apply {
            title = animeDetail.title
            description = animeDetail.description
            genre = animeDetail.genres.joinToString(", ")
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(
        "$baseApiUrl/v1/episodes/find/${
            episode.url.substringBeforeLast("/").substringAfterLast("/")
        }/${episode.episode_number}",
    )

    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val cdaExtractor by lazy { CdaPlExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val lycorisExtractor by lazy { LycorisCafeExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val googledriveExtractor by lazy { GoogleDriveExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val videolist: List<VideoList> = json.decodeFromString(response.body.string())
        val serverList = videolist.mapNotNull { player ->
            var sub = player.translator_title.uppercase()

            val prefix = if (player.isInverted) {
                "[Odwrócone Kolory] $sub - "
            } else {
                "$sub - "
            }

            val playerName = player.player_hosting.lowercase()

            if (playerName !in listOf(
                    "vk",
                    "cda",
                    "mp4upload",
                    "sibnet",
                    "dailymotion",
                    "dood",
                    "lycoris.cafe",
                    "lulustream",
                    "gdrive",
                    "google drive",
                )
            ) {
                return@mapNotNull null
            }

            Pair(player.player, prefix)
        }
        // Jeśli dodadzą opcje z mozliwością edytowania mpv to zrobić tak ze jak bedą odwrócone kolory to ustawia dane do mkv <3
        return serverList.parallelCatchingFlatMapBlocking { (serverUrl, prefix) ->
            when {
                serverUrl.contains("vk.com") -> {
                    vkExtractor.videosFromUrl(serverUrl, prefix)
                }

                serverUrl.contains("mp4upload") -> {
                    mp4uploadExtractor.videosFromUrl(serverUrl, headers, prefix)
                }

                serverUrl.contains("cda.pl") -> {
                    cdaExtractor.getVideosFromUrl(serverUrl, headers, prefix)
                }

                serverUrl.contains("dailymotion") -> {
                    dailymotionExtractor.videosFromUrl(serverUrl, "$prefix Dailymotion -")
                }

                serverUrl.contains("sibnet.ru") -> {
                    sibnetExtractor.videosFromUrl(serverUrl, prefix)
                }

                serverUrl.contains("dood") -> {
                    doodExtractor.videosFromUrl(serverUrl, "$prefix Dood")
                }

                serverUrl.contains("lycoris.cafe") -> {
                    lycorisExtractor.getVideosFromUrl(serverUrl, headers, prefix)
                }

                serverUrl.contains("luluvdo.com") -> {
                    luluExtractor.videosFromUrl(serverUrl, prefix)
                }

                serverUrl.contains("drive.google.com") -> {
                    val regex = Regex("/d/([a-zA-Z0-9_-]+)")
                    val id = regex.find(serverUrl)?.groupValues?.get(1).toString()
                    googledriveExtractor.videosFromUrl(id, "${prefix}Gdrive -")
                }

                else -> emptyList()
            }
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "cda.pl")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
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
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferowany serwer"
            entries = arrayOf("cda.pl", "Dailymotion", "Mp4upload", "Sibnet", "vk.com")
            entryValues = arrayOf("cda.pl", "Dailymotion", "Mp4upload", "Sibnet", "vk.com")
            setDefaultValue("cda.pl")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
    }

    @Serializable
    data class ApiList(
        val mal_id: Int,
        val adult_content: String,
        val title: String,
        val title_en: String,
        val slug: String,
        val cover: String,
        val genres: List<String>,
        val broadcast_day: String?,
        val aired_from: String?,
        val episodes: Int?,
        val season: String,
        val season_year: Int,
        val series_type: String,
    )

    @Serializable
    data class ApiSearch(
        val mal_id: Int,
        val ani_id: Int?,
        val title: String,
        val title_en: String,
        val slug: String,
        val cover: String,
        val adult_content: String,
        val series_type: String,
        val episodes: Int?,
        val season: String,
        val season_year: Int,
    )

    @Serializable
    data class ApiDetail(
        val id: Int,
        val mal_id: Int,
        val ani_id: Int?,
        val adult_content: String,
        val title: String,
        val title_en: String,
        val slug: String,
        val slug_oa: String?,
        val description: String,
        val cover: String,
        val bg: String?,
        val genres: List<String>,
        val broadcast_day: String?,
        val aired_from: String?,
        val episodes: Int?,
        val season: String,
        val season_year: Int,
        val series_type: String,
        val ads: String?,
        val modified: String?,
    )

    @Serializable
    data class EpisodeList(
        val anime_id: String,
        val anime_episode_number: Float,
        val isInverted: String,
        val created_at: String,
        val bg: String?,
    )

    @Serializable
    data class VideoList(
        val id: Int,
        val anime_id: String,
        val anime_episode_number: Float,
        val player: String,
        val player_hosting: String,
        val created_at: String,
        val translator: Boolean,
        val translator_title: String,
        val translator_url: String?,
        val isInverted: Boolean,
        val bg: String?,
    )
}
