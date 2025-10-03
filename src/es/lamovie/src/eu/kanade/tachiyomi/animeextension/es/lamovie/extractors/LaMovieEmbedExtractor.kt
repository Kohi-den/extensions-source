package eu.kanade.tachiyomi.animeextension.es.lamovie.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class LaMovieEmbedExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val parsedUrl = url.toHttpUrlOrNull()
        val origin = parsedUrl?.let { "${it.scheme}://${it.host}" } ?: DEFAULT_ORIGIN
        val referer = "$origin/"

        val embedHeaders = headers.newBuilder().apply {
            set("Origin", origin)
            set("Referer", referer)
        }.build()

        val body = client.newCall(GET(url, embedHeaders)).execute().use { response ->
            response.body.string()
        }

        var playlistUrl: String? = null
        val subtitleAccumulator = linkedSetOf<Pair<String, String>>()

        fun addSubtitle(label: String?, rawUrl: String) {
            val resolvedUrl = rawUrl.unescapeUrl()
            if (resolvedUrl.isBlank()) return
            val resolvedLabel = label?.takeIf(String::isNotBlank) ?: "Subtitle"
            subtitleAccumulator.add(resolvedLabel to resolvedUrl)
        }

        CONFIG_REGEX.find(body)?.groupValues?.getOrNull(1)?.let { configText ->
            val configJson = runCatching { json.parseToJsonElement(configText).jsonObject }.getOrNull()
            configJson?.get("file")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                playlistUrl = it.unescapeUrl()
            }
            configJson?.get("subtitle")?.jsonPrimitive?.contentOrNull?.let { subtitleRaw ->
                SUBTITLE_REGEX.findAll(subtitleRaw).forEach { match ->
                    addSubtitle(match.groupValues[1], match.groupValues[2])
                }
            }
        }

        val scriptUnpacked = SCRIPT_REGEX.find(body)?.value?.let { script ->
            JsUnpacker.unpackAndCombine(script) ?: manualUnpack(script) ?: script
        }

        if (playlistUrl.isNullOrBlank()) {
            playlistUrl = scriptUnpacked?.let { unpacked ->
                M3U8_REGEX.find(unpacked)?.value?.unescapeUrl()
            }
        }

        scriptUnpacked?.let { unpacked ->
            SUBTITLE_REGEX.findAll(unpacked).forEach { match ->
                addSubtitle(match.groupValues[1], match.groupValues[2])
            }
        }

        val subtitleList = playlistUtils.fixSubtitles(
            subtitleAccumulator.map { (label, subUrl) -> Track(subUrl, label) },
        )

        val resolvedPlaylistUrl = playlistUrl ?: return emptyList()

        val videoNameGen: (String) -> String = { quality ->
            val label = when {
                quality.equals("Video", ignoreCase = true) || quality.isBlank() -> "HLS"
                quality.all(Char::isDigit) -> "${quality}p"
                else -> quality
            }
            if (prefix.isBlank()) label else "$prefix - $label"
        }

        return playlistUtils.extractFromHls(
            playlistUrl = resolvedPlaylistUrl,
            referer = referer,
            videoNameGen = videoNameGen,
            subtitleList = subtitleList,
        )
    }

    private fun String.unescapeUrl(): String = replace("\\/", "/").replace("&amp;", "&")

    private fun manualUnpack(script: String): String? {
        var current = script
        var decoded = false

        repeat(MAX_UNPACK_ITERATIONS) {
            val match = PACKER_REGEX.find(current) ?: return@repeat

            val payload = match.groupValues.getOrNull(1)?.unescapePackerString() ?: return@repeat
            val base = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@repeat
            val count = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return@repeat
            val dictionaryRaw = match.groupValues.getOrNull(4) ?: return@repeat
            val dictionary = if (dictionaryRaw.isEmpty()) emptyList() else dictionaryRaw.split("|")

            var result = payload
            for (index in count - 1 downTo 0) {
                val replacement = dictionary.getOrNull(index) ?: continue
                if (replacement.isEmpty()) continue

                val token = index.toPackerToken(base)
                if (token.isEmpty()) continue

                val regex = Regex("\\b" + Regex.escape(token) + "\\b")
                result = result.replace(regex, replacement)
            }

            current = result
            decoded = true
        }

        return if (decoded) current else null
    }

    private fun Int.toPackerToken(radix: Int): String {
        if (radix !in 2..PACKER_ALPHABET.length) return ""
        if (this == 0) return PACKER_ALPHABET.first().toString()

        var value = this
        val builder = StringBuilder()

        while (value > 0) {
            val digit = value % radix
            builder.append(PACKER_ALPHABET[digit])
            value /= radix
        }

        return builder.reverse().toString()
    }

    private fun String.unescapePackerString(): String =
        replace("\\\\", "\\")
            .replace("\\'", "'")

    companion object {
        private const val DEFAULT_ORIGIN = "https://lamovie.link"

        private const val MAX_UNPACK_ITERATIONS = 3
        private const val PACKER_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        private val CONFIG_REGEX = Regex(
            pattern = """<script\s+id['\"]config['\"][^>]*>(\{[\s\S]*?\})</script>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val SCRIPT_REGEX = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split('\|')\)\)""")
        private val PACKER_REGEX = Regex(
            pattern = """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\('([^']*)',(\\d+),(\\d+),'([^']*)'\.split\('\|'\)\)""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val M3U8_REGEX = Regex("""https?://[^\s'\"]+\.m3u8[^\s'\"]*""")
        private val SUBTITLE_REGEX = Regex("""\[(.+?)](https?://[^\s'\"]+)""")
    }
}
