package eu.kanade.tachiyomi.animeextension.pt.funanimetv

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.GetAppDetailsResponse
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.GetHomeVideosResponse
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.SearchVideoItemDto
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.SingleVideoItemDto
import eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto.VideoByCatItemDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

class FunAnimeTV : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Fun Anime TV"

    override val baseUrl = "https://betterclass.click"

    val apiUrl by lazy {
        "$baseUrl/api.php"
    }

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; M2007J20CG Build/BP3A.250905.014)")
    }

    override val client by lazy {
        network.client.newBuilder()
            .rateLimit(5, 1.seconds)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val body = createRequestJson("get_home_videos")

        return POST(apiUrl, headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = response.getByArrayKey<GetHomeVideosResponse>()

        val animes = json.mostViewed.map {
            SAnime.create().apply {
                url = baseUrl.toHttpUrl().newBuilder().apply {
                    addQueryParameter("cid", it.cid)
                    if (it.isTemporada) {
                        addQueryParameter("tid", it.tid)
                    }
                }.build().toString()
                title = it.categoryName
                description = it.sinopse
                genre = it.genero
                thumbnail_url = it.categoryImage
            }
        }

        return AnimesPage(animes, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val body = createRequestJson("get_home_videos")

        return POST(apiUrl, headers, body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val json = response.getByArrayKey<GetHomeVideosResponse>()

        val animes = json.latestVideo.map { vid ->
            val category = json.allVideoCat.find { cat -> cat.categoryName == vid.categoryName }
            val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("id", vid.id)
            }

            SAnime.create().apply {
                title = "${vid.categoryName} | Legendado"
                description = vid.videoTitle
                thumbnail_url = vid.videoThumbnailB
                category?.let {
                    description = category.sinopse
                    thumbnail_url = category.categoryImage
                    urlBuilder.addQueryParameter("cid", category.cid)
                    urlBuilder.addQueryParameter("tid", category.tid)
                }
                url = urlBuilder.build().toString()
            }
        } + json.latestVideoDub.map { vid ->
            val category = json.allVideoCat.find { cat -> cat.categoryName == vid.categoryName }
            val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("id", vid.id)
            }

            SAnime.create().apply {
                title = "${vid.categoryName} | Dublado"
                description = vid.videoTitle
                thumbnail_url = vid.videoThumbnailB
                category?.let {
                    description = category.sinopse
                    thumbnail_url = category.categoryImage
                    urlBuilder.addQueryParameter("cid", category.cid)
                    urlBuilder.addQueryParameter("tid", category.tid)
                }
                url = urlBuilder.build().toString()
            }
        }

        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = FunAnimeTVFilters.getSearchParameters(filters)

        val data = buildJsonObject {
            put("search_text", query)
            put("title_type", null)
            put("content_type", null)
            put("category", null)

            if (params.genre.isNotBlank()) {
                put("category", params.genre)
            }
        }

        val body = createRequestJson("get_search_video", data)

        return POST(apiUrl, headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val json = response.getByArrayKey<List<SearchVideoItemDto>>()

        val animes = json.map {
            SAnime.create().apply {
                url = baseUrl.toHttpUrl().newBuilder().apply {
                    addQueryParameter("cid", it.cid)
                    if (it.isTemporada) {
                        addQueryParameter("tid", it.tid)
                    }
                }.build().toString()
                title = if (it.isTemporada) {
                    "${it.categoryName} | ${it.tempName}"
                } else {
                    "${it.categoryName} | ${it.audioType}"
                }
                description = it.sinopse
                genre = it.genero
                thumbnail_url = it.categoryImage
            }
        }

        return AnimesPage(animes, false)
    }

    override fun getFilterList() = FunAnimeTVFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime =
        throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = anime.url.toHttpUrl()

        val cid = url.queryParameter("cid")
        val tid = url.queryParameter("tid")
        val id = url.queryParameter("id")

        if (cid.isNullOrBlank() && !id.isNullOrBlank()) {
            val params = buildJsonObject {
                put("video_id", id)
            }
            val form = createRequestJson("get_single_video", params)

            val request = POST(apiUrl, headers, form)

            val data = client
                .newCall(request)
                .execute()
                .getByArrayKey<List<SingleVideoItemDto>>()
                .first()

            anime.url = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("cid", data.catId)
                addQueryParameter("tid", data.tempId)
            }.build().toString()
            anime.title = "${data.categoryName} | ${data.tempName}"
            anime.thumbnail_url = data.tempImage
        }

        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val url = runBlocking {
            getAnimeDetails(anime).url.toHttpUrl()
        }

        val cid = url.queryParameter("cid")
        val tid = url.queryParameter("tid")

        val params = buildJsonObject {
            put("cat_id", cid)
            put("page", 1)

            if (!tid.isNullOrBlank()) {
                put("tid", tid)
            }
        }
        val form = createRequestJson("get_video_by_cat_id", params)

        return POST(apiUrl, headers, form)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val json = response.getByArrayKey<List<VideoByCatItemDto>>()

        return json.map {
            SEpisode.create().apply {
                url = baseUrl.toHttpUrl().newBuilder().apply {
                    addQueryParameter("id", it.id)
                }.build().toString()
                name = it.videoTitle
                episode_number = it.videoEp.split(" ")[1].toFloatOrNull() ?: 1F
                scanlator = it.videoEp
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val url = episode.url.toHttpUrl()

        val id = url.queryParameter("id")

        val params = buildJsonObject {
            put("video_id", id)
        }
        val form = createRequestJson("get_single_video", params)

        return POST(apiUrl, headers, form)
    }

    override fun videoListParse(response: Response): List<Video> {
        val json = response.getByArrayKey<List<SingleVideoItemDto>>().first()

        val videos = mutableListOf<Video>()

        if (json.videoUrlSd.startsWith("http")) {
            val videoUrl = json.videoUrl
            videos.add(Video(videoUrl, "360p", videoUrl))
        }
        if (json.videoUrl.startsWith("http")) {
            val videoUrl = json.videoUrl
            videos.add(Video(videoUrl, "720p", videoUrl))
        }
        if (json.videoUrlFhd.startsWith("http")) {
            val videoUrl = json.videoUrl
            videos.add(Video(videoUrl, "1080p", videoUrl))
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

    fun generateSalt(): String {
        return (1..900).random().toString()
    }

    /**
     * Calcula MD5 hash de uma string
     */
    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun createRequestJson(
        methodName: String,
        additionalParams: JsonObject? = null,
        singSalt: String? = null,
    ): RequestBody {
        val jsonObject = buildJsonObject {
            val salt = generateSalt()
            val sign = singSalt ?: constants.signSalt
            put("salt", salt)
            put(
                "sign",
                md5("$sign$salt"),
            )
            put("method_name", methodName)

            // Adiciona parâmetros adicionais se fornecidos
            additionalParams?.forEach { (key, value) ->
                put(key, value)
            }
        }

        val jsonString = json.encodeToString(JsonObject.serializer(), jsonObject)

        val base64Data = Base64.encodeToString(
            jsonString.toByteArray(Charsets.UTF_8),
            Base64.NO_PADDING,
        )

        return FormBody.Builder()
            .add("data", base64Data)
            .build()
    }

    private inline fun <reified T> Response.getByArrayKey(key: String? = null): T {
        val json = this.parseAs<Map<String, T>>()
        return json[key ?: constants.arrayPadrao]
            ?: throw IllegalStateException("Chave '${constants.arrayPadrao}' não encontrada na resposta")
    }

    private val constants: Constants by lazy {
        val form = createRequestJson(
            "get_app_details",
            null,
            "JbWIGaSQOVoJLYCF0RU",
        )

        val request = POST("$baseUrl/valid_g.php", headers, form)

        val appDetails = client
            .newCall(request)
            .execute()
            .getByArrayKey<List<GetAppDetailsResponse>>(NAME_INIT)
            .first()

        Constants(
            signSalt = appDetails.singsalt,
            arrayPadrao = appDetails.arrayPadrao,
        )
    }

    class Constants(
        val signSalt: String,
        val arrayPadrao: String,
    )

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
        private val NAME_INIT = "FUN_ANIME_01"

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
