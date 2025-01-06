package eu.kanade.tachiyomi.animeextension.pt.tomato

import android.app.Application
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.AnimeResultDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.EpisodeInfoDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.EpisodesResultDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.SearchAnimeItemDto
import eu.kanade.tachiyomi.animeextension.pt.tomato.dto.SearchResultDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.parallelMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration.Companion.seconds

class Tomato : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Tomato"

    override val baseUrl = "https://beta-api.tomatoanimes.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder().add(
        "Authorization",
        "Bearer $TOKEN",
    )

    private val episodesClient by lazy {
        client.newBuilder().rateLimitHost(baseUrl.toHttpUrl(), 1, 0.5.seconds).build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/v2/animes/feed", headers = headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = response.parseAs<JsonObject>()

        val emAlta = responseJson["data"]?.jsonArray?.find {
            it.jsonObject["title"]?.jsonPrimitive?.content?.contains("curtidos") == true
        }

        val animes = emAlta?.jsonObject?.get("data")?.jsonArray?.parallelMapBlocking {
            animeFromId(it.jsonObject["anime_id"]!!.jsonPrimitive.int)
        }
            ?: emptyList<SAnime>()

        return AnimesPage(animes, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/v2/animes/feed", headers = headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseJson = response.parseAs<JsonObject>()

        val emAlta = responseJson["data"]?.jsonArray?.find {
            it.jsonObject["type"]!!.jsonPrimitive.int == 7
        }

        val animes = emAlta?.jsonObject?.get("data")?.jsonArray?.parallelMapBlocking {
            animeFromId(it.jsonObject["ep_anime_id"]!!.jsonPrimitive.int)
        }
            ?: emptyList<SAnime>()

        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = TomatoFilters.getSearchParameters(filters)

        val data = buildJsonObject {
            put("token", TOKEN)
            put("search", query)
            put("content_type", "anime")
            put("page", page - 1)

            if (params.genres.isNotEmpty()) {
                putJsonArray("tags") {
                    params.genres.forEach { add(it) }
                }
            }
        }

        val body = json.encodeToString(JsonObject.serializer(), data)
            .toRequestBody("application/json".toMediaType())

        return POST("$baseUrl/v2/content/search", headers = headers, body = body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchResult = response.parseAs<SearchResultDto>().result
        val results = searchResult.map { it.toSAnime() }
        return AnimesPage(results, false)
    }

    private fun SearchAnimeItemDto.toSAnime(): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain("$baseUrl/v2/anime/$id")
            title = name
            thumbnail_url = image
        }
    }

    override fun getFilterList() = TomatoFilters.FILTER_LIST

    // =========================== Anime Details ============================
    private fun animeFromId(id: Int): SAnime {
        val response = client.newCall(
            GET("$baseUrl/v2/anime/$id", headers = headers),
        ).execute()

        return animeDetailsParse(response)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.parseAs<AnimeResultDto>()
        return SAnime.create().apply {
            setUrlWithoutDomain("$baseUrl/v2/anime/${anime.animeDetails.animeId}")
            title = anime.animeDetails.animeName
            description = anime.animeDetails.animeDescription
            genre = anime.animeDetails.animeGenre
            thumbnail_url = anime.animeDetails.animeCoverUrl
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val anime = response.parseAs<AnimeResultDto>()

        val seasons = anime.animeSeasons

        val episodeList = mutableListOf<SEpisode>()

        seasons.forEach { season ->
            var nextPage = 0
            do {
                val data = buildJsonObject {
                    put("token", TOKEN)
                    put("page", nextPage)
                    put("order", "ASC")
                }

                val body = json.encodeToString(JsonObject.serializer(), data)
                    .toRequestBody("application/json".toMediaType())

                val request = POST(
                    "$baseUrl/season/${season.seasonId}/episodes",
                    headers = headers,
                    body = body,
                )
                val episodes =
                    episodesClient.newCall(request).execute().parseAs<EpisodesResultDto>().data

                episodes.forEach { episode ->
                    val partName = "Temporada ${season.seasonNumber} x ${episode.epNumber}"
                    val fullName = "$partName - ${episode.epName}"

                    val prev = episodeList.find { it.name.contains(partName) }

                    val newUrl = "&episode[${season.seasonDubbed}]=${episode.epId}"
                    if (prev != null) {
                        prev.url += newUrl
                    } else {
                        episodeList.add(
                            SEpisode.create().apply {
                                episode_number = episode.epNumber
                                name = fullName
                                url = "http://localhost?season=${season.seasonNumber}$newUrl"
                            },
                        )
                    }
                }

                if (episodes.size == 25) nextPage += 1 else nextPage = -1
            } while (nextPage != -1)
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        Log.d("fetchVideoList", episode.url)

        val dubs = listOf(
            Pair("Legendado", episode.url.toHttpUrl().queryParameter("episode[0]")),
            Pair("Dublado", episode.url.toHttpUrl().queryParameter("episode[1]")),
        )

        val videos = mutableListOf<Video>()

        dubs.forEach { dub ->
            if (dub.second.isNullOrBlank()) {
                return@forEach
            }
            val request =
                GET("$baseUrl/v2/anime/episode/${dub.second}/stream", headers = headers)
            val response = client.newCall(request).execute().parseAs<EpisodeInfoDto>()

            if (response.streams.shd != null) {
                videos.add(
                    Video(
                        response.streams.shd,
                        "${dub.first} - 480p",
                        videoUrl = response.streams.shd,
                    ),
                )
            }
            if (response.streams.mhd != null) {
                videos.add(
                    Video(
                        response.streams.mhd,
                        "${dub.first} - 720p",
                        videoUrl = response.streams.mhd,
                    ),
                )
            }
            if (response.streams.fhd != null) {
                videos.add(
                    Video(
                        response.streams.fhd,
                        "${dub.first} - 1080p",
                        videoUrl = response.streams.fhd,
                    ),
                )
            }
        }

        return videos.sort()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
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
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_VALUES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(lang) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending {
                    REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6MTMxNjUzODQsInV1aWQiOiI4N2VmNmNmMC1jMjFkLTExZWYtODAxNS01NzNlMjdjNWU4ZGIiLCJpYXQiOjE3MzUwNjMwNTd9.5JMhTqBjs4A3VxrIjNQqpXtJGJ5y8MJt-ARvFrjcYUo"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("480p", "720p", "1080p")

        private const val PREF_LANGUAGE_KEY = "pref_language"
        private const val PREF_LANGUAGE_DEFAULT = "Legendado"
        private const val PREF_LANGUAGE_TITLE = "Língua/tipo preferido"
        private val PREF_LANGUAGE_VALUES = arrayOf("Legendado", "Dublado")
    }
}
