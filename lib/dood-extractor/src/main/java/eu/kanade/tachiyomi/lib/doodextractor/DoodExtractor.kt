package eu.kanade.tachiyomi.lib.doodextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URI

class DoodExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(
        url: String,
        prefix: String? = null,
        redirect: Boolean = true,
        externalSubs: List<Track> = emptyList(),
    ): Video? {
        return runCatching {
            val response = client.newCall(GET(url)).execute()
            val newUrl = if (redirect) response.request.url.toString() else url

            val doodHost = getBaseUrl(newUrl)
            val content = response.body.string()
            if (!content.contains("'/pass_md5/")) return null

            // Obtener la calidad del título de la página
            val extractedQuality = Regex("\\d{3,4}p")
                .find(content.substringAfter("<title>").substringBefore("</title>"))
                ?.groupValues
                ?.getOrNull(0)

            // Determinar la calidad a usar
            val newQuality = extractedQuality ?: ( if (redirect) " mirror" else "")

            // Obtener el hash MD5
            val md5 = doodHost + (Regex("/pass_md5/[^']*").find(content)?.value ?: return null)
            val token = md5.substringAfterLast("/")
            val randomString = createHashTable()
            val expiry = System.currentTimeMillis()

            // Obtener la URL del video
            val videoUrlStart = client.newCall(
                GET(
                    md5,
                    Headers.headersOf("referer", newUrl),
                ),
            ).execute().body.string()

            val trueUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"

            Video(trueUrl, prefix + "Doodstream " + newQuality , trueUrl, headers = doodHeaders(doodHost), subtitleTracks = externalSubs)
        }.getOrNull()
    }

    fun videosFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true,
    ): List<Video> {
        val video = videoFromUrl(url, quality, redirect)
        return video?.let(::listOf) ?: emptyList()
    }

    // Método para generar una cadena aleatoria
    private fun createHashTable(): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return buildString {
            repeat(10) {
                append(alphabet.random())
            }
        }
    }

    // Método para obtener la base de la URL
    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    // Método para obtener headers personalizados
    private fun doodHeaders(host: String) = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://$host/")
    }.build()
}
