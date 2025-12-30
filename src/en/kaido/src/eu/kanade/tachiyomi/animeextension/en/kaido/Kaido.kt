package eu.kanade.tachiyomi.animeextension.en.kaido

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class Kaido : ZoroTheme(
    "en",
    "Kaido",
    "https://kaido.to",
    hosterNames = listOf(
        "Vidstreaming",
        "Vidcloud",
//        "StreamTape",
    ),
) {

    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers) }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "Vidstreaming", "Vidcloud" -> runBlocking(Dispatchers.IO) {
                megaCloudExtractor.videosFromUrl(
                    server.link,
                    prefix = "${server.name.uppercase()}-${server.type.uppercase()}"
                )
            }

            else -> emptyList()
        }
    }
}
