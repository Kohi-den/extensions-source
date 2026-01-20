package eu.kanade.tachiyomi.lib.lycorisextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import android.util.Base64
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class LycorisCafeExtractor(private val client: OkHttpClient) {
    private val GETLNKURL = "https://www.lycoris.cafe/api/watch/getVideoLink"

    private val DECRYPTURL = "https://www.lycoris.cafe/api/watch/decryptVideoLink"

    private val DECRYPT_API_KEY = "303a897d-sd12-41a8-84d1-5e4f5e208878"

    // Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/lycoris.py
    fun getVideosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {

        val videos = mutableListOf<Video>()

        val document = client.newCall(
            GET(url, headers = headers),
        ).execute().asJsoup()

        val script =
            document.selectFirst("script[type='application/json']")?.data() ?: return emptyList()

        val scriptData = script.parseAs<ScriptBody>()

        val data = scriptData.body.parseAs<ScriptEpisode>()

        val linkList = fetchAndDecodeVideo(client, headers, data.episodeInfo.id.toString())


        linkList.FHD?.takeIf { checkLinks(client, it) }?.let {
            videos.add(Video(it, "${prefix}lycoris.cafe - 1080p", it))
        }
        linkList.HD?.takeIf { checkLinks(client, it) }?.let {
            videos.add(Video(it, "${prefix}lycoris.cafe - 720p", it))
        }
        linkList.SD?.takeIf { checkLinks(client, it) }?.let {
            videos.add(Video(it, "${prefix}lycoris.cafe - 480p", it))
        }
        return videos

    }


    private fun fetchAndDecodeVideo(client: OkHttpClient, headers: Headers, episodeId: String): VideoLinksApi {

        val decryptHeaders = headers.newBuilder()
            .add("x-api-key", DECRYPT_API_KEY)
            .add("Content-Type", "application/json")
            .build()

        val url: HttpUrl = GETLNKURL.toHttpUrl().newBuilder()
            .addQueryParameter("id", episodeId)
            .build()

        val encryptedText = client.newCall(GET(url))
            .execute().body.string()

        val textByte = encryptedText.toByteArray(Charsets.ISO_8859_1)

        val base64Data = String(Base64.encode(textByte, Base64.DEFAULT), Charsets.UTF_8)

        val jsonObject = JSONObject()
        jsonObject.put("encoded", base64Data)

        client.newCall(POST(
            DECRYPTURL, headers = decryptHeaders, body = jsonObject.toString().toRequestBody("application/json".toMediaType()) )
        ).execute().use { response ->
            return response.body.string().parseAs<VideoLinksApi>()
        }

    }

    private fun checkLinks(client: OkHttpClient, link: String): Boolean {
        if (!link.contains("https://")) return false

        client.newCall(GET(link)).execute().use { response ->
            return response.code.toString() == "200"
        }
    }
    @Serializable
    data class ScriptBody(
        val body: String
    )

    @Serializable
    data class ScriptEpisode(
        val episodeInfo: EpisodeInfo
    )

    @Serializable
    data class EpisodeInfo(
        val id: Int? = null,
        val FHD: String? = null,
        val HD: String? = null,
        val SD: String? = null,
    )

    @Serializable
    data class VideoLinksApi(
        val HD: String? = null,
        val SD: String? = null,
        val FHD: String? = null,
        val Source: String? = null,
        val SourceMKV: String? = null
    )

}
