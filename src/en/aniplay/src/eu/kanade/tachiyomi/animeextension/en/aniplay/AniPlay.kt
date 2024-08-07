package eu.kanade.tachiyomi.animeextension.en.aniplay

import android.app.Application
import android.util.Base64
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.multisrc.anilist.AniListAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class AniPlay : AniListAnimeHttpSource(), ConfigurableAnimeSource {
    override val name = "AniPlay"
    override val lang = "en"

    override val baseUrl: String
        get() = "https://${preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)}"

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    /* ================================= AniList configurations ================================= */

    override fun mapAnimeDetailUrl(animeId: Int): String {
        return "$baseUrl/anime/info/$animeId"
    }

    override fun mapAnimeId(animeDetailUrl: String): Int {
        val httpUrl = animeDetailUrl.toHttpUrl()

        return httpUrl.pathSegments[2].toInt()
    }

    override fun getPreferredTitleLanguage(): TitleLanguage {
        val preferredLanguage = preferences.getString(PREF_TITLE_LANGUAGE_KEY, PREF_TITLE_LANGUAGE_DEFAULT)

        return when (preferredLanguage) {
            "romaji" -> TitleLanguage.ROMAJI
            "english" -> TitleLanguage.ENGLISH
            "native" -> TitleLanguage.NATIVE
            else -> TitleLanguage.ROMAJI
        }
    }

    /* ====================================== Episode List ====================================== */

    override fun episodeListRequest(anime: SAnime): Request {
        val httpUrl = anime.url.toHttpUrl()
        val animeId = httpUrl.pathSegments[2]

        return GET("$baseUrl/api/anime/episode/$animeId")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val isMarkFiller = preferences.getBoolean(PREF_MARK_FILLER_EPISODE_KEY, PREF_MARK_FILLER_EPISODE_DEFAULT)
        val episodeListUrl = response.request.url
        val animeId = episodeListUrl.pathSegments[3]
        val providers = response.parseAs<List<EpisodeListResponse>>()
        val episodes = mutableMapOf<Int, EpisodeListResponse.Episode>()
        val episodeExtras = mutableMapOf<Int, List<EpisodeExtra>>()

        providers.forEach { provider ->
            provider.episodes.forEach { episode ->
                if (!episodes.containsKey(episode.number)) {
                    episodes[episode.number] = episode
                }
                val existingEpisodeExtras = episodeExtras.getOrElse(episode.number) { emptyList() }
                val episodeExtra = EpisodeExtra(
                    source = provider.providerId,
                    episodeId = episode.id,
                    hasDub = episode.hasDub,
                )
                episodeExtras[episode.number] = existingEpisodeExtras + listOf(episodeExtra)
            }
        }

        return episodes.map { episodeMap ->
            val episode = episodeMap.value
            val episodeNumber = episode.number
            val episodeExtra = episodeExtras.getValue(episodeNumber)
            val episodeExtraString = json.encodeToString(episodeExtra)
                .let { Base64.encode(it.toByteArray(), Base64.DEFAULT) }
                .toString(Charsets.UTF_8)

            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("anime")
                .addPathSegment("watch")
                .addQueryParameter("id", animeId)
                .addQueryParameter("ep", episodeNumber.toString())
                .addQueryParameter("extras", episodeExtraString)
                .build()

            val name = parseEpisodeName(episodeNumber, episode.title)
            val uploadDate = parseDate(episode.createdAt)
            val dub = when {
                episodeExtra.any { it.hasDub } -> ", Dub"
                else -> ""
            }
            val filler = when {
                episode.isFiller && isMarkFiller -> " • Filler Episode"
                else -> ""
            }
            val scanlator = "Sub$dub$filler"

            SEpisode.create().apply {
                this.url = url.toString()
                this.name = name
                this.date_upload = uploadDate
                this.episode_number = episodeNumber.toFloat()
                this.scanlator = scanlator
            }
        }.reversed()
    }

    /* ======================================= Video List ======================================= */

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = episode.url.toHttpUrl()
        val animeId = episodeUrl.queryParameter("id") ?: return emptyList()
        val episodeNum = episodeUrl.queryParameter("ep") ?: return emptyList()
        val extras = episodeUrl.queryParameter("extras")
            ?.let {
                Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8)
            }
            ?.let { json.decodeFromString<List<EpisodeExtra>>(it) }
            ?: emptyList()

        val episodeDataList = extras.parallelFlatMapBlocking { extra ->
            val languages = mutableListOf("sub")
            if (extra.hasDub) {
                languages.add("dub")
            }
            val url = "$baseUrl/api/anime/source/$animeId"

            languages.map { language ->
                val requestBody = json
                    .encodeToString(
                        VideoSourceRequest(
                            source = extra.source,
                            episodeId = extra.episodeId,
                            episodeNum = episodeNum,
                            subType = language,
                        ),
                    )
                    .toRequestBody("application/json".toMediaType())

                val response = client
                    .newCall(POST(url = url, body = requestBody))
                    .execute()
                    .parseAs<VideoSourceResponse>()

                EpisodeData(
                    source = extra.source,
                    language = language,
                    response = response,
                )
            }
        }

        val videos = episodeDataList.flatMap { episodeData ->
            val defaultSource = episodeData.response.sources?.first {
                it.quality in listOf("default", "auto")
            } ?: return@flatMap emptyList()

            val subtitles = episodeData.response.subtitles
                ?.filter { it.lang != "Thumbnails" }
                ?.map { Track(it.url, it.lang) }
                ?: emptyList()

            playlistUtils.extractFromHls(
                playlistUrl = defaultSource.url,
                videoNameGen = { quality ->
                    val serverName = getServerName(episodeData.source)
                    val typeName = when {
                        subtitles.isNotEmpty() -> "SoftSub"
                        else -> getTypeName(episodeData.language)
                    }

                    "$serverName - $quality - $typeName"
                },
                subtitleList = subtitles,
            )
        }

        return videos.sort()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!.let(::getTypeName)
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!.let(::getServerName)

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(lang) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    /* ====================================== Preferences ====================================== */

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain"
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_ENTRY_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRY_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = "Preferred type"
            entries = PREF_TYPE_ENTRIES
            entryValues = PREF_TYPE_ENTRY_VALUES
            setDefaultValue(PREF_TYPE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANGUAGE_KEY
            title = "Preferred title language"
            entries = PREF_TITLE_LANGUAGE_ENTRIES
            entryValues = PREF_TITLE_LANGUAGE_ENTRY_VALUES
            setDefaultValue(PREF_TITLE_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                Toast.makeText(screen.context, "Refresh your anime library to apply changes", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MARK_FILLER_EPISODE_KEY
            title = "Mark filler episodes"
            setDefaultValue(PREF_MARK_FILLER_EPISODE_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, "Refresh your anime library to apply changes", Toast.LENGTH_LONG).show()
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }

    /* =================================== AniPlay Utilities =================================== */

    private fun parseEpisodeName(number: Int, name: String): String {
        return when {
            listOf("EP ", "EPISODE ").any(name::startsWith) -> "Episode $number"
            else -> "Episode $number: $name"
        }
    }

    private fun getServerName(value: String): String {
        val index = PREF_SERVER_ENTRY_VALUES.indexOf(value)
        return PREF_SERVER_ENTRIES[index]
    }

    private fun getTypeName(value: String): String {
        val index = PREF_TYPE_ENTRY_VALUES.indexOf(value)
        return PREF_TYPE_ENTRIES[index]
    }

    @Synchronized
    private fun parseDate(dateStr: String?): Long {
        return dateStr?.let {
            runCatching { DATE_FORMATTER.parse(it)?.time }.getOrNull()
        } ?: 0L
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "domain"
        private val PREF_DOMAIN_ENTRIES = arrayOf("aniplaynow.live (default)", "aniplay.lol (backup)")
        private val PREF_DOMAIN_ENTRY_VALUES = arrayOf("aniplaynow.live", "aniplay.lol")
        private const val PREF_DOMAIN_DEFAULT = "aniplaynow.live"

        private const val PREF_SERVER_KEY = "server"
        private val PREF_SERVER_ENTRIES = arrayOf("Kuro (Gogoanime)", "Yuki (HiAnime)", "Yuno (Yugenanime)")
        private val PREF_SERVER_ENTRY_VALUES = arrayOf("kuro", "yuki", "yuno")
        private const val PREF_SERVER_DEFAULT = "kuro"

        private const val PREF_QUALITY_KEY = "quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_ENTRY_VALUES = arrayOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TYPE_KEY = "type"
        private val PREF_TYPE_ENTRIES = arrayOf("Sub", "SoftSub", "Dub")
        private val PREF_TYPE_ENTRY_VALUES = arrayOf("sub", "softsub", "dub")
        private const val PREF_TYPE_DEFAULT = "sub"

        private const val PREF_TITLE_LANGUAGE_KEY = "title_language"
        private val PREF_TITLE_LANGUAGE_ENTRIES = arrayOf("Romaji", "English", "Native")
        private val PREF_TITLE_LANGUAGE_ENTRY_VALUES = arrayOf("romaji", "english", "native")
        private const val PREF_TITLE_LANGUAGE_DEFAULT = "romaji"

        private const val PREF_MARK_FILLER_EPISODE_KEY = "mark_filler_episode"
        private const val PREF_MARK_FILLER_EPISODE_DEFAULT = true

        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    }
}
