package eu.kanade.tachiyomi.animeextension.es.samatodenvideos

import android.text.Html
import android.util.Base64
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.OffsetDateTime

class SamatoDenVideosFactory : AnimeSourceFactory {
    override fun createSources() = listOf(SamatoDenVideos())
}

class SamatoDenVideos : AnimeHttpSource() {

    override val name = "Samato's Den: Videos"
    override val baseUrl = "https://samatoden.blogspot.com"
    override val lang = "es"
    override val supportsLatest = true
    override val versionId = 1

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/html, */*")

    override fun popularAnimeRequest(page: Int): Request = GET(feedUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseFeedPage(response.body.string())

    override fun latestUpdatesRequest(page: Int): Request = GET(feedUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseFeedPage(response.body.string())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET(feedUrl(page, query = query.trim()), headers)

    override fun searchAnimeParse(response: Response): AnimesPage = parseFeedPage(response.body.string())

    override fun animeDetailsRequest(anime: SAnime): Request = GET(normalizePostFeedUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime = entryToAnime(parseSingleEntry(response.body.string()))

    override fun episodeListRequest(anime: SAnime): Request = GET(normalizePostFeedUrl(anime.url), headers)

    override fun episodeListParse(response: Response): List<SEpisode> = entryToEpisodes(parseSingleEntry(response.body.string()))

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    override fun getEpisodeUrl(episode: SEpisode): String = decodeEpisodePayload(episode.url).videoUrl

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val payload = decodeEpisodePayload(episode.url)
        val videoHeaders = Headers.Builder()
            .add("Referer", payload.referer ?: "$baseUrl/")
            .build()

        return listOf(
            Video(
                payload.videoUrl,
                episode.name,
                payload.videoUrl,
                videoHeaders,
            ),
        )
    }

    private fun feedUrl(page: Int, query: String = ""): String {
        val startIndex = ((page - 1) * PAGE_SIZE) + 1
        val queryPart = if (query.isBlank()) {
            ""
        } else {
            "&q=${query.urlEncode()}"
        }
        return "$baseUrl/feeds/posts/default/-/videos?alt=json&max-results=$PAGE_SIZE&start-index=$startIndex$queryPart"
    }

    private fun parseFeedPage(json: String): AnimesPage {
        val root = JSONObject(json)
        val feed = root.optJSONObject("feed") ?: return AnimesPage(emptyList(), false)
        val entries = feed.optJSONArray("entry") ?: JSONArray()
        val animeList = buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                add(entryToAnime(entries.getJSONObject(index)))
            }
        }

        val total = feed.optJSONObject("openSearch\$totalResults")?.optString("\$t")?.toIntOrNull() ?: animeList.size
        val start = feed.optJSONObject("openSearch\$startIndex")?.optString("\$t")?.toIntOrNull() ?: 1
        val perPage = feed.optJSONObject("openSearch\$itemsPerPage")?.optString("\$t")?.toIntOrNull() ?: animeList.size
        val hasNextPage = (start + perPage - 1) < total

        return AnimesPage(animeList, hasNextPage)
    }

    private fun parseSingleEntry(json: String): JSONObject {
        val root = JSONObject(json)
        root.optJSONObject("entry")?.let { return it }

        val feed = root.optJSONObject("feed")
        val entries = feed?.optJSONArray("entry")
        if (entries != null && entries.length() > 0) {
            return entries.getJSONObject(0)
        }

        error("No se encontro ninguna entrada en la respuesta de Blogger")
    }

    private fun entryToAnime(entry: JSONObject): SAnime {
        val html = entry.optJSONObject("content")?.optString("\$t").orEmpty()
        val anime = SAnime.create()
        anime.url = normalizePostFeedUrl(linkHref(entry, "self").orEmpty())
        anime.title = entry.optJSONObject("title")?.optString("\$t").orEmpty()
        anime.artist = extractPrimaryCredit(html)
        anime.author = extractPrimaryCredit(html)
        anime.description = extractPrimaryCredit(html)
        anime.genre = extractCategories(entry).joinToString(", ").ifBlank { null }
        anime.status = SAnime.COMPLETED
        anime.thumbnail_url = extractThumbnail(entry, html)
        anime.initialized = true
        return anime
    }

    private fun entryToEpisodes(entry: JSONObject): List<SEpisode> {
        val animeTitle = entry.optJSONObject("title")?.optString("\$t").orEmpty()
        val html = entry.optJSONObject("content")?.optString("\$t").orEmpty()
        val uploadedAt = parseDateMillis(entry.optJSONObject("published")?.optString("\$t"))
        val defaultImage = extractThumbnail(entry, html)
        val referer = linkHref(entry, "alternate") ?: "$baseUrl/"

        val playlistEpisodes = parsePlaylistItems(html).mapIndexed { index, item ->
            createEpisode(
                name = item.title.ifBlank { "$animeTitle ${index + 1}" },
                videoUrl = item.file,
                thumbnail = item.image ?: defaultImage,
                referer = referer,
                episodeNumber = (index + 1).toFloat(),
                uploadedAt = uploadedAt,
            )
        }.toList()
        if (playlistEpisodes.isNotEmpty()) {
            return playlistEpisodes
        }

        val singleFile = SINGLE_FILE_REGEX.find(html)?.groupValues?.getOrNull(1)
        return if (singleFile.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(
                createEpisode(
                    name = animeTitle.ifBlank { "Video" },
                    videoUrl = singleFile,
                    thumbnail = defaultImage,
                    referer = referer,
                    episodeNumber = 1f,
                    uploadedAt = uploadedAt,
                ),
            )
        }
    }

    private fun createEpisode(
        name: String,
        videoUrl: String,
        thumbnail: String?,
        referer: String,
        episodeNumber: Float,
        uploadedAt: Long,
    ): SEpisode {
        val episode = SEpisode.create()
        episode.url = encodeEpisodePayload(
            EpisodePayload(
                videoUrl = videoUrl,
                thumbnail = thumbnail,
                referer = referer,
            ),
        )
        episode.name = name
        episode.date_upload = uploadedAt
        episode.episode_number = episodeNumber
        episode.scanlator = null
        return episode
    }

    private fun extractPrimaryCredit(html: String): String? = extractCreditLines(html).firstOrNull()

    private fun extractCreditLines(html: String): List<String> =
        buildList {
            EDITED_BY_CAPTURE_REGEX.findAll(html)
                .map { cleanHtml(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .forEach(::add)

            STRONG_ARTIST_REGEX.findAll(html)
                .map { cleanHtml(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .forEach(::add)

            HEADING_ARTISTS_SECTION_REGEX.findAll(html)
                .map { cleanHtml(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }.distinct()

    private fun extractThumbnail(entry: JSONObject, html: String): String? {
        val inlineImage = HIDDEN_IMAGE_REGEX.find(html)?.groupValues?.getOrNull(1)
        if (!inlineImage.isNullOrBlank()) {
            return inlineImage
        }

        val playlistImage = PLAYLIST_IMAGE_REGEX.find(html)?.groupValues?.getOrNull(1)
        if (!playlistImage.isNullOrBlank()) {
            return playlistImage
        }

        val mediaThumb = entry.optJSONObject("media\$thumbnail")?.optString("url")
        return mediaThumb?.substringBefore("=s72")
    }

    private fun extractCategories(entry: JSONObject): List<String> {
        val categories = entry.optJSONArray("category") ?: return emptyList()
        return buildList(categories.length()) {
            for (index in 0 until categories.length()) {
                categories.optJSONObject(index)?.optString("term")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun linkHref(entry: JSONObject, rel: String): String? {
        val links = entry.optJSONArray("link") ?: return null
        for (index in 0 until links.length()) {
            val link = links.optJSONObject(index) ?: continue
            if (link.optString("rel") == rel) {
                return link.optString("href")
            }
        }
        return null
    }

    private fun normalizePostFeedUrl(url: String): String =
        if (url.contains("alt=json")) url else "$url${if (url.contains("?")) "&" else "?"}alt=json"

    private fun parseDateMillis(value: String?): Long =
        value?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: 0L

    private fun cleanHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun cleanCredit(value: String): String =
        value
            .replace(EDITED_BY_REGEX, "")
            .replace(ARTIST_BY_REGEX, "")
            .trim()

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun parsePlaylistItems(html: String): List<PlaylistItem> {
        val playlistContent = extractJsArrayContent(html, "playlist") ?: return emptyList()
        return extractTopLevelObjects(playlistContent).mapNotNull { block ->
            val file = JS_FILE_REGEX.find(block)?.groupValues?.getOrNull(1).orEmpty()
            if (file.isBlank()) return@mapNotNull null

            PlaylistItem(
                title = JS_TITLE_REGEX.find(block)?.groupValues?.getOrNull(1).orEmpty(),
                file = file,
                image = JS_IMAGE_REGEX.find(block)?.groupValues?.getOrNull(1)?.ifBlank { null },
            )
        }
    }

    private fun extractJsArrayContent(html: String, propertyName: String): String? {
        val propertyIndex = html.indexOf("$propertyName:")
            .takeIf { it >= 0 }
            ?: html.indexOf("$propertyName :")
                .takeIf { it >= 0 }
            ?: return null

        val start = html.indexOf('[', propertyIndex)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaping = false
        for (index in start until html.length) {
            val ch = html[index]
            if (escaping) {
                escaping = false
                continue
            }
            if (ch == '\\') {
                escaping = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            when (ch) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return html.substring(start + 1, index)
                    }
                }
            }
        }
        return null
    }

    private fun extractTopLevelObjects(content: String): List<String> {
        val objects = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaping = false

        content.forEachIndexed { index, ch ->
            if (escaping) {
                escaping = false
                return@forEachIndexed
            }
            if (ch == '\\') {
                escaping = true
                return@forEachIndexed
            }
            if (ch == '"') {
                inString = !inString
                return@forEachIndexed
            }
            if (inString) return@forEachIndexed

            when (ch) {
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects += content.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    private fun encodeEpisodePayload(payload: EpisodePayload): String {
        val encoded = Base64.encodeToString(
            payload.toJson().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$EPISODE_PREFIX$encoded"
    }

    private fun decodeEpisodePayload(url: String): EpisodePayload {
        require(url.startsWith(EPISODE_PREFIX)) { "Unsupported episode url: $url" }
        val raw = url.removePrefix(EPISODE_PREFIX)
        val decoded = Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return EpisodePayload.fromJson(String(decoded, Charsets.UTF_8))
    }

    private data class EpisodePayload(
        val videoUrl: String,
        val thumbnail: String?,
        val referer: String?,
    ) {
        fun toJson(): String = JSONObject()
            .put("videoUrl", videoUrl)
            .put("thumbnail", thumbnail)
            .put("referer", referer)
            .toString()

        companion object {
            fun fromJson(json: String): EpisodePayload {
                val obj = JSONObject(json)
                return EpisodePayload(
                    videoUrl = obj.getString("videoUrl"),
                    thumbnail = obj.optString("thumbnail").ifBlank { null },
                    referer = obj.optString("referer").ifBlank { null },
                )
            }
        }
    }

    private data class PlaylistItem(
        val title: String,
        val file: String,
        val image: String?,
    )

    companion object {
        private const val PAGE_SIZE = 30
        private const val EPISODE_PREFIX = "samatoden://video/"

        private val HIDDEN_IMAGE_REGEX = Regex("""<img[^>]+src="([^"]+)"""", setOf(RegexOption.IGNORE_CASE))
        private val EDITED_BY_REGEX = Regex("""^\s*edited\s+by\s*:?\s*""", setOf(RegexOption.IGNORE_CASE))
        private val ARTIST_BY_REGEX = Regex("""^\s*artist(?:s)?\s*:?\s*""", setOf(RegexOption.IGNORE_CASE))
        private val EDITED_BY_CAPTURE_REGEX = Regex("""<li[^>]*>\s*Edited\s+by\s*([^<]+)</li>""", setOf(RegexOption.IGNORE_CASE))
        private val STRONG_ARTIST_REGEX = Regex("""<strong>\s*Artists?:\s*</strong>\s*([^<]+)""", setOf(RegexOption.IGNORE_CASE))
        private val HEADING_ARTISTS_SECTION_REGEX = Regex(
            """<h3[^>]*>.*?Artists?.*?</h3>\s*<ul>\s*<li[^>]*>(.*?)</li>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val SINGLE_FILE_REGEX = Regex("""(?<!\w)file\s*:\s*"([^"]+)"""")
        private val JS_TITLE_REGEX = Regex("""title\s*:\s*"([^"]*)"""", setOf(RegexOption.IGNORE_CASE))
        private val JS_FILE_REGEX = Regex("""file\s*:\s*"([^"]+)"""", setOf(RegexOption.IGNORE_CASE))
        private val JS_IMAGE_REGEX = Regex("""image\s*:\s*"([^"]+)"""", setOf(RegexOption.IGNORE_CASE))
        private val PLAYLIST_IMAGE_REGEX = Regex(
            """playlist\s*:\s*\[.*?image\s*:\s*"([^"]+)"""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
