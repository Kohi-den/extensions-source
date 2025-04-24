package eu.kanade.tachiyomi.animeextension.en.aniplay

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Log
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parallelFlatMap
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

@Suppress("unused")
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

    private val baseHost: String get() = "${preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)}"

    /* ====================================== Episode List ====================================== */

    override fun episodeListRequest(anime: SAnime): Request {
        val httpUrl = anime.url.toHttpUrl()
        val animeId = httpUrl.pathSegments[2]

        val requestBody = "[\"${animeId}\",true,false]"
            .toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val headersWithAction =
            headers.newBuilder()
                // next.js stuff I guess
                .add("Next-Action", getHeaderValue(baseHost, NEXT_ACTION_EPISODE_LIST))
                .build()

        return POST(url = "$baseUrl/anime/info/$animeId", headersWithAction, requestBody)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val isMarkFiller = preferences.getBoolean(PREF_MARK_FILLER_EPISODE_KEY, PREF_MARK_FILLER_EPISODE_DEFAULT)
        val episodeListUrl = response.request.url
        val animeId = episodeListUrl.pathSegments[2]

        val responseString = response.body.string()
        val episodesArrayString = extractEpisodeList(responseString)
        if (episodesArrayString == null) {
            Log.e("AniPlay", "Episode list not found - ${response.request}\nbody:${response.request.body}\n${responseString.substring(0,200)}")
            throw Exception("Episode list not found")
        }

        val providers = episodesArrayString.parseAs<List<EpisodeListResponse>>()
        val episodes = mutableMapOf<Int, EpisodeListResponse.Episode>()
        val episodeExtras = mutableMapOf<Int, List<EpisodeExtra>>()

        providers.forEach { provider ->
            provider.episodes.forEach { episode ->
                val episodeNumber = episode.number.toString().toIntOrNull() ?: episode.number.toInt()
                if (!episodes.containsKey(episodeNumber)) {
                    episodes[episodeNumber] = episode
                }
                val existingEpisodeExtras = episodeExtras.getOrElse(episodeNumber) { emptyList() }
                val episodeExtra = EpisodeExtra(
                    source = provider.providerId,
                    episodeId = episode.id,
                    episodeNum = episode.number,
                    hasDub = episode.hasDub ?: false,
                )
                episodeExtras[episodeNumber] = existingEpisodeExtras + listOf(episodeExtra)
            }
        }

        return episodes.map { episodeMap ->
            val episode = episodeMap.value
            val episodeNumber = episodeMap.key
            val episodeExtra = episodeExtras.getValue(episodeNumber)
            val episodeExtraString = json.encodeToString(episodeExtra)
                .let { Base64.encodeToString(it.toByteArray(), Base64.DEFAULT) }

            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("anime")
                .addPathSegment("watch")
                .addQueryParameter("id", animeId)
                .addQueryParameter("ep", episodeNumber.toString())
                .addQueryParameter("extras", episodeExtraString)
                .build()

            val name = parseEpisodeName(episodeNumber.toString(), episode.title)
            val uploadDate = parseDate(episode.createdAt)
            val dub = when {
                episodeExtra.any { it.hasDub } -> ", Dub"
                else -> ""
            }
            val filler = when {
                episode.isFiller == true && isMarkFiller -> " • Filler Episode"
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
        val extras = episodeUrl.queryParameter("extras")
            ?.let {
                try {
                    Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    Log.e("AniPlay", "Error decoding base64", e)
                    return emptyList()
                }
            }
            ?.let {
                try {
                    json.decodeFromString<List<EpisodeExtra>>(it)
                } catch (e: SerializationException) {
                    Log.e("AniPlay", "Error parsing JSON extras", e)
                    emptyList()
                }
            }
            ?: emptyList()

        var timeouts = 0
        var maxTimeout = 0
        val videos = extras.parallelFlatMap { extra ->
            val languages = mutableListOf("sub").apply {
                if (extra.hasDub) add("dub")
            }
            languages.parallelFlatMap { language ->
                val epNum = if (extra.episodeNum == extra.episodeNum.toInt().toFloat()) {
                    extra.episodeNum.toInt().toString()
                } else {
                    extra.episodeNum.toString()
                }

                val params = mapOf(
                    "host" to extra.source,
                    "ep" to epNum,
                    "type" to language,
                )

                val builder = Uri.parse("$baseUrl/anime/watch/$animeId").buildUpon()
                params.map { (k, v) -> builder.appendQueryParameter(k, v); }
                val url = builder.build()

                val headersWithAction =
                    headers.newBuilder()
                        .add("Next-Action", getHeaderValue(baseHost, NEXT_ACTION_SOURCES_LIST))
                        .build()

                val requestBody = "[\"$animeId\",\"${extra.source}\",\"${extra.episodeId}\",\"$epNum\",\"$language\"]"
                    .toRequestBody("application/json".toMediaType())

                val request = POST(url.toString(), headersWithAction, requestBody)

                maxTimeout += 1
                try {
                    getVideos(extra, language, request)
                } catch (e: java.net.SocketTimeoutException) {
                    Log.e("AniPlay", "VideoList $url SocketTimeoutException", e)
                    timeouts++
                    emptyList()
                } catch (e: IOException) {
                    Log.e("AniPlay", "VideoList $url IOException", e)
                    emptyList()
                } catch (e: Exception) {
                    Log.e("AniPlay", "VideoList $url Exception", e)
                    emptyList()
                }
            }
        }

        if (videos.isEmpty() && timeouts != 0 && maxTimeout == timeouts) {
            throw Exception("Timed out")
        }

        return videos.sort()
    }

    private fun getVideos(extra: EpisodeExtra, language: String, request: Request): List<Video> {
        val response = client.newCall(request).execute()

        val responseString = response.body.string()
        try {
            val sourcesString = extractSourcesList(responseString) ?: throw Exception("extractSourcesList null")
            Log.i("AniPlay", "${extra.source} $language -> $sourcesString")
            return processEpisodeData(
                EpisodeData(
                    source = extra.source,
                    language = language,
                    response = sourcesString.parseAs<VideoSourceResponse>(),
                ),
            )
        } catch (e: Exception) {
            Log.e("AniPlay", "processEpisodeData Error (\"${extra.source} - $language\"): $e")
            return emptyList()
        }
    }

    private fun processEpisodeData(episodeData: EpisodeData): List<Video> {
        val defaultSource = episodeData.response.sources?.firstOrNull {
            it.quality in listOf("default", "auto")
        } ?: return emptyList()

        val subtitles = episodeData.response.subtitles
            ?.filter { it.lang?.lowercase() != "thumbnails" }
            ?.map { Track(it.url ?: throw Exception("episodeData.response.subtitles.url is null ($it)"), it.lang ?: "Unk") }
            ?: emptyList()

        var serverName = getServerName(episodeData.source)
        if (serverName == SERVER_UNKNOWN) {
            serverName = episodeData.source.substring(0, 69) + "!"
        }
        var typeName = getTypeName(episodeData.language)
        if (typeName == "Sub" && subtitles.isNotEmpty()) {
            typeName = "SoftSub"
        } else if (serverName == "Yuki" && typeName == "Dub" && subtitles.isNotEmpty()) {
            typeName = "Dubtitles"
        }

        try {
            when (serverName) {
                // yuki
                PREF_SERVER_ENTRIES[1] -> {
                    val url = "https://yukiprox.aniplaynow.live/m3u8-proxy?url=${defaultSource.url}&headers={\"Referer\":\"https://megacloud.club/\"}"
                    return playlistUtils.extractFromHls(
                        playlistUrl = url,
                        videoNameGen = { quality -> "$serverName - $quality - $typeName" },
                        subtitleList = playlistUtils.fixSubtitles(subtitles),
                        masterHeadersGen = { baseHeaders: Headers, _: String ->
                            baseHeaders.newBuilder().apply {
                                set("Accept", "*/*")
                                set("Origin", baseUrl)
                                set("Referer", "$baseUrl/")
                            }.build()
                        },
                        videoHeadersGen = { baseHeaders: Headers, _, _: String ->
                            baseHeaders.newBuilder().apply {
                                set("Accept", "*/*")
                                set("Origin", baseUrl)
                                set("Referer", "$baseUrl/")
                            }.build()
                        },
                    )
                }
                // pahe
                PREF_SERVER_ENTRIES[2] -> {
                    val url = "https://prox.aniplaynow.live/?url=${defaultSource.url}&ref=https://kwik.si"
                    val headers = headers.newBuilder().apply {
                        set("Accept", "*/*")
                        set("Origin", baseUrl)
                        set("Referer", "$baseUrl/")
                    }.build()
                    return listOf(Video(url, "$serverName - Video - $typeName", url, headers, subtitles, listOf()))
                }
                else -> {
                    throw Exception("Unknown serverName: $serverName (${episodeData.source})")
                }
            }
        } catch (e: Exception) {
            Log.e("AniPlay", "processEpisodeData extractFromHls Error (\"$serverName - $typeName\"): $e")
        }

        return emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!.let(::getTypeName)
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!.let(::getServerName)

        val lang2 = if ((lang == PREF_TYPE_ENTRIES[1]) or (lang == PREF_TYPE_ENTRIES[3])) {
            // if preferred language is SoftSub or Dubtitles
            PREF_TYPE_ENTRIES[PREF_TYPE_ENTRIES.indexOf(lang) - 1]
        } else {
            ""
        }

        return sortedWith(
            compareByDescending<Video> { it.quality.split(" - ").contains(lang) }
                .thenByDescending { it.quality.contains(lang2) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    private fun extractEpisodeList(input: String): String? {
        return extractList(input, '[', ']')
    }

    private fun extractSourcesList(input: String): String? {
        return extractList(input, '{', '}')
    }

    private fun extractList(input: String, bracket1: Char, bracket2: Char): String? {
        val startMarker = "1:$bracket1"
        val list1Index = input.indexOf(startMarker)
        if (list1Index == -1) return null

        val startIndex = list1Index + startMarker.length
        var endIndex = startIndex
        var bracketCount = 1

        while (endIndex < input.length && bracketCount > 0) {
            when (input[endIndex]) {
                bracket1 -> bracketCount++
                bracket2 -> bracketCount--
            }
            endIndex++
        }

        return if (bracketCount == 0) input.substring(startIndex - 1, endIndex) else null
    }

    private val headerFetchLock = ReentrantLock()
    private var lastHeaderFetch = 0L
    private fun fetchHeaders() {
        val internalFetchHeaders = internalFetchHeaders@{
            val currentTimestamp = Date().time
            val timeout = lastHeaderFetch + (HEADERS_TIMEOUT_MINUTES * 60 * 1000)
            // check only after 15 minutes
            if (timeout > currentTimestamp) {
                Log.i("AniPlay", "Skipping header update. $timeout > $currentTimestamp (${timeout - currentTimestamp}).")
                return@internalFetchHeaders
            }

            val baseUrl = Base64.decode("aHR0cHM6Ly9qb3NlZmZzdHJha2EuZ2l0aHViLmlvL2FuaXBsYXktaGVhZGVycy8=", Base64.DEFAULT).toString(Charsets.UTF_8)

            val preferredDomain = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

            try {
                val url = ("$baseUrl$preferredDomain/headers.json").toHttpUrl()
                val response = client.newCall(Request(url)).execute()
                val body = response.body.string()
                val domainHeaders = body.parseAs<DomainHeaders>()
                domainsHeaders[preferredDomain] = domainHeaders
                Log.i("AniPlay", "Fetched headers($preferredDomain): $domainHeaders")
            } catch (e: Exception) {
                Log.e("AniPlay", "Failed to fetch new headers: \"e\"")
                return@internalFetchHeaders
            }

            lastHeaderFetch = Date().time
        }

        headerFetchLock.lock()
        try {
            internalFetchHeaders()
        } finally {
            headerFetchLock.unlock()
        }
    }

    private fun getHeaderValue(serverHost: String, key: String): String {
        fetchHeaders()
        try {
            val domainHeaders = domainsHeaders[serverHost] ?: throw Exception("Bad host: $serverHost")
            return when (key) {
                NEXT_ACTION_EPISODE_LIST -> domainHeaders.episodes
                NEXT_ACTION_SOURCES_LIST -> domainHeaders.sources
                else -> throw Exception("Bad key: $key")
            }
        } catch (e: Exception) {
            Log.e("AniPlay", "getHeaderValue error. $e. (s:${domainsHeaders.size}, l:$lastHeaderFetch)")
            throw e
        }
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
    private fun parseEpisodeName(number: String, title: String?): String {
        return if (title.isNullOrBlank()) {
            "Episode $number"
        } else {
            "Episode $number: $title"
        }
    }
    private fun getServerName(value: String): String {
        val index = PREF_SERVER_ENTRY_VALUES.indexOf(value)
        if (index == -1) {
            return SERVER_UNKNOWN
        }
        return PREF_SERVER_ENTRIES[index]
    }

    private fun getTypeName(value: String): String {
        val index = PREF_TYPE_ENTRY_VALUES.indexOf(value.lowercase())
        if (index == -1) {
            return TYPE_UNKNOWN
        }
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
        private val PREF_DOMAIN_ENTRIES = arrayOf("aniplaynow.live (default)", "aniplay.lol (backup/experimental)")
        private val PREF_DOMAIN_ENTRY_VALUES = arrayOf("aniplaynow.live", "aniplay.lol")
        private const val PREF_DOMAIN_DEFAULT = "aniplaynow.live"

        private const val PREF_SERVER_KEY = "server"
        private val PREF_SERVER_ENTRIES = arrayOf("Maze", "Yuki", "Pahe", "Kuro")
        private val PREF_SERVER_ENTRY_VALUES = arrayOf("maze", "yuki", "pahe", "kuro")
        private const val PREF_SERVER_DEFAULT = "yuki"
        private const val SERVER_UNKNOWN = "Other"

        private const val PREF_QUALITY_KEY = "quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_ENTRY_VALUES = arrayOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TYPE_KEY = "type"
        private val PREF_TYPE_ENTRIES = arrayOf("Sub", "SoftSub", "Dub", "Dubtitles")
        private val PREF_TYPE_ENTRY_VALUES = arrayOf("sub", "softsub", "dub", "dubtitles")
        private const val PREF_TYPE_DEFAULT = "softsub"
        private const val TYPE_UNKNOWN = "Other"

        private const val PREF_TITLE_LANGUAGE_KEY = "title_language"
        private val PREF_TITLE_LANGUAGE_ENTRIES = arrayOf("Romaji", "English", "Native")
        private val PREF_TITLE_LANGUAGE_ENTRY_VALUES = arrayOf("romaji", "english", "native")
        private const val PREF_TITLE_LANGUAGE_DEFAULT = "romaji"

        private const val PREF_MARK_FILLER_EPISODE_KEY = "mark_filler_episode"
        private const val PREF_MARK_FILLER_EPISODE_DEFAULT = true

        // These values has probably something to do with Next.js server and hydration
        private const val NEXT_ACTION_EPISODE_LIST = "NEXT_ACTION_EPISODE_LIST"
        private const val NEXT_ACTION_SOURCES_LIST = "NEXT_ACTION_SOURCES_LIST"

        private const val HEADERS_TIMEOUT_MINUTES = 60

        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

        private var domainsHeaders = mutableMapOf<String, DomainHeaders>()
    }
}
