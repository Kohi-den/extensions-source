package eu.kanade.tachiyomi.animeextension.all.shabakatycinemana

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

inline fun <reified T> Response.asModel(deserializer: DeserializationStrategy<T>): T {
    return Json.decodeFromString(deserializer, this.body.string())
}

inline fun <reified T> Response.asModelList(deserializer: DeserializationStrategy<T>): List<T> {
    return Json.parseToJsonElement(this.body.string()).jsonArray.map {
        Json.decodeFromJsonElement(deserializer, it)
    }
}

object SEpisodeDeserializer : DeserializationStrategy<SEpisode> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("SEpisode")

    override fun deserialize(decoder: Decoder): SEpisode {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        val nb = jsonObject["nb"]?.jsonPrimitive?.content!!
        val episodeNumber = jsonObject["episodeNummer"]?.jsonPrimitive?.content
        val seasonNumber = jsonObject["season"]?.jsonPrimitive?.content
        val seasonEpisode = arrayOf(seasonNumber, episodeNumber).joinToString(ShabakatyCinemana.SEASON_EPISODE_DELIMITER)
        val uploadDate = jsonObject["videoUploadDate"]?.jsonPrimitive?.content.runCatching {
            this?.let { ShabakatyCinemana.DATE_FORMATTER.parse(it)?.time }
        }.getOrNull() ?: 0L

        return SEpisode.create().apply {
            url = nb
            episode_number = "$seasonNumber.$episodeNumber".parseAs()
            name = seasonEpisode
            date_upload = uploadDate
        }
    }
}

object VideoDeserializer : DeserializationStrategy<Video> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("Video")

    override fun deserialize(decoder: Decoder): Video {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        val videoUrl = jsonObject["videoUrl"]?.jsonPrimitive?.content!!
        val quality = jsonObject["resolution"]?.jsonPrimitive?.content.orEmpty()

        return Video(url = videoUrl, videoUrl = videoUrl, quality = quality)
    }
}

object SubtitleDeserialize : DeserializationStrategy<List<Track>> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("Track")

    override fun deserialize(decoder: Decoder): List<Track> {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        return jsonObject["translations"]?.jsonArray?.map {
            val url = it.jsonObject["file"]?.jsonPrimitive?.content!!
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
            val extension = it.jsonObject["extention"]?.jsonPrimitive?.content
            val lang = arrayOf(name, extension).joinToString(ShabakatyCinemana.SUBTITLE_DELIMITER)

            Track(url, lang)
        }.orEmpty()
    }
}

data class SAnimeListWithInfo(val animes: List<SAnime>, val offset: Int)

object SAnimeWithInfoDeserializer : DeserializationStrategy<SAnimeListWithInfo> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("SAnimeListWithInfo")

    override fun deserialize(decoder: Decoder): SAnimeListWithInfo {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        val animeList = jsonObject["info"]?.jsonArray?.map {
            Json.decodeFromJsonElement(SAnimeDeserializer, it)
        }.orEmpty()
        val offset = jsonObject["offset"]?.jsonPrimitive?.int ?: 0

        return SAnimeListWithInfo(animeList, offset)
    }
}

object SAnimeDeserializer : DeserializationStrategy<SAnime> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("SAnime")

    override fun deserialize(decoder: Decoder): SAnime {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        val nb = jsonObject["nb"]?.jsonPrimitive?.content!!
        val enTitle = jsonObject["en_title"]?.jsonPrimitive?.content ?: "no title"
        val imgObjUrl = jsonObject["imgObjUrl"]?.jsonPrimitive?.content
        val categories = jsonObject["categories"]?.jsonArray?.mapNotNull {
            it.jsonObject["en_title"]?.jsonPrimitive?.content
        }.orEmpty()
        val language = jsonObject["videoLanguages"]?.jsonObject?.get("en_title")?.jsonPrimitive?.content
        val genreText = (categories + language).mapNotNull { it }.joinToString()
        val directors = jsonObject["directorsInfo"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content
        }?.joinToString()
        val actors = jsonObject["actorsInfo"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content
        }?.joinToString()
        val enContent = jsonObject["en_content"]?.jsonPrimitive?.content
        val year = jsonObject["year"]?.jsonPrimitive?.content ?: "N/A"
        val stars = jsonObject["stars"]?.jsonPrimitive?.content?.parseAs<Float>()?.toInt() ?: 0
        val starsText = "${"★".repeat(stars / 2)}${"☆".repeat(5 - (stars / 2))}"
        val likes = jsonObject["Likes"]?.jsonPrimitive?.content?.parseAs<Int>() ?: 0
        val dislikes = jsonObject["DisLikes"]?.jsonPrimitive?.content?.parseAs<Int>() ?: 0
//        val ref = jsonObject["imdbUrlRef"]?.jsonPrimitive?.content ?: ""

        return SAnime.create().apply {
            url = nb
            title = enTitle
            thumbnail_url = imgObjUrl
            genre = genreText
            author = directors
            artist = actors
            description = listOfNotNull(
                "$year | $starsText | $likes\uD83D\uDC4D  $dislikes\uD83D\uDC4E",
                enContent,
            ).joinToString("\n\n")
        }
    }
}

class ShabakatyCinemana : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Shabakaty Cinemana"

    override val baseUrl = "https://cinemana.shabakaty.com"

    private val apiBaseUrl = "$baseUrl/api/android"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val IS_BROWSING_FILTER_NAME = "Browse"
        private const val KIND_FILTER_NAME = "Kind"
        private const val MAIN_CATEGORY_FILTER_NAME = "Main Category"
        private const val SUB_CATEGORY_FILTER_NAME = "Sub Category"
        private const val LANGUAGE_FILTER_NAME = "Language"
        private const val YEAR_FILTER_NAME = "Year"
        private const val BROWSE_RESULT_SORT_FILTER_NAME = "Browse Sort"
        private const val STAFF_TITLE_FILTER_NAME = "Staff Title"

        private const val POPULAR_ITEMS_PER_PAGE = 30
        private const val SEARCH_ITEMS_PER_PAGE = 12
        private const val LATEST_ITEMS_PER_PAGE = 24

        private const val PREF_LATEST_KIND_KEY = "preferred_latest_kind"
        private const val PREF_LATEST_KIND_DEFAULT = "Movies"
        private val KINDS_LIST = arrayOf(
            Pair("Movies", 1),
            Pair("Series", 2),
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("2160", "1080", "720", "480", "360", "240")

        private const val PREF_SUBTITLE_LANG_KEY = "preferred_subtitle_language"
        private const val PREF_SUBTITLE_LANG_DEFAULT = "arabic"
        private val LANG_LIST = arrayOf("arabic", "english")

        private const val PREF_SUBTITLE_EXT_KEY = "preferred_subtitle_extension"
        private const val PREF_SUBTITLE_EXT_DEFAULT = "ass"
        private val EXT_LIST = arrayOf("srt", "vtt", "ass")

        const val SUBTITLE_DELIMITER = " - "
        const val SEASON_EPISODE_DELIMITER = " - "

        val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/video/en/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime) = GET("$apiBaseUrl/allVideoInfo/id/${anime.url}")

    override fun animeDetailsParse(response: Response) = response.asModel(SAnimeDeserializer)

    override fun latestUpdatesRequest(page: Int): Request {
        val kind = preferences.getString(PREF_LATEST_KIND_KEY, PREF_LATEST_KIND_DEFAULT)!!

        return GET("$apiBaseUrl/latest$kind/level/0/itemsPerPage/$LATEST_ITEMS_PER_PAGE/page/${page - 1}/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animeList = response.asModelList(SAnimeDeserializer)
        return AnimesPage(animeList, animeList.size == LATEST_ITEMS_PER_PAGE)
    }

    override fun popularAnimeRequest(page: Int): Request {
        val kindPref = preferences.getString(PREF_LATEST_KIND_KEY, PREF_LATEST_KIND_DEFAULT)!!
        val kind = KINDS_LIST.first { it.first == kindPref }.second

        val url = "$apiBaseUrl/video/V/2/itemsPerPage/$POPULAR_ITEMS_PER_PAGE/level/0/videoKind/$kind/sortParam/desc/pageNumber/${page - 1}"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.asModelList(SAnimeDeserializer)
        return AnimesPage(animeList, animeList.size == POPULAR_ITEMS_PER_PAGE)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val isBrowsingFilter = filterList.find { it.name == IS_BROWSING_FILTER_NAME } as CheckBoxFilter
        val kindFilter = filterList.find { it.name == KIND_FILTER_NAME } as SingleSelectFilter
        val mainCategoryFilter = filterList.find { it.name == MAIN_CATEGORY_FILTER_NAME } as MultipleSelectFilter
        val subCategoryFilter = filterList.find { it.name == SUB_CATEGORY_FILTER_NAME } as MultipleSelectFilter
        val languageFilter = filterList.find { it.name == LANGUAGE_FILTER_NAME } as SingleSelectFilter
        val yearFilter = filterList.find { it.name == YEAR_FILTER_NAME } as YearFilter
        val staffTitleFilter = filterList.find { it.name == STAFF_TITLE_FILTER_NAME } as StaffTitleFilter
        val browseResultSortFilter = filterList.find { it.name == BROWSE_RESULT_SORT_FILTER_NAME } as BrowseResultSort
        val isBrowsing = isBrowsingFilter.state
        val kindName = kindFilter.getNameValue()
        val kindNumber = kindFilter.getNumberValue().toString()
        val selectedMainCategories = mainCategoryFilter.getSelectedIds()
        val mainCategory = selectedMainCategories.joinToString(",")
        val selectedSubCategories = subCategoryFilter.getSelectedIds()
        val bothCategory = (selectedMainCategories + selectedSubCategories).joinToString(",")
        val language = languageFilter.getNumberValue().toString()
        val year = yearFilter.getFormatted()
        val staffTitle = staffTitleFilter.state
        val browseResultSort = browseResultSortFilter.getValue()

        var url = apiBaseUrl.toHttpUrl()
        if (isBrowsing) {
            if (languageFilter.state != 0 && mainCategory.isNotBlank()) {
                url = url.newBuilder()
                    .addPathSegment("videosByCategoryAndLanguage")
                    .addQueryParameter("language_id", language)
                    .addQueryParameter("category_id", mainCategory)
                    .build()
            } else {
                url = url.newBuilder()
                    .addPathSegment("videosByCategory")
                    .build()

                if (mainCategoryFilter.hasSelected()) {
                    url = url.newBuilder().addQueryParameter("categoryID", mainCategory).build()
                }
            }

            url = url.newBuilder()
                .addQueryParameter("level", "0")
                .addQueryParameter("offset", "${(page - 1) * POPULAR_ITEMS_PER_PAGE}")
                .addQueryParameter("videoKind", kindNumber)
                .addQueryParameter("orderby", browseResultSort)
                .build()

            val resp = client.newCall(GET(url, headers)).execute()
            // Todo: remove SAnimeWithInfo data class if no longer needed
            val animeListWithInfo = resp.asModel(SAnimeWithInfoDeserializer)
            return AnimesPage(animeListWithInfo.animes, animeListWithInfo.animes.size == POPULAR_ITEMS_PER_PAGE)
        } else {
//            star=8&year=1900,2025
            url = url.newBuilder()
                .addQueryParameter("level", "0")
                .addPathSegment("AdvancedSearch")
                .addQueryParameter("type", kindName)
                .addQueryParameter("page", "${page - 1}")
                .addQueryParameter("year", year)
                .build()

            if (bothCategory.isNotBlank()) {
                url = url.newBuilder().addQueryParameter("category_id", bothCategory).build()
            }

            if (query.isNotBlank()) {
                url = url.newBuilder()
                    .addQueryParameter("videoTitle", query)
                    .build()
            }

            if (staffTitle.isNotBlank()) {
                url = url.newBuilder()
                    .addQueryParameter("staffTitle", staffTitle)
                    .build()
            }

            val resp = client.newCall(GET(url, headers)).execute()
            val animeList = resp.asModelList(SAnimeDeserializer)
            return AnimesPage(animeList, animeList.size == SEARCH_ITEMS_PER_PAGE)
        }
    }

    override fun searchAnimeParse(response: Response) =
        throw UnsupportedOperationException("Not used.")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        throw UnsupportedOperationException("Not used.")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodeList = super.getEpisodeList(anime)

        if (episodeList.isNotEmpty()) {
            return episodeList.sortedWith(
                compareBy(
                    { it.name.split(SEASON_EPISODE_DELIMITER).first().parseAs<Int>() },
                    { it.name.split(SEASON_EPISODE_DELIMITER).last().parseAs<Int>() },
                ),
            ).reversed()
        } else {
            return listOf(
                SEpisode.create().apply {
                    url = anime.url
                    episode_number = 1.0F
                    name = "movie"
                },
            )
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = GET("$apiBaseUrl/videoSeason/id/${anime.url}")

    override fun episodeListParse(response: Response) = response.asModelList(SEpisodeDeserializer)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val extension = preferences.getString(PREF_SUBTITLE_EXT_KEY, PREF_SUBTITLE_EXT_DEFAULT)!!
        val language = preferences.getString(PREF_SUBTITLE_LANG_KEY, PREF_SUBTITLE_LANG_DEFAULT)!!
        val subs = this.client.newCall(GET("$apiBaseUrl/translationFiles/id/${episode.url}")).execute()
            .asModel(SubtitleDeserialize)
            .sortedWith(
                compareBy(
                    { it.lang.split(SUBTITLE_DELIMITER).contains(extension) },
                    { it.lang.split(SUBTITLE_DELIMITER).contains(language) },
                ),
            ).reversed()

        return super.getVideoList(episode).map {
            Video(url = it.url, quality = it.quality, videoUrl = it.videoUrl, subtitleTracks = subs)
        }
    }

    override fun videoListRequest(episode: SEpisode) = GET("$apiBaseUrl/transcoddedFiles/id/${episode.url}")

    override fun videoListParse(response: Response) = response.asModelList(VideoDeserializer)

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private open class SingleSelectFilter(displayName: String, val vals: Array<Pair<String, Int>>, default: Int = 0) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), default) {
        fun getNameValue() = vals[state].first.lowercase()
        fun getNumberValue() = vals[state].second
    }

    private open class MultipleSelectFilter(displayName: String, vals: Array<Pair<String, Int>>) :
        AnimeFilter.Group<CheckBoxFilter>(displayName, vals.map { CheckBoxFilter(it.first, false, it.second) }) {
        fun getSelectedIds(): List<Int> =
            this.state.filter { it.state }.map { it.value }
        fun hasSelected(): Boolean = this.state.any { it.state }
    }

    private open class CheckBoxFilter(displayName: String, default: Boolean, val value: Int = 0) : AnimeFilter.CheckBox(displayName, default)

    private open class YearFilter(displayName: String, years: Pair<YearTextFilter, YearTextFilter>) : AnimeFilter.Group<YearTextFilter>(
        displayName,
        years.toList(),
    ) {
        fun getFormatted(): String = this.state.map {
            it.state.ifBlank { it.default }
        }.joinToString(",")
    }

    private open class YearTextFilter(displayName: String, val default: String) : AnimeFilter.Text(displayName, default)

    private open class BrowseResultSort(
        displayName: String,
        val vals: Array<Pair<String, Pair<String, String>>>,
        val default: Selection = Selection(0, false),
    ) : AnimeFilter.Sort(displayName, vals.map { it.first }.toTypedArray(), default) {
        fun getValue(): String {
            val currentState = state ?: default
            val sortKind = vals[currentState.index].second
            return if (currentState.ascending) {
                sortKind.first
            } else {
                sortKind.second
            }
        }
    }

    private open class StaffTitleFilter(displayName: String) : AnimeFilter.Text(displayName)

    override fun getFilterList() = AnimeFilterList(
        StaffTitleFilter(STAFF_TITLE_FILTER_NAME),
        CheckBoxFilter(IS_BROWSING_FILTER_NAME, false),
        SingleSelectFilter(
            KIND_FILTER_NAME,
            KINDS_LIST,
        ),
        MultipleSelectFilter(
            MAIN_CATEGORY_FILTER_NAME,
            arrayOf(
                Pair("Action", 84),
                Pair("Adventure", 56),
                Pair("Animation", 57),
                Pair("Comedy", 59),
                Pair("Crime", 60),
                Pair("Documentary", 61),
                Pair("Drama", 62),
                Pair("Fantasy", 67),
                Pair("Horror", 70),
                Pair("Mystery", 76),
                Pair("Romance", 77),
                Pair("Sci-Fi", 78),
                Pair("Sport", 79),
                Pair("Thriller", 80),
                Pair("Western", 89),
            ),
        ),
        MultipleSelectFilter(
            SUB_CATEGORY_FILTER_NAME,
            arrayOf(
                Pair("Biography", 58),
                Pair("Family", 65),
                Pair("History", 68),
                Pair("Musical", 75),
                Pair("War", 81),
                Pair("Supernatural", 87),
                Pair("Music", 88),
                Pair("Talk-Show", 90),
                Pair("Short", 97),
                Pair("Reality-TV", 101),
                Pair("Arabic dubbed", 102),
                Pair("News", 104),
                Pair("Ecchi", 105),
                Pair("Film-Noir", 106),
                Pair("Game", 111),
                Pair("Psychological", 112),
                Pair("Slice of Life", 113),
                Pair("Game-Show", 118),
                Pair("Magic", 123),
                Pair("Super Power", 124),
                Pair("Seinen", 125),
                Pair("Shounen", 126),
                Pair("School", 127),
                Pair("Sports", 128),
                Pair("Iraqi", 130),
            ),
        ),
        SingleSelectFilter(
            LANGUAGE_FILTER_NAME,
            arrayOf(
                Pair("", 0),
                Pair("English", 7),
                Pair("Arabic", 9),
                Pair("Hindi", 10),
                Pair("French", 11),
                Pair("German", SEARCH_ITEMS_PER_PAGE),
                Pair("Italian", 13),
                Pair("Spanish", 14),
                Pair("Chinese", 21),
                Pair("Japanese", 22),
                Pair("Korean", 23),
                Pair("Russian", LATEST_ITEMS_PER_PAGE),
                Pair("Turkish", 25),
                Pair("Norwegian", 26),
                Pair("Persian", 27),
                Pair("Swedish", 35),
                Pair("Hungary", 36),
                Pair("Polish", 38),
                Pair("Dutch", 39),
                Pair("Portuguese", 40),
                Pair("Indonesian", 41),
                Pair("Danish", 43),
                Pair("Romania", 44),
                Pair("Ukrainian", 48),
                Pair("Mandarin", 52),
                Pair("Catalan", 65),
                Pair("Filipino", 68),
                Pair("Hungarian", 76),
                Pair("Thai", 80),
                Pair("Croatian", 84),
                Pair(" Malay", 85),
                Pair("Finnish", 86),
                Pair("Vietnamese", 88),
                Pair("Zulu", 89),
                Pair("Taiwan", 47),
                Pair("Bulgarian", 95),
                Pair("Serbian", 97),
                Pair("Greek", 28),
                Pair("Finland", 37),
                Pair("Iran", 42),
                Pair("Hebrew", 46),
                Pair("Icelandic", 56),
                Pair("Georgian", 58),
                Pair("Pakistani", 61),
                Pair("Czeck", 72),
                Pair("Latvian", 87),
                Pair("Kazakh", 90),
                Pair("Estonian", 91),
                Pair("Quechua", 92),
                Pair("Multi Language", 93),
                Pair("Papiamento", 94),
                Pair("Albanian", 100),
                Pair("Slovenian", 103),
                Pair("Macedonian", 109),
                Pair("Kurdish", 112),
                Pair("Irish", 115),
                Pair("Afghani", 106),
            ),
        ),
        YearFilter(
            YEAR_FILTER_NAME,
            YearTextFilter("start", "1900") to
                YearTextFilter("end", Calendar.getInstance().get(Calendar.YEAR).toString()),
        ),
        BrowseResultSort(
            BROWSE_RESULT_SORT_FILTER_NAME,
            arrayOf(
                Pair("Upload", Pair("asc", "desc")),
                Pair("Release", Pair("r_asc", "r_desc")),
                Pair("Name", Pair("title_asc", "title_desc")),
                Pair("View", Pair("views_asc", "views_desc")),
                Pair("Age Rating", Pair("rating_asc", "rating_desc")),
            ),
        ),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LATEST_KIND_KEY
            title = "Preferred Latest kind"
            entries = KINDS_LIST.map { it.first }.toTypedArray()
            entryValues = KINDS_LIST.map { it.first }.toTypedArray()
            setDefaultValue(PREF_LATEST_KIND_DEFAULT)
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
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
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
            key = PREF_SUBTITLE_LANG_KEY
            title = "Preferred Subtitle Language"
            entries = LANG_LIST
            entryValues = LANG_LIST
            setDefaultValue(PREF_SUBTITLE_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUBTITLE_EXT_KEY
            title = "Preferred Subtitle Extension"
            entries = EXT_LIST
            entryValues = EXT_LIST
            setDefaultValue(PREF_SUBTITLE_EXT_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
