package eu.kanade.tachiyomi.animeextension.pt.smartanimes.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.googledriveplayerextractor.GoogleDrivePlayerExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class SmartAnimesExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .build()

    private val gdriveExtractor by lazy { GoogleDrivePlayerExtractor(client, headers) }
    private val sendNowExtractor by lazy { SendNowExtractor(client, headers) }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val content = client.newCall(GET(url, headers)).execute().body.string()

        val item = content.substringAfter("var item = ", "")
            .substringBefore(";")
            .parseAs<ItemDto>()

        val options = content.substringAfter("var options = ", "")
            .substringBefore(";")
            .parseAs<OptionsDto>()

        val newHeaders = headers.newBuilder()
            .set("Referer", item.post)
            .build()

        val formBody = FormBody.Builder()
            .add("token", item.token)
            .add("id", item.id.toString())
            .add("time", item.time.toString())
            .add("post", item.post)
            .add("redirect", item.redirect)
            .add("cacha", item.cacha)
            .add("new", "false")
            .add("link", item.link)
            .add("action", options.soralink_z)
            .build()

        val sourceUrl =
            noRedirectClient.newCall(POST(options.soralink_ajaxurl, newHeaders, formBody))
                .execute().use { it.header("location") }
                ?: return emptyList()

        return when {
            "drive.google.com" in sourceUrl -> gdriveExtractor.videosFromUrl(sourceUrl)
            "send.now" in sourceUrl -> sendNowExtractor.videosFromUrl(sourceUrl, name)

            else -> emptyList()
        }
    }

    @Serializable
    data class ItemDto(
        val token: String,
        val id: Int,
        val time: Int,
        val post: String,
        val redirect: String,
        val cacha: String,
        val new: Boolean,
        val link: String,
    )

    @Serializable
    data class OptionsDto(
        val soralink_z: String,
        val soralink_ajaxurl: String,
    )
}
