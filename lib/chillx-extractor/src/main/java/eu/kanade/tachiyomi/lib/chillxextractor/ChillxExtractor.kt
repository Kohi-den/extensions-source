package eu.kanade.tachiyomi.lib.chillxextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class ChillxExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private val REGEX_MASTER_JS = Regex("""\s*=\s*'([^']+)""")
        private val REGEX_SOURCES = Regex("""sources:\s*\[\{"file":"([^"]+)""")
        private val REGEX_FILE = Regex("""file: ?"([^"]+)"""")
        private val REGEX_SOURCE = Regex("""source = ?"([^"]+)"""")
        private val REGEX_SUBS = Regex("""\{"file":"([^"]+)","label":"([^"]+)","kind":"captions","default":\w+\}""")
        private const val KEY_SOURCE = "https://raw.githubusercontent.com/Rowdy-Avocado/multi-keys/keys/index.html"
    }

    fun videoFromUrl(url: String, referer: String, prefix: String = "Chillx - "): List<Video> {
        val newHeaders = headers.newBuilder()
            .set("Referer", "$referer/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.5")
            .build()

        val body = client.newCall(GET(url, newHeaders)).execute().body.string()

        val master = REGEX_MASTER_JS.find(body)?.groupValues?.get(1) ?: return emptyList()
        val aesJson = json.decodeFromString<CryptoInfo>(master)
        val key = fetchKey() ?: throw ErrorLoadingException("Unable to get key")
        val decryptedScript = CryptoAES.decryptWithSalt(aesJson.ciphertext, aesJson.salt, key)
            .replace("\\n", "\n")
            .replace("\\", "")

        val masterUrl = REGEX_SOURCES.find(decryptedScript)?.groupValues?.get(1)
            ?: REGEX_FILE.find(decryptedScript)?.groupValues?.get(1)
            ?: REGEX_SOURCE.find(decryptedScript)?.groupValues?.get(1)
            ?: return emptyList()

        val subtitleList = buildList {
            val subtitles = REGEX_SUBS.findAll(decryptedScript)
            subtitles.forEach {
                add(Track(it.groupValues[1], decodeUnicodeEscape(it.groupValues[2])))
            }
        }

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = url,
            videoNameGen = { "$prefix$it" },
            subtitleList = subtitleList,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun fetchKey(): String? {
        return client.newCall(GET(KEY_SOURCE)).execute().parseAs<KeysData>().keys.firstOrNull()
    }

    private fun decodeUnicodeEscape(input: String): String {
        val regex = Regex("u([0-9a-fA-F]{4})")
        return regex.replace(input) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
    }

    @Serializable
    data class CryptoInfo(
        @SerialName("ct") val ciphertext: String,
        @SerialName("s") val salt: String,
    )

    @Serializable
    data class KeysData(
        @SerialName("chillx") val keys: List<String>
    )
}
class ErrorLoadingException(message: String) : Exception(message)
